package com.example.checkin.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.checkin.data.TokenStore
import com.example.checkin.data.WbAccount
import com.example.checkin.network.ApiClient
import com.example.checkin.network.HttpResult
import com.example.checkin.notify.Notifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.random.Random

/**
 * WorkBuddy 定时签到：遍历全部账号。
 *
 * 连续签到天数的口径（1.4.0 借成长中心，当晚即被推翻）：
 * `checkin-activity-status` 的 `streak_days` / `checkin_dates` 都只覆盖**本期赛季**，
 * 换期一起归零（示例：season 9 从 2026-09-16 开跑，两个字段都只有 2）；
 * 赛季起止由同一响应的 `start_time` / `end_time` 给出。
 * 1.2.0 曾用「本地持久化日期 ∪ 服务端日期再回溯」绕过换期清零，但那是**手工模拟**，
 * 会失真：本地只记「App 自己跑成功的那几天」，用户手动签的、换设备期间签的都不知道。
 * 1.4.0 改为问成长中心 `GET /v2/activity/growth/streak` 的 `streak.days`，
 * **这个口径是错的**（2026-09-17 用户更正）：那是**连续登录 PC 端**的天数，不是连续签到——
 * 同一账号本赛季签到 2 天，成长中心却可以是 0（因为它本月只在 PC 登录过 1 天）。
 * 现整段删除：**App 不再查成长中心、也不显示任何「连续」**，只保留签到接口自己的
 * **本期赛季累计**（`streak_days`）。别再把成长中心的 `days` 当签到天数用。
 *
 * Token 续期（1.3.0 新增，参考同作者的 WorkBuddy-Daily 实测推翻「无法刷新」的旧结论）：
 * - 官方插件接口可用 refreshToken 长期续期（见 [WorkBuddyApi]），但**每次续期 RT 会轮换**，
 *   必须写回账号存储，否则续期链断掉仍要重新提取；
 * - 智能续期节奏与参考实现对齐：accessToken 7 天内将过期才主动刷新，其余等鉴权失败再刷
 *   （多处同时用同一 RT 刷新会互相把对方顶失效，所以不无脑每天刷）；
 * - 鉴权失败（HTTP 401/403 或业务码/文案命中）自动续期后重试一次，仍失败才算硬失败。
 * - 正因为每次续期都会轮换 RT，「手动签到」与定时链这两个入口之间用 [WbCheckinGate] 串行，
 *   否则两个流程同时刷会把对方的 RT 顶失效。
 */
class WorkBuddyWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        /** 账号之间的间隔，与 Trae 侧保持一致（3 秒 + 0~2 秒抖动）。 */
        private const val ACCOUNT_GAP_MS = 3_000L
        private const val ACCOUNT_GAP_JITTER_MS = 2_000L

        /** 「今天已签到」的业务码：HTTP 400 + code 10001，幂等，不算失败。 */
        private const val ALREADY_CODE = 10001

        /**
         * 主动续期阈值（天）：accessToken 距过期不足该天数就先刷新。
         * 对齐参考实现 WorkBuddy-Daily 的「智能续期」（AT 7 天内过期才刷新）。
         */
        private const val RENEW_AHEAD_DAYS = 7
    }

    override suspend fun doWork(): Result {
        DailySchedule.scheduleNext(applicationContext, DailySchedule.WORK_WB, WorkBuddyWorker::class.java)
        // 手动签到：每个账号的结果当场通知，失败不进退避（用户点了就是要立刻看到结果）
        val manual = inputData.getBoolean(DailySchedule.KEY_MANUAL, false)
        // 重试链当前轮次（链首任务由排程器写入 1；重试轮由 enqueueRetry 递增）
        val attempt = inputData.getInt(DailySchedule.KEY_ATTEMPT, 1)
        val chainId = inputData.getString(DailySchedule.KEY_CHAIN) ?: cnToday().toString()

        val store = TokenStore(applicationContext)
        val attempts = mutableListOf<Pair<WbAccount, Attempt>>()
        // 整段「读账号 → 签到 → 写回」都放进互斥区（详见 [WbCheckinGate]）：
        // - 手动签到（wb_once）与定时链（workbuddy_<date>）唯一名不同，WorkManager 层面不互斥；
        // - 续期会轮换 RT，两个流程同时刷会互相顶掉；lastResult 写回也是「读全量→改一条→写全量」；
        // - 账号列表必须在锁内重新读：拿锁外读到的旧 Token 发请求只会白吃一次 401。
        // 代价是手动签到最多等定时链跑完（几个账号约 1 分钟），按钮文案已写明「结果稍后通知」。
        val accounts = WbCheckinGate.mutex.withLock {
            val list = store.getWbAccounts().filter { it.token.isNotBlank() && it.domain.isNotBlank() }
            for ((index, acc) in list.withIndex()) {
                if (index > 0) delay(ACCOUNT_GAP_MS + Random.nextLong(ACCOUNT_GAP_JITTER_MS))
                // signIn 可能已续期 Token 并写回 store，必须用返回的「最新账号」写 lastResult，
                // 否则旧 acc 会把刚轮换的新 RT 又覆盖回去
                val (latest, result) = signIn(store, acc)
                store.updateWbAccount(latest.copy(lastResult = result.message))
                attempts.add(latest to result)
            }
            list
        }
        if (accounts.isEmpty()) return Result.failure()

        val retryable = attempts.any { it.second.outcome == CheckinOutcome.RETRYABLE }
        val finalAttempt = !retryable || attempt >= DailySchedule.MAX_RUN_ATTEMPTS

        attempts.forEach { (acc, res) ->
            // 成功一律通知（2026-09-18 用户反馈「自动签到成功没显示通知」后移除静默压制）：
            // 补签一天最多一次（见 alreadyHandled），「今日已签到」类通知不会刷屏，
            // 反而是用户确认「定时任务真的跑了」的唯一信号。
            when {
                res.outcome == CheckinOutcome.OK ->
                    Notifier.notify(applicationContext, "${res.title} · ${acc.name}", res.message)

                // 定时签到：失败只在「不再重试」时报；手动签到：失败也当场报
                res.outcome != CheckinOutcome.OK && (manual || finalAttempt) ->
                    Notifier.notify(applicationContext, "${res.title} · ${acc.name}", res.message)
            }
        }

        return when {
            !retryable && attempts.all { it.second.outcome == CheckinOutcome.OK } -> Result.success()
            // 可重试且未到上限：自排 10 分钟后的下一轮（固定节奏，见 enqueueRetry）
            retryable && !finalAttempt && !manual -> {
                DailySchedule.enqueueRetry(
                    applicationContext, DailySchedule.WORK_WB, WorkBuddyWorker::class.java,
                    chainId, attempt + 1,
                )
                Result.failure()
            }
            else -> Result.failure()
        }
    }

    /**
     * 单账号签到：智能续期 → 查状态 → 未签则领取。
     * 返回「最终账号」+ 本次结果：若中途换新过 Token，返回的是已写回 store 的新账号，
     * 外层写 lastResult 必须以它为准，避免旧 RT 覆盖回去。
     */
    private suspend fun signIn(store: TokenStore, acc: WbAccount): Pair<WbAccount, Attempt> {
        var current = acc

        /** 用当前账号发 billing 接口（current 变更后自动带新 Token）。 */
        suspend fun call(path: String): HttpResult =
            ApiClient.postRaw("${current.domain}$path", "{}", "Bearer ${current.token}")

        return try {
            // 智能续期：accessToken 临近过期先刷新（对齐参考实现的续期节奏）
            renew(store, current, force = false)?.let { current = it }

            var resp = call("/v2/billing/meter/checkin-activity-status")
            // 判定必须带上解析结果：只传 null 就只剩 HTTP 码这一条路径，
            // 「HTTP 200 + 业务码 401」会漏判，于是带着已过期的 Token 又发一次
            // claim（写操作），到 claim 阶段才发现
            var statusJson = WorkBuddyApi.parseJson(resp.body)
            var renewedOnce = false
            if (WorkBuddyApi.isAuthFailure(resp, statusJson)) {
                renewedOnce = true
                val updated = renew(store, current, force = true)
                    ?: return current to Attempt(
                        CheckinOutcome.FATAL, "WorkBuddy 签到失败", renewFatalMessage(current),
                    )
                current = updated
                resp = call("/v2/billing/meter/checkin-activity-status")
                statusJson = WorkBuddyApi.parseJson(resp.body)
            }

            val statusBody = statusJson
                ?: return current to Attempt(
                    CheckinOutcome.RETRYABLE, "WorkBuddy 签到失败", "状态接口响应不是合法 JSON"
                )

            val data = statusBody.optJSONObject("data")
            val statusCode = statusBody.optInt("code")

            if ((statusCode == 0 || statusCode == 200) && data != null) {
                if (data.optBoolean("today_checked_in")) {
                    val credit = data.optInt("today_credit", data.optInt("daily_credit", 0))
                    return current to Attempt(
                        CheckinOutcome.OK,
                        "WorkBuddy 今日已签到",
                        creditText(credit),
                    )
                }
            } else if (statusCode == ALREADY_CODE) {
                return current to Attempt(
                    CheckinOutcome.OK,
                    "WorkBuddy 今日已签到",
                    creditText(0),
                )
            }

            var claimResp = call("/v2/billing/meter/daily-checkin")
            var claimJson = WorkBuddyApi.parseJson(claimResp.body)
                ?: return current to Attempt(
                    CheckinOutcome.RETRYABLE, "WorkBuddy 签到失败", "领取接口响应不是合法 JSON"
                )
            if (WorkBuddyApi.isAuthFailure(claimResp, claimJson)) {
                // 本轮已在 status 阶段刷过仍鉴权失败 → RT 也救不回来了；否则刷一次重试。
                // （claim 阶段重试后不再回头查鉴权，标志位赋值无用武之地，故不置 renewedOnce）
                if (renewedOnce) {
                    return current to Attempt(
                        CheckinOutcome.FATAL, "WorkBuddy 签到失败", renewFatalMessage(current),
                    )
                }
                val updated = renew(store, current, force = true)
                    ?: return current to Attempt(
                        CheckinOutcome.FATAL, "WorkBuddy 签到失败", renewFatalMessage(current),
                    )
                current = updated
                claimResp = call("/v2/billing/meter/daily-checkin")
                claimJson = WorkBuddyApi.parseJson(claimResp.body)
                    ?: return current to Attempt(
                        CheckinOutcome.RETRYABLE, "WorkBuddy 签到失败", "领取接口响应不是合法 JSON"
                    )
            }

            val claimCode = claimJson.optInt("code")
            when {
                // 成功口径 0/200（参考 WorkBuddy-Daily 的社区实测口径）
                claimCode == 0 || claimCode == 200 -> {
                    current to Attempt(
                        CheckinOutcome.OK,
                        "WorkBuddy 签到成功",
                        creditText(creditOf(claimJson)),
                    )
                }

                claimCode == ALREADY_CODE -> {
                    current to Attempt(
                        CheckinOutcome.OK,
                        "WorkBuddy 今日已签到",
                        creditText(creditOf(claimJson)),
                    )
                }

                else -> {
                    val msg = messageOf(claimJson).ifBlank { "code=$claimCode" }
                    // 刷过一轮仍是鉴权失败才算硬失败；其余（限流/活动未开放等）值得重试
                    val fatal = WorkBuddyApi.isAuthFailure(claimResp, claimJson)
                    current to Attempt(
                        if (fatal) CheckinOutcome.FATAL else CheckinOutcome.RETRYABLE,
                        "WorkBuddy 签到失败",
                        msg,
                    )
                }
            }
        } catch (e: Exception) {
            current to Attempt(CheckinOutcome.RETRYABLE, "WorkBuddy 签到失败", "网络异常：${e.message}")
        }
    }

    /**
     * 续期失败的准确文案：没导入 RT（老凭据提取于支持续期之前）和「刷新被拒」
     * 是两回事，前者重新提取导入即可，后者才是真的要重新登录取新链。
     */
    private fun renewFatalMessage(acc: WbAccount): String =
        if (acc.refresh.isBlank()) "未导入 refreshToken（重跑 run.cmd 提取后重新粘贴即可启用自动续期）"
        else "Token 失效，自动刷新被拒，请在电脑上重新运行 run.cmd 提取"

    /**
     * 按需续期并写回 store：force=false 时仅当 accessToken 将在 [RENEW_AHEAD_DAYS] 天内
     * 过期才刷；force=true 表示鉴权已失败，直接刷。刷新失败返回 null（无 RT / 被拒）。
     */
    private suspend fun renew(store: TokenStore, acc: WbAccount, force: Boolean): WbAccount? {
        if (acc.refresh.isBlank()) return null
        if (!force && !WorkBuddyApi.expiresWithinDays(acc.token, RENEW_AHEAD_DAYS)) return null
        val renewed = WorkBuddyApi.refreshToken(acc.refresh) ?: return null
        val updated = acc.copy(token = renewed.first, refresh = renewed.second)
        store.updateWbAccount(updated)
        return updated
    }

    /**
     * 通知文案：只说本次到账积分，**不再拼「连续 N 天」**。
     *
     * 原先这里拼的是成长中心的 `days`，而它其实是**连续登录 PC 端**的天数（见类注释），
     * 拿它当「连续签到」会误导（同日实测：本赛季签到 2 天的账号，成长中心是 0）。
     * 签到侧剩下的 `streak_days` 是**本期赛季累计**口径，不是连续，也不适合放进这句文案；
     * 想看本期累计请到「查询积分」看（那里已标明「本期」）。
     */
    private fun creditText(credit: Int): String =
        if (credit > 0) "今日 +$credit 积分" else "今日已签到"

    /** 领取响应里本次到账积分，兼容顶层与 data 内、以及 credit / today_credit 两种命名。 */
    private fun creditOf(json: JSONObject): Int {
        val data = json.optJSONObject("data")
        return listOf(
            json.optInt("credit", -1), json.optInt("today_credit", -1),
            data?.optInt("credit", -1) ?: -1, data?.optInt("today_credit", -1) ?: -1,
            data?.optInt("daily_credit", -1) ?: -1,
        ).filter { it > 0 }.maxOrNull() ?: 0
    }

    private fun messageOf(json: JSONObject): String =
        json.optString("msg").ifBlank { json.optString("message") }
}
