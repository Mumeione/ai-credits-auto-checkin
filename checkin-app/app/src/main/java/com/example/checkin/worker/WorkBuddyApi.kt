package com.example.checkin.worker

import com.example.checkin.data.Jwt
import com.example.checkin.network.ApiClient
import com.example.checkin.network.HttpResult
import org.json.JSONObject

/**
 * WorkBuddy 接口层：refreshToken 续期 + 跨调用方共用的响应判定。
 *
 * 放在这里的都是「定时签到」与「查询积分」必须得出同一结论的逻辑。
 * 只被单边使用的业务规则不要往这里塞，否则这个 object 会慢慢变成杂物间。
 *
 * ⚠️ 2026-09-17 曾在这里实现过成长中心 `GET /v2/activity/growth/streak`（`fetchStreakDays`），
 * 用来当「真实连续签到天数」。用户当天更正：**那是「连续登录 PC 端」的天数，不是连续签到**
 * （该账号本月只在 PC 登录过 1 天 → `days=0`，而它本赛季签到其实是 2 天）。
 * 已整体删除，App 不再查成长中心；签到侧的「连续」没有权威字段可用，
 * 只展示 `checkin-activity-status` 的**本期赛季累计** `streak_days`。
 * 成长中心那套 7d/14d/28d 档位与补登卡属于**登录**连击，别再把它的 `days` 当签到天数。
 *
 * 此前本项目认为 WorkBuddy「无已验证的刷新接口，过期只能重新提取」——
 * 参考同作者的 [WorkBuddy-Daily](https://github.com/L0NE-6/WorkBuddy-Daily)（2026-09 仍在运行）
 * 推翻了这一结论：官方插件接口可以长期续期，且**每次续期都会轮换 refreshToken**，
 * 必须把新 RT 写回账号存储形成续期链，否则链断掉后仍要重新提取。
 *
 * 接口口径（参考实现实测）：
 * - `POST https://copilot.tencent.com/v2/plugin/auth/token/refresh`，body 为空 JSON `{}`
 * - 凭据不在 Authorization 头，而是 `X-Refresh-Token: <RT>` + `X-Auth-Refresh-Source: plugin`
 * - 响应 `{code:0, data:{accessToken, refreshToken}}`；refreshToken 未轮换时字段可能省略，沿用旧值
 *
 * 续期节奏（对齐参考实现的「智能续期」）：accessToken 7 天内将过期才主动刷新，
 * 其余情况等鉴权失败再刷——多处同时用同一个 RT 刷新会互相把对方的 RT 顶失效。
 */
object WorkBuddyApi {

    private const val REFRESH_URL = "https://copilot.tencent.com/v2/plugin/auth/token/refresh"

    /** 鉴权失败的 HTTP 状态码 / 业务码（各平台通用口径）。 */
    private val AUTH_CODES = setOf(401, 403)

    /**
     * 鉴权失败判定：HTTP 状态码、业务码或文案（token / 登录 / 未授权）任一命中。
     *
     * 这里收拢了原先分散在 [WorkBuddyWorker] 与 MainActivity「查询积分」里的两份实现。
     * 那两份靠注释约定保持口径一致，但注释挡不住漂移——一旦不一致，
     * 同一种失败会在「定时签到」和「查询积分」里得出不同结论
     * （一边自动续期后重试成功、一边直接提示 Token 失效），排查时极易误判。
     *
     * @param json 响应体解析结果，解析失败（非 JSON）时传 null。
     *   **调用方请务必把 [parseJson] 的结果传进来**，不要图省事传 null——
     *   传 null 时只剩 HTTP 码这一条路径，「HTTP 200 + 业务码 401」这种响应会漏判，
     *   于是带着已过期的 Token 又发一次写请求，到下一步才发现。
     */
    fun isAuthFailure(resp: HttpResult, json: JSONObject?): Boolean {
        if (resp.code in AUTH_CODES) return true
        if (json == null) return false
        val code = json.optInt("code")
        if (code in AUTH_CODES) return true
        // 文案兜底只在「业务码非成功」时才看。否则响应里任何地方出现「登录 / token」
        // 字样（比如活动名、提示语里顺带提到）都会被判成鉴权失效。
        // 口径与 TraeApi.isAuthFailed 的 `code != 0 && …` 一致。
        if (code == 0) return false
        val msg = json.optString("msg").ifBlank { json.optString("message") }
        return msg.contains("token", ignoreCase = true) ||
            msg.contains("登录") || msg.contains("未授权") ||
            msg.contains("unauthorized", ignoreCase = true)
    }

    /**
     * 用 refreshToken 换新 accessToken，返回 (新 accessToken, 新 refreshToken)；
     * 失败返回 null（RT 也失效 / 被服务端拒绝，只能重新提取）。
     */
    fun refreshToken(refreshToken: String): Pair<String, String>? {
        if (refreshToken.isBlank()) return null
        return try {
            val json = parseJson(
                ApiClient.post(
                    REFRESH_URL, "{}", "",
                    mapOf(
                        "X-Refresh-Token" to refreshToken,
                        "X-Auth-Refresh-Source" to "plugin",
                    ),
                )
            ) ?: return null
            // data 缺失时 access 必为空、下面一样会被拦掉，但显式 return null 才能非空化，
            // 后面读 refreshToken / refresh_token 不用再冒险碰可空接收者
            val data = json.optJSONObject("data") ?: return null
            val access = data.optString("accessToken")
            if (json.optInt("code") != 0 || access.isBlank()) return null
            // 新 RT 兼容 camelCase / snake_case 两种字段名（workbuddy-switch 同款兜底）：
            // 只认一种的话，服务端换个命名就会拿到空串、回落到已被轮换作废的旧 RT，链就断了
            val nextRefresh = data.optString("refreshToken")
                .ifBlank { data.optString("refresh_token") }
                .ifBlank { refreshToken }
            Pair(access, nextRefresh)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * accessToken 是否将在 [days] 天内过期（参考实现的刷新阈值是 7 天）。
     * exp 解析不出来时返回 false——不冒进刷新，交给鉴权失败后的被动续期兜底。
     */
    fun expiresWithinDays(token: String, days: Int): Boolean {
        val exp = Jwt.expEpochSeconds(token) ?: return false
        return exp * 1000L - System.currentTimeMillis() < days * 86_400_000L
    }

    /**
     * 统一的响应体解析入口：空 body / 非 JSON 一律返回 null，由调用方决定怎么报。
     *
     * **不要**改用 `JSONObject(body.ifBlank { "{}" })` 那种兜底：那会把「服务端没给 JSON」
     * 伪装成「`code=0` 但没有 `data`」，调用方据此报出的原因就是错的
     * （查询积分曾因此把 5xx 空响应体报成「Token 失效」）。
     */
    fun parseJson(body: String): JSONObject? = try {
        if (body.isBlank()) null else JSONObject(body)
    } catch (e: Exception) {
        null
    }
}
