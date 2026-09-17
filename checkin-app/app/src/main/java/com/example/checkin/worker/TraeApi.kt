package com.example.checkin.worker

import com.example.checkin.network.ApiClient
import org.json.JSONObject

/** 签到状态查询结果。区分「鉴权失效」与「其它业务失败」，别把限流当成 Token 过期。 */
sealed class TraeStatus {
    data class Ok(val json: JSONObject) : TraeStatus()
    data class AuthFailed(val code: Int, val message: String) : TraeStatus()

    /**
     * 其它业务失败。
     *
     * [retryable] 区分「限流等值得错峰重试」与「请求本身不合法、重试只会拿到同一个错误」——
     * 上层据此决定是排进重试链，还是直接报给用户去处理。
     */
    data class Failed(
        val code: Int,
        val message: String,
        val retryable: Boolean = true,
    ) : TraeStatus()
}

/** 领取签到结果。 */
sealed class TraeClaim {
    /**
     * 领取成功。[credits] 是本次到账积分，抠不出来时为 null
     * （宁可只报「签到成功」，也不要凭空编一个数字）。
     */
    data class Done(val credits: Int?, val message: String) : TraeClaim()

    /** 账号自己今天已经签过了（幂等，不是失败）。 */
    data class Already(val message: String) : TraeClaim()

    /** 服务端限流/过载，值得稍后重试。 */
    data class Busy(val code: Int, val message: String) : TraeClaim()

    /** 鉴权失效。 */
    data class AuthFailed(val code: Int, val message: String) : TraeClaim()

    /** 其它业务失败；[retryable] 语义同 [TraeStatus.Failed]。 */
    data class Failed(
        val code: Int,
        val message: String,
        val retryable: Boolean = true,
    ) : TraeClaim()
}

/**
 * Trae 签到/积分/刷新 API 封装（每个账号一个实例）。
 *
 * 认证方式：`Authorization: Cloud-IDE-JWT <token>`（不是 Bearer），
 * 且必须携带 `x-device-id` 头（缺失返回 9004）。
 *
 * 请求头采用社区验证有效的**最小集合**（参考 L0NE-6/Trae-AutoCheckin 的
 * ug_headers，2026-09 仍在成功签到）：
 * - `x-device-id` / `x-device-type` / `x-os-version` / `x-app-version` 四个设备头
 *   补齐 + body 带 req_source（参考实现的口径，未逐项独立实证，但补齐无成本）。
 * - **只发一个小写 x-device-id**：同一头重复出现（哪怕大小写变体）会被风控
 *   判为异常客户端，因此不再叠加桌面网关头（x-lscbd / x-lgw / Package-Type /
 *   双版本头等）——此前按桌面端全套静态头对齐仍持续 9074，说明多出来的头
 *   没有收益、反而更可疑。
 * - UA 用 `Trae/1.107.1`（VS Code 内核版本，参考实现口径）。
 *   注意它和 `x-app-version` **不是**一回事：后者要用 `product.json.appVersion`（`3.3.102`），
 *   详见 [APP_VERSION]。
 *
 * 9074 的处置（**2026-09-17 实测已修正，见下方结论**）：
 * - ⚠️ 曾经认为「9074 是按时间片的容量闸门、与设备号取值无关」——**这个结论是错的**，
 *   因为当时试的设备号全是伪造的（UUID / 乱造 16 位 / 拼接串），**从没试过注册过的真实号**。
 *   2026-09-17 同一分钟内的对照实验：真实 16 位 Aha 设备号 → 正常返回业务码 `9095`；
 *   拼了 userId 的设备号 → 立刻 `9074`。**9074 与设备号取值强相关**。
 * - 所以 9074 的首要看点是「`x-device-id` 是不是客户端那个**注册过的** 16 位 Aha 号」
 *   （来源见 [extract_tokens.py] 的 `trae_device_id()`）。
 * - 仍然成立的旧结论：每轮只发 1 次 claim，失败交给上层固定节奏重试（见 [DailySchedule]）。
 * - 「换新设备号立刻能签成」这条是**错的**：2026-09-16 乱造设备号仍 9074，2026-09-17
 *   拼接设备号也立刻 9074——伪造号只会换来 9074，**绝不要做设备号轮换**（频繁更换本身
 *   就是风控高危信号，而且会覆盖丢掉客户端真实设备号）。
 * - 另一个常见误区是「熬低峰就行」：低峰能缓解，但设备号不对时**熬到深夜也没用**
 *   （伪造号永远 9074）。
 *
 * 判定口径（[kindOf]）：
 * status 与 claim 共用同一套分类逻辑，避免两处各写一遍、顺序不一致导致
 * 「同一个响应在状态查询里是失败、在领取里却是限流」这类漂移。
 */
