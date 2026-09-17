package com.example.checkin.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.checkin.data.TokenStore
import com.example.checkin.data.TraeAccount
import com.example.checkin.notify.Notifier
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Trae 定时签到：遍历全部账号。
 *
 * 关于「当前参与用户太多，请稍后重试」（错误码 9074）：
 * - 2026-09-16 实测它是**持续状态**而非瞬时抖动——15 分钟内连打 9+ 次全是 9074，
 *   运行内短重试基本是白等，所以每轮只发 1 次 claim，失败交给
 *   [DailySchedule.enqueueRetry] 排下一轮。
 * - 参考实现（L0NE-6/Trae-AutoCheckin）声称「反复 9074 = 设备号被服务端记住，
 *   换新号立刻能签成」，但本项目**两次独立实测证伪**：9/16 上午乱造设备号仍 9074，
 *   下午 3 个从未出现过的全新 16 位设备号也全部 9074。换头、换 body、换设备号都
 *   绕不开，9074 就是服务端按「当前参与用户太多」做的容量闸门——已删除设备号
 *   轮换逻辑（频繁换设备号本身还是风控高危信号，见 [TraeApi] 类注释）。
 * - **2026-09-17 起收紧重试策略**：首轮落在 08:00-08:50（避开早高峰），失败后每
 *   10 分钟一轮、最多 [DailySchedule.MAX_RUN_ATTEMPTS] 次即收手。9074 是服务端
 *   容量问题，长时间反复请求既签不上、又徒增风控暴露面，所以超过次数就把
 *   「请手动签到」交给用户，不再无限续命。
 * 失败通知只在「不再重试」的那一轮才发，避免重试期间把通知刷爆。
 */
class TraeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        /**
         * 账号之间的间隔。
         * 参考实现 trae-check 用的是固定 2 秒，本项目取 3 秒且叠加 0~2 秒抖动：
         * 3 秒比参考值更保守，抖动则避免多台设备/多次运行落在同一节奏上。
         * 需要说明的是，限流来自服务端整体负载，加大间隔并不能消除 9074，
         * 真正起作用的是错峰（见 [DailySchedule] 的窗口随机落点）与重试。
         */
        private const val ACCOUNT_GAP_MS = 3_000L
        private const val ACCOUNT_GAP_JITTER_MS = 2_000L
    }

    override suspend fun doWork(): Result {
        // 先把明天的排程落地，即使本次超时/崩溃也不会断链
        DailySchedule.scheduleNext(applicationContext, DailySchedule.WORK_TRAE, TraeWorker::class.java)
        val quiet = inputData.getBoolean(DailySchedule.KEY_QUIET, false)
        // 手动签到：每个账号的结果当场通知，失败不进退避（用户点了就是要立刻看到结果）
        val manual = inputData.getBoolean(DailySchedule.KEY_MANUAL, false)
        // 重试链当前轮次（链首任务由排程器写入 1；重试轮由 enqueueRetry 递增）
        val attempt = inputData.getInt(DailySchedule.KEY_ATTEMPT, 1)
        val chainId = inputData.getString(DailySchedule.KEY_CHAIN)
            ?: cnToday().toString()

        val store = TokenStore(applicationContext)
        val accounts = store.getTraeAccounts().filter { it.access.isNotBlank() }
        if (accounts.isEmpty()) return Result.failure()

        val attempts = mutableListOf<Pair<TraeAccount, Attempt>>()
        for ((index, acc) in accounts.withIndex()) {
            // 多账号间隔请求，避免连发触发风控
            if (index > 0) delay(ACCOUNT_GAP_MS + Random.nextLong(ACCOUNT_GAP_JITTER_MS))
            // signIn 可能已刷新 Token 并写入 store，必须用返回的「最新账号」写 lastResult，
            // 否则旧 acc 会把刚换新的 Token 又覆盖回去
            val (latest, result) = signIn(store, acc, manual, attempt)
            store.updateTraeAccount(latest.copy(lastResult = result.message))
            attempts.add(latest to result)
        }

        val retryable = attempts.any { it.second.outcome == CheckinOutcome.RETRYABLE }
        // attempt 即本次是第几轮（1 起算）
        val finalAttempt = !retryable || attempt >= DailySchedule.MAX_RUN_ATTEMPTS

        attempts.forEach { (acc, res) ->
            when {
                res.outcome == CheckinOutcome.OK && !(quiet && res.alreadyDone) ->
                    Notifier.notify(applicationContext, "${res.title} · ${acc.name}", res.message)

                // 定时签到：失败只在「不再重试」时报，避免重试期间反复打扰；
                // 手动签到：失败也当场报，用户等着结果呢
                res.outcome != CheckinOutcome.OK && (manual || finalAttempt) ->
                    Notifier.notify(applicationContext, "${res.title} · ${acc.name}", res.message)
            }
        }

        return when {
            !retryable && attempts.all { it.second.outcome == CheckinOutcome.OK } -> Result.success()
            // 可重试且未到上限：自排 10 分钟后的下一轮（固定节奏，见 enqueueRetry）；
            // 不用 Result.retry()——WorkManager 自带退避是「间隔 × 轮次」，会越排越疏
            retryable && !finalAttempt && !manual -> {
                DailySchedule.enqueueRetry(
                    applicationContext, DailySchedule.WORK_TRAE, TraeWorker::class.java,
                    chainId, attempt + 1, quiet,
                )
                Result.failure()
            }
            else -> Result.failure()
        }
    }

    /**
     * 单账号签到：状态查询 → （必要时刷新 Token）→ 未签则领取。
     * [attempt] 是当前重试链轮次（1 起算），只用来决定限流文案是「稍后自动重试」
     * 还是「已到次数上限，请手动签到」。
     * 返回「最终账号」+ 本次结果：若中途换新过 Token，返回的是已写回 store 的新账号，
     * 外层写 lastResult 时必须以它为准，避免旧 Token 覆盖回去。
     */
    private suspend fun signIn(
        store: TokenStore,
        acc: TraeAccount,
        manual: Boolean,
        attempt: Int,
    ): Pair<TraeAccount, Attempt> {
        var current = acc
        var api = TraeApi(acc.access, acc.device)
        var status = api.status()

        // 鉴权失效才去刷新 Token；限流等业务失败不去刷新，避免误判成「Token 过期」
        if (status is TraeStatus.AuthFailed) {
            val renewed = TraeApi.exchangeToken(acc.refresh)
            if (renewed != null) {
                val updated = acc.copy(access = renewed.first, refresh = renewed.second)
                store.updateTraeAccount(updated)
                current = updated
                api = TraeApi(updated.access, updated.device)
                status = api.status()
            }
            if (status is TraeStatus.AuthFailed) {
                return current to Attempt(
                    CheckinOutcome.FATAL,
                    "Trae 签到失败",
                    if (acc.refresh.isBlank()) "未导入 refreshToken，无法自动刷新，请重新提取"
                    else "Token 失效，自动刷新被拒绝，请在电脑上重新运行 run.cmd 提取",
                )
            }
        }
        // 限流等值得错峰重试；参数类错误（如缺设备号返回的 9004）重试只会拿到同一个
        // 结果，归为 FATAL 直接交给用户处理，别在重试链上白跑
        if (status is TraeStatus.Failed) {
            val base = status.message.ifBlank { "code=${status.code}" }
            return current to Attempt(
                if (status.retryable) CheckinOutcome.RETRYABLE else CheckinOutcome.FATAL,
                "Trae 签到失败",
                if (status.retryable) retryableText(base, attempt, manual)
                else fatalText(base, status.code),
            )
        }

        val json = (status as TraeStatus.Ok).json
        // 积分概要（used/total），失败不影响主流程
        val usage = api.entUsage()
        val usageText = usage?.let { "，已用 ${trim(it.first)} / ${trim(it.second)}" } ?: ""

        if (json.optBoolean("checked_in")) {
            val credits = json.optInt("credits", 0) + json.optInt("extra_credits", 0)
            return current to Attempt(
                CheckinOutcome.OK,
                "Trae 今日已签到",
                "今日 +$credits 积分$usageText",
                alreadyDone = true,
            )
        }

        // 服务端明确未开放签到（enable=false）时，继续 claim 只会白烧限流额度；
        // 但「今天没签成」必须让用户知道，不标 alreadyDone，照常通知
        if (!json.optBoolean("enable", true)) {
            return current to Attempt(
                CheckinOutcome.OK, "Trae 签到", "该账号未开放签到（enable=false），今日跳过",
            )
        }

        // 每轮只发 1 次 claim：9074 是按时间片的容量闸门，当场连发只会延长惩罚窗口
        val claim = api.claim()

        return when (claim) {
            is TraeClaim.Done -> current to Attempt(
                CheckinOutcome.OK,
                "Trae 签到成功",
                // 抠不出积分数时只报成功文案，不凭空编一个数字
                (claim.credits?.let { "+$it 积分" } ?: claim.message) + usageText,
            )

            is TraeClaim.Already -> current to Attempt(
                CheckinOutcome.OK, "Trae 今日已签到", claim.message, alreadyDone = true
            )

            is TraeClaim.Busy -> current to Attempt(
                CheckinOutcome.RETRYABLE,
                "Trae 签到失败",
                retryableText(claim.message, attempt, manual),
            )

            is TraeClaim.AuthFailed -> current to Attempt(
                CheckinOutcome.FATAL,
                "Trae 签到失败",
                "Token 失效，请在电脑上重新运行 run.cmd 提取",
            )

            is TraeClaim.Failed -> current to Attempt(
                if (claim.retryable) CheckinOutcome.RETRYABLE else CheckinOutcome.FATAL,
                "Trae 签到失败",
                if (claim.retryable) retryableText(claim.message, attempt, manual)
                else fatalText(claim.message, claim.code),
            )
        }
    }

    /**
     * 可重试失败的通知文案。
     *
     * 定时签到只在「不再重试」的那一轮才发通知，所以自动流程实际只会走到
     * 「已到次数上限」那一支；中间轮次的文案是给手动签到留的
     * （用户点了按钮，就该知道接下来会自动重试还是得自己动手）。
     */
    private fun retryableText(base: String, attempt: Int, manual: Boolean): String = when {
        manual -> "$base（当前被服务端限流，可稍后在 App 内重试，或到 Trae 客户端手动签到）"

        attempt >= DailySchedule.MAX_RUN_ATTEMPTS ->
            "$base（已自动尝试 ${DailySchedule.MAX_RUN_ATTEMPTS} 次仍未成功，" +
                "请手动到 Trae 客户端签到）"

        else -> "$base（第 $attempt/${DailySchedule.MAX_RUN_ATTEMPTS} 次尝试，" +
            "${DailySchedule.RETRY_DELAY_MINUTES} 分钟后自动重试）"
    }

    /**
     * 「重试无用」类失败的提示文案：按错误码给出可执行的下一步。
     * - `9004`：请求参数被拒（实测为缺 `x-device-id`），设备号只能靠重新提取拿到
     * - `9095`：设备级去重——本设备今天的签到名额已被用掉
     *   （同机多账号共用同一个设备号时，每天只有第一个账号能签成）
     */
    private fun fatalText(message: String, code: Int): String = when (code) {
        9004 -> "请求被拒（${message.ifBlank { "code=$code" }}），" +
            "通常是设备号缺失——请在电脑上重跑 run.cmd 重新提取后导入该账号"

        9095 -> "本设备今日的签到名额已被用掉（一设备一天一次），该账号今天签不了——" +
            "请明日再试，或换一台设备重新提取凭据"

        else -> message.ifBlank { "code=$code" }
    }

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