class TraeApi(
    private val accessToken: String,
    private val deviceId: String,
) {

    companion object {
        private const val UG_BASE = "https://api.trae.cn"
        private const val STATUS = "$UG_BASE/trae/api/v2/ug/checkin_credits/status"
        private const val CLAIM = "$UG_BASE/trae/api/v2/ug/checkin_credits/claim"
        private const val ENT_USAGE = "$UG_BASE/trae/api/v2/pay/ide_user_ent_usage"
        private const val EXCHANGE = "https://api.trae.com.cn/cloudide/api/v3/trae/oauth/ExchangeToken"

        // Trae CN 桌面端 stable 的 ClientID（来自 Trae 安装目录 product.json iCubeApp.authConfig）
        // SOLO 版是 en1oxy7wnw8j9n，用错会返回 "refresh token is not matched to the client"
        private const val CLIENT_ID = "ono9krqynydwx5"

        /** status / claim 的请求体，与客户端一致（空体会被拒成 9074）。 */
        private const val REQ_SOURCE_BODY = "{\"req_source\":1}"

        /**
         * VS Code 内核版本（`product.json.version`）。注意**它不是 `x-app-version` 的取值**。
         */
        private const val IDE_VERSION = "1.107.1"

        /**
         * `x-app-version` 的取值 = 客户端自己的 `product.json.appVersion`。
         *
         * 2026-09-17 从本机 `Trae CN` 安装目录读到 `appVersion = 3.3.102`
         * （也就是「关于」里显示的 3.3.x 版本），并从客户端源码
         * （`resources/app/out/main.js` 的 `fb()`）确认它发的就是这个字段。
         *
         * ⚠️ 此前这里错用了 VS Code 内核版本 `1.107.1`——版本号对不上会被服务端
         * 当成「未知/过旧客户端」，很可能是 9074 更严格限流的来源之一。
         */
        private const val APP_VERSION = "3.3.102"

        /** UA（参考实现的取值）。注意真实客户端并不发这个自造 UA（待实测是否影响风控）。 */
        private const val UA = "Trae/$IDE_VERSION"

        /** x-os-version 的取值（参考实现的兜底常量，服务端只看存在性）。 */
        private const val OS_VERSION = "10.0.19045"

        /**
         * 值得重试的失败码（HTTP 与业务码混在一个集合里，两者位数不同不会撞）。
         * 9074 =「当前参与用户太多，请稍后重试」——官方论坛实测：高峰期服务端会返这个，
         * 需要错峰/重试，不是账号或 Token 的问题。
         */
        private val BUSY_CODES = setOf(9074, 429, 500, 502, 503, 504)

        /** 鉴权失效码：1001/1002 = Token 无效/过期（HTTP 401/403 另按 HTTP 码识别）。 */
        private val AUTH_CODES = setOf(1001, 1002, 401, 403)

        /**
         * 请求参数类错误：**重试没有意义**，不该进重试链白跑
         * （链长与间隔见 [DailySchedule.MAX_RUN_ATTEMPTS] / [DailySchedule.RETRY_DELAY_MINUTES]）。
         * 9004 =「The submitted order parameters are incorrect」，实测是 claim 缺
         * `x-device-id` 时返回——请求本身不合法，再发一次还是同一个 9004。
         */
        private val PARAM_CODES = setOf(9004)

        /** 「今日已签」的业务码（参考实现实测值），比文案匹配可靠。 */
        private const val ALREADY_CODE = 9095

        private val BUSY_WORDS = listOf(
            "参与用户太多", "稍后重试", "请稍后再试", "操作太频繁", "请求过于频繁",
            "太频繁", "服务繁忙", "系统繁忙",
        )

        private val ALREADY_WORDS = listOf(
            "已签到", "已经签到", "明日再来", "今日已完成", "已领取", "重复签到",
        )

        /** 设备级去重的措辞，用来把「该设备今日已达上限」和「账号今天确实签过了」区分开。 */
        private val DEVICE_WORDS = listOf("设备", "device", "machine")

        /**
         * 设备级去重：文案形如「当前设备今日已经签到，请明日再来哦～」（业务码 `9095`）。
         *
         * 2026-09-17 实测把这一层彻底看清了：
         * - 用**真实的 16 位 Aha 设备号**时，账号未签的那次 claim 返回 `9095`，
         *   而同一时刻的 `status` 里 `checked_in=false`、`did_checked_in=true`
         *   —— 即**账号**没签，是**设备**今天的名额已被用掉；
         * - 换成拼了 userId 的设备号（伪造），同一分钟内立刻变回 `9074` 限流
         *   → **9074 与设备号取值强相关**：只有注册过的真实设备号才会被正常处理。
         *
         * 结论：同机多账号共用同一个设备号时，**每天只有第一个账号能签成**，
         * 其余账号都吃这个返回；而且**没法用伪造设备号绕过**（伪造即 9074）。
         * 重试没有意义（同一设备同一自然日），所以归为「不可重试」。
         */
        private fun isDeviceBlocked(message: String): Boolean =
            DEVICE_WORDS.any { message.contains(it, ignoreCase = true) }

        /**
         * 设备头集合。
         *
         * 2026-09-17 直接从本机 `Trae CN` 客户端源码确认了它**实际发什么**
         * （`resources/app/out/main.js` 里的 `eb()` / `fb()`）：
         * ```
         * Content-Type:   application/json
         * Authorization:  Cloud-IDE-JWT <token>
         * x-device-id:    guaranteedDeviceId          ← 就是 storage.json 里 icube-dc 的 16 位数字
         * x-device-brand: commonParams.device_model
         * x-device-type:  commonParams.os_name
         * x-os-version:   commonParams.os_version
         * x-app-version:  commonParams.app_version    ← product.json.appVersion
         * ```
         * body 是 `{"req_source":1}`（IDE=1 / Lite=2），客户端自己还带 `retry:2`。
         *
         * 已知差异（待实测确认是否影响风控）：
         * - 真实客户端**不发** `X-User-Region`，也**不发** `Trae/...` 这种自造 UA；
         * - 客户端会带 `x-device-brand`（机器型号），我们拿不到该值，所以没发。
         */
        private fun headers(deviceId: String) = mapOf(
            "User-Agent" to UA,
            "X-User-Region" to "CN",
            "x-device-id" to deviceId,
            "x-device-type" to "windows",
            "x-os-version" to OS_VERSION,
            "x-app-version" to APP_VERSION,
        )

        private fun auth(token: String, deviceId: String) =
            "Cloud-IDE-JWT $token" to headers(deviceId)

        /**
         * 判定的中间结果：一次「非成功」响应到底属于哪一类。
         * HTTP 码与业务码都要过一遍（限流既可能体现在 HTTP 429/5xx，
         * 也可能体现在业务码 9074）。
         *
         * 不含「成功」——成功态由调用方先用 [isOkCode] / `success` 短路，
         * 免得把 status 与 claim 两套成功口径混进分类器里。
         */
        private enum class Kind { DEVICE_BLOCKED, ALREADY, BUSY, PARAM, AUTH, FAIL }

        private fun isBusy(httpCode: Int, code: Int, message: String): Boolean =
            httpCode in BUSY_CODES || code in BUSY_CODES ||
                BUSY_WORDS.any { message.contains(it) }

        private fun isAuthFailed(code: Int, message: String): Boolean =
            code in AUTH_CODES ||
                (code != 0 && ("token" in message.lowercase() ||
                    message.contains("未登录") || message.contains("登录失效") ||
                    message.contains("unauthorized", ignoreCase = true)))

        /**
         * 判断「账号今天确实签过了」。
         * 只认文案，不认 1001：本项目实测 1001 是鉴权失效（见 README 踩坑记录），
         * 把它当成「已签到」会把「Token 过期」误报成签到成功。
         * 另外把「设备…」字样的响应排除掉——那是设备级去重拦截，不是账号签过了。
         */
        private fun isAlreadyCheckedIn(message: String): Boolean {
            if (DEVICE_WORDS.any { message.contains(it, ignoreCase = true) }) return false
            return ALREADY_WORDS.any { message.contains(it) }
        }

        /**
         * 统一的响应分类，status / claim 共用。
         *
         * 顺序即优先级（沿用原实现，别随意调换）：
         * 「已签到」必须在「限流」之前——这类响应常常也带「稍后重试」的字样，
         * 反过来判会把「今天已签过」报成失败并白白重试一整天。
         */
        private fun kindOf(httpCode: Int, code: Int, message: String): Kind = when {
            // 设备级去重排在最前：它的文案里同时含「已签到」和「明日再来」，
            // 让它落到 ALREADY 会把「本设备名额被占」误报成「账号已签到」而静默跳过
            isDeviceBlocked(message) -> Kind.DEVICE_BLOCKED
            isAlreadyCheckedIn(message) -> Kind.ALREADY
            isBusy(httpCode, code, message) -> Kind.BUSY
            code in PARAM_CODES -> Kind.PARAM
            isAuthFailed(code, message) -> Kind.AUTH
            else -> Kind.FAIL
        }

        private fun parse(body: String): JSONObject? = try {
            if (body.isBlank()) null else JSONObject(body)
        } catch (e: Exception) {
            null
        }

        /** 成功码：0 是官方口径，200 是社区实现里出现过的变体。 */
        private fun isOkCode(code: Int) = code == 0 || code == 200

        private fun messageOf(json: JSONObject): String =
            json.optString("message").ifBlank { json.optString("msg") }

        /**
         * 用 refreshToken 调 ExchangeToken 换新 accessToken（可能轮换 refreshToken）。
         * 兼容两种响应结构：traework2api 的 Result.Token 和 trae-mate 的 data.access_token。
         * 成功返回 (新 accessToken, 新 refreshToken)，由调用方写回对应账号；失败返回 null
         * （refreshToken 绑定客户端，Trae CN 桌面端的 refreshToken 由桌面客户端自行刷新，
         * 第三方刷新可能不被接受）。
         */
        suspend fun exchangeToken(refreshToken: String): Pair<String, String>? {
            if (refreshToken.isBlank()) return null
            val body = JSONObject().apply {
                put("ClientID", CLIENT_ID)
                put("RefreshToken", refreshToken)
                put("ClientSecret", "-")
                put("UserID", "")
            }
            return try {
                val resp = parse(ApiClient.post(EXCHANGE, body.toString(), "Bearer")) ?: return null
                val result = resp.optJSONObject("Result")
                val data = resp.optJSONObject("data")
                val newToken = result?.optString("Token").orEmpty().ifBlank {
                    data?.optString("access_token").orEmpty().ifBlank {
                        data?.optString("token").orEmpty()
                    }
                }
                if (newToken.isBlank()) return null
                val newRefresh = result?.optString("RefreshToken").orEmpty().ifBlank {
                    data?.optString("refresh_token").orEmpty()
                }
                Pair(newToken, newRefresh)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** 签到状态。 */
    suspend fun status(): TraeStatus {
        val (auth, extra) = auth(accessToken, deviceId)
        val resp = ApiClient.postRaw(STATUS, REQ_SOURCE_BODY, auth, extra)
        val json = parse(resp.body)
            ?: return TraeStatus.Failed(-1, "HTTP ${resp.code}：响应不是合法 JSON", retryable = true)
        val code = json.optInt("code", -1)
        val message = messageOf(json)
        if (isOkCode(code)) return TraeStatus.Ok(json)
        return when (kindOf(resp.code, code, message)) {
            // 限流：文案兜底成官方口径，避免通知里出现一句看不懂的空 message
            Kind.BUSY -> TraeStatus.Failed(code, message.ifBlank { "服务端繁忙" }, retryable = true)

            // 参数不合法：重试也是同一个错误，直接交给用户处理（别白跑整条重试链）
            Kind.PARAM -> TraeStatus.Failed(
                code, message.ifBlank { "请求参数被拒（code=$code）" }, retryable = false,
            )

            Kind.AUTH -> TraeStatus.AuthFailed(code, message)

            // 设备级去重：同一设备同一自然日重试无意义
            Kind.DEVICE_BLOCKED -> TraeStatus.Failed(
                code, message.ifBlank { "本设备今日已签到（code=$code）" }, retryable = false,
            )

            // OK 走不到这里；ALREADY 在状态查询里没有对应语义（权威字段是 checked_in），
            // 一并按普通失败处理，保持与重构前一致的行为
            else -> TraeStatus.Failed(code, message, retryable = true)
        }
    }

    /** 执行签到领取。每轮只调用 1 次（见 [DailySchedule] 的固定重试节奏）。 */
    suspend fun claim(): TraeClaim {
        val (auth, extra) = auth(accessToken, deviceId)
        val resp = ApiClient.postRaw(CLAIM, REQ_SOURCE_BODY, auth, extra)
        val json = parse(resp.body)
            ?: return TraeClaim.Failed(-1, "HTTP ${resp.code}：响应不是合法 JSON", retryable = true)
        val code = json.optInt("code", -1)
        val message = messageOf(json)

        if (isOkCode(code) || json.optBoolean("success")) {
            return TraeClaim.Done(creditsOf(json), message.ifBlank { "签到成功" })
        }

        when (kindOf(resp.code, code, message)) {
            // 设备级去重：本设备今天的名额已被（可能正是本机的另一个账号）用掉。
            // 账号本身没签，但今天在这个设备上签不了，重试没用。
            Kind.DEVICE_BLOCKED -> return TraeClaim.Failed(
                code,
                message.ifBlank { "当前设备今日已经签到，请明日再来" },
                retryable = false,
            )

            // 「已签到」文案是服务端明说的，直接采信
            Kind.ALREADY -> return TraeClaim.Already(message.ifBlank { "今日已签到" })

            Kind.BUSY ->
                return TraeClaim.Busy(code, message.ifBlank { "当前参与用户太多，请稍后重试" })

            Kind.PARAM -> return TraeClaim.Failed(
                code, message.ifBlank { "请求参数被拒（code=$code）" }, retryable = false,
            )

            Kind.AUTH -> return TraeClaim.AuthFailed(code, message)

            else -> Unit
        }

        // 裸 9095 来自参考仓库的口径，本机未实证过——它与当年社区把 1001 当
        // 「已签到」是同一类风险（万一是失败码就会把失败报成成功），所以用
        // status 复核一次 checked_in：复核确认才算已签，否则按普通失败交给重试
        if (code == ALREADY_CODE) {
            val recheck = status()
            return if (recheck is TraeStatus.Ok && recheck.json.optBoolean("checked_in")) {
                TraeClaim.Already(message.ifBlank { "今日已签到" })
            } else {
                TraeClaim.Failed(code, message.ifBlank { "code=$code" }, retryable = true)
            }
        }

        return TraeClaim.Failed(code, message.ifBlank { "code=$code" }, retryable = true)
    }

    /**
     * 积分概要：used/total 来自 ide_user_ent_usage 的 usage_summary。
     * 失败返回 null（不影响签到主流程）。
     */
    suspend fun entUsage(): Pair<Double, Double>? = try {
        val (auth, extra) = auth(accessToken, deviceId)
        val json = parse(ApiClient.post(ENT_USAGE, "{}", auth, extra))
        val summary = json?.optJSONObject("usage_summary")
        if (summary != null) {
            Pair(summary.optDouble("consumed_amount", 0.0), summary.optDouble("total_amount", 0.0))
        } else null
    } catch (e: Exception) {
        null
    }

    /**
     * 从响应里尽量抠出本次到账积分（官方口径是 150 + 50）。
     * 抠不到返回 null——重构前这里默认 `?: 150`，会把「字段没解析出来」报成
     * 「本次 +150 积分」，通知里凭空多一个数字比不显示更糟。
     */
    private fun creditsOf(json: JSONObject): Int? {
        val data = json.optJSONObject("data")
        val base = listOf(json.optInt("credits", -1), data?.optInt("credits", -1) ?: -1)
            .filter { it >= 0 }.maxOrNull()
        val extra = listOf(json.optInt("extra_credits", 0), data?.optInt("extra_credits", 0) ?: 0)
            .maxOrNull() ?: 0
        val points = listOf(json.optInt("points", 0), data?.optInt("points", 0) ?: 0)
            .maxOrNull() ?: 0
        val total = (base ?: 0) + extra
        return when {
            total > 0 -> total
            points > 0 -> points
            else -> null
        }
    }
}
