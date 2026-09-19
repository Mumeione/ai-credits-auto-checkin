package com.example.checkin.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 每日签到排程：把签到固定在东八区 **08:00 起、[JITTER_MINUTES] 分钟内随机**执行，
 * 并保证「条件不满足会补签」。
 *
 * 为什么不用 `PeriodicWorkRequest`：
 * 周期任务的弹性窗口固定在**周期末尾**（窗口 = `[T-flex, T]`），而第一次执行的 T 又被
 * `initialDelay + period` 决定，导致「窗口落在 8 点档」和「今天就要跑一次」无法同时成立——
 * 想对齐窗口末尾就必须把首次执行推迟一整天，等于天天漏一天。
 * 所以改成**一次性任务自链**：每次跑完再排下一天，`initialDelay` 精确对齐窗口，
 * 并且用一个「当天日期」做唯一任务名，重复排程天然幂等（`KEEP`）。
 *
 * 补签由三层兜住，但**一天只走一次轮询**（跑过就不再补，详见 [alreadyHandled]）：
 * 1. 约束不满足时 WorkManager 会把任务一直挂在队列里，网络/电量恢复后立刻执行（可能已是下午）；
 * 2. 服务端限流（9074）时 Worker 失败后自排 [RETRY_DELAY_MINUTES] 分钟后的下一轮，
 *    总共最多 [MAX_RUN_ATTEMPTS] 次尝试即收手——限流是服务端容量问题，长时间反复
 *    请求既签不上、又徒增风控暴露面，超过次数改由通知提醒用户手动签到；
 * 3. 被国产 ROM 清理掉后台任务、导致今天**完全没有任务记录**时，下次打开 App 补跑一次。
 *    这只在「今天确实一次都没跑过」时发生——失败后不再靠反复补签撞运气，
 *    因为每开一次 App 就补一整条链，反过来会把「收紧请求量」的意图抵消掉。
 */
object DailySchedule {

    /** 窗口起点（东八区小时）：08:00。避开 9 点之后的早高峰。 */
    const val WINDOW_START_HOUR = 8

    /**
     * 窗口内随机错峰的幅度（分钟）——**它同时决定窗口的上界**：
     * 落点是 `[WINDOW_START_HOUR:00, +JITTER_MINUTES]` 内的随机时刻，即 **08:00-08:50**。
     *
     * 这里刻意不再设「窗口长度」这类常量：曾经有过一个 `WINDOW_MINUTES = 60`
     * （声称窗口 08:00-09:00），但没有任何代码引用它，改它也不会有任何效果——
     * 与真实行为对不上的死常量只会误导后来人，已删除。
     *
     * 为什么是 50 而不是 60：留 10 分钟余地，别把落点贴回 9 点之后的早高峰。
     * 服务端高峰期会返回 9074「参与用户太多」，窗口从 9-10 点提前到 8 点就是为了避开它。
     */
    private const val JITTER_MINUTES = 50

    /** 任务种类，用于拼唯一任务名。 */
    const val WORK_TRAE = "trae"
    const val WORK_WB = "workbuddy"

    /** 手动签到标记：App 内按钮触发，每个账号的结果当场通知，失败不进入退避重试。 */
    const val KEY_MANUAL = "manual"

    /** 重试链已尝试的轮次（1 起算），见 [enqueueRetry]。 */
    const val KEY_ATTEMPT = "attempt"

    /** 重试链标识 = 链首任务的目标日期；同一天的多轮重试共用它（见 [retryName]）。 */
    const val KEY_CHAIN = "chain"

    /**
     * 失败重试间隔：固定 10 分钟。
     *
     * 不能用 WorkManager 自带的 `BackoffPolicy.LINEAR`——它的语义是「间隔 × 轮次」
     * （30min → 60min → 90min…），会越排越疏，第一轮就已经不是我们想要的节奏。
     * 所以改为 Worker 失败后**自排下一轮**（[enqueueRetry]），拿到严格的固定节奏：
     * 08:00-08:50 首轮，失败后每 10 分钟一轮，最多 [MAX_RUN_ATTEMPTS] 次即收手，
     * 避免在高峰期反复请求（9074 是服务端容量问题，硬刚没有意义）。
     */
    const val RETRY_DELAY_MINUTES = 10L

    /**
     * 重试链最多尝试几轮（含首轮）。
     *
     * 只做 3 次：08:00-08:50 首轮 + 两次 10 分钟重试，约 20 分钟内跑完。
     * 9074 属于服务端整体容量闸门，短时间反复请求既签不上、又徒增风控暴露面，
     * 所以到次数就停，由通知提醒用户手动到客户端签到。
     */
    const val MAX_RUN_ATTEMPTS = 3

    fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** 某天窗口内的随机落点。 */
    private fun slotOf(date: LocalDate): ZonedDateTime =
        date.atTime(WINDOW_START_HOUR, 0).atZone(CN_ZONE)
            .plusMinutes(Random.nextLong(0L, JITTER_MINUTES.toLong() + 1))

    /**
     * 下一次执行时刻：今天窗口的落点还没过就用今天，否则顺延到明天。
     * （留 1 分钟余量，避免「刚算完就过期」导致任务立刻执行、把窗口挤到当前时刻。）
     */
    fun nextTarget(now: ZonedDateTime = ZonedDateTime.now(CN_ZONE)): ZonedDateTime {
        val todaySlot = slotOf(now.toLocalDate())
        return if (todaySlot.isAfter(now.plusMinutes(1))) todaySlot
        else slotOf(now.toLocalDate().plusDays(1))
    }

    /**
     * App 启动时调用：确保「下一次定时」在队列里；若当天落点已过而今天**一次都没跑过**，
     * 补一次兜底签到（判定见 [alreadyHandled]）。
     * 用当天日期做唯一任务名 + `KEEP`，所以反复调用不会堆任务。
     */
    fun ensureScheduled(context: Context, kind: String, worker: Class<out ListenableWorker>) {
        val wm = WorkManager.getInstance(context)
        val now = ZonedDateTime.now(CN_ZONE)
        val target = nextTarget(now)
        enqueue(
            wm,
            uniqueName(kind, target.toLocalDate()),
            worker,
            Duration.between(now, target).toMillis(),
            chainId = target.toLocalDate().toString(),
        )

        // 兜底补签：只有「今天的落点已经过去」才考虑（窗口内交给上面的定时任务，
        // 否则会和它撞车、同一天多弹一条「今日已签到」）。
        // 是否还要补由 [alreadyHandled] 决定——**今天跑过（哪怕失败）就不再补**，
        // 所以这里一天最多补一次；失败后由 Worker 的通知去提示手动签到 / 报错误码。
        // 另外 KEEP 只对未完成的同名任务去重（任务一旦结束，同名唯一名可以再次入队），
        // 靠 KEEP 本身挡不住「每次开 App 都重跑」，所以这层判断是必要的。
        val today = now.toLocalDate()
        val todayName = uniqueName(kind, today)
        if (target.toLocalDate() > today && !alreadyHandled(wm, kind, today)) {
            enqueue(
                wm,
                "${todayName}_catchup",
                worker,
                0L,
                chainId = today.toString(),
            )
        }
    }

    /**
     * 今天的签到是否**已经跑过**（或正挂着）。
     *
     * 判定是「只要相关任务**存在过**就算数」，**不看它成功还是失败**：
     * 失败也算数，因为失败后不再靠反复补签去撞运气——补一次就是一整条重试链
     * （最多 [MAX_RUN_ATTEMPTS] 次请求），而每打开一次 App 都补一轮，
     * 会把「收紧请求量、别硬刚服务端限流」的意图整个抵消掉。
     * 失败后由 Worker 的通知负责告知用户：Trae 提示「请手动到客户端签到」，
     * WorkBuddy 直接报服务端给的原因/错误码。
     *
     * 于是补签只在**今天完全没有任务记录**时发生（典型场景：后台任务被 ROM 清掉、
     * 昨天没能把今天的任务排上，今天首次打开 App）。它是「三层兜底」的第三层，
     * 保留但一天只生效一次。
     */
    private fun alreadyHandled(wm: WorkManager, kind: String, today: LocalDate): Boolean {
        val todayName = uniqueName(kind, today)
        val chain = today.toString()
        // 重试轮的唯一名带轮次（见 [enqueueRetry]），所以按轮次逐个探——名字统一由
        // [retryName] 生成，避免这里再拼一遍字面量、改一处漏一处。
        // 列表里的 `${kind}_${chain}_retry`（不带轮次）是升级前的旧命名：重试链从首轮
        // 到收手不到 20 分钟，它只用来兜住「刚升级时正挂着一条旧链」，下个版本可以删。
        val names = mutableListOf(todayName, "${todayName}_catchup", "${kind}_${chain}_retry")
        for (attempt in 2..MAX_RUN_ATTEMPTS) {
            names.add(retryName(kind, chain, attempt))
        }
        return names.any { hasAnyRecord(wm, it) }
    }

    /** 该唯一名名下**存在过**任何一次执行记录（未完成 / 成功 / 失败 / 取消都算）。 */
    private fun hasAnyRecord(wm: WorkManager, name: String): Boolean = runCatching {
        wm.getWorkInfosForUniqueWork(name).get().isNotEmpty()
    }.getOrDefault(false)

    /**
     * Worker 跑起来后调用：把下一天排上。
     * 在 doWork 开头就排，这样即使本次超时/崩溃，明天的任务也已经落地。
     *
     * 必须**固定排明天**而不是 [nextTarget]：后者会在「运行时」重新随机一个今日落点，
     * 若落在当前时刻之后，唯一任务名会与**正在运行的本任务**相同，`KEEP` 命中运行中的任务
     * 直接被忽略——本次跑完后明天的任务就没排上，链条就此断掉（只能靠下次打开 App 兜底）。
     * 固定排明天则名字必然与当前任务不同，且与 [ensureScheduled] 算出的明天的名字一致，
     * 重复排程天然幂等（`KEEP`）。
     */
    fun scheduleNext(context: Context, kind: String, worker: Class<out ListenableWorker>) {
        val now = ZonedDateTime.now(CN_ZONE)
        val target = slotOf(now.toLocalDate().plusDays(1))
        enqueue(
            WorkManager.getInstance(context),
            uniqueName(kind, target.toLocalDate()),
            worker,
            Duration.between(now, target).toMillis(),
            chainId = target.toLocalDate().toString(),
        )
    }

    /**
     * 失败后的下一轮重试：延迟固定 [RETRY_DELAY_MINUTES] 分钟再跑一次。
     * 由 Worker 在「可重试失败且还没到 [MAX_RUN_ATTEMPTS] 上限」时调用，
     * 替代 `Result.retry()`（自带退避是「间隔 × 轮次」，节奏会越排越疏，见
     * [RETRY_DELAY_MINUTES] 注释）。
     *
     * 唯一名带轮次（[retryName]）所以天然唯一，用 `KEEP` 就够：同一轮不会重复入队，
     * 也**不会取消任何正在运行的任务**。此前固定名 + `REPLACE` 的写法命中的正是
     * **当前这个 RUNNING 的 work 自己**（因为链名没变），只是恰好写在 doWork 最后一步
     * 才没出事——新任务虽然在取消之后才插入、不至于丢链，但没人应该依赖这种时机巧合。
     */
    fun enqueueRetry(
        context: Context,
        kind: String,
        worker: Class<out ListenableWorker>,
        chainId: String,
        nextAttempt: Int,
    ) {
        val request = OneTimeWorkRequest.Builder(worker)
            .setConstraints(constraints())
            .setInitialDelay(RETRY_DELAY_MINUTES, TimeUnit.MINUTES)
            .setInputData(
                Data.Builder()
                    .putString(KEY_CHAIN, chainId)
                    .putInt(KEY_ATTEMPT, nextAttempt)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(retryName(kind, chainId, nextAttempt), ExistingWorkPolicy.KEEP, request)
    }

    private fun uniqueName(kind: String, date: LocalDate) = "${kind}_$date"

    /**
     * 重试轮的唯一任务名：`{kind}_{链首日期}_retry_{轮次}`（轮次 = 本次是整条链的第几轮，1 起算）。
     *
     * **必须带轮次**：名字带轮次后天然唯一，`KEEP` 即可，也就不存在「自排下一轮时
     * 把正在运行的自己取消掉」的隐患（见 [enqueueRetry]）。
     * 改这个名字时要同步改 [alreadyHandled] 的探测列表。
     */
    private fun retryName(kind: String, chainId: String, attempt: Int) =
        "${kind}_${chainId}_retry_$attempt"

    private fun enqueue(
        wm: WorkManager,
        name: String,
        worker: Class<out ListenableWorker>,
        delayMillis: Long,
        chainId: String,
    ) {
        val request = OneTimeWorkRequest.Builder(worker)
            .setConstraints(constraints())
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putString(KEY_CHAIN, chainId)
                    .putInt(KEY_ATTEMPT, 1)
                    .build()
            )
            .build()
        wm.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * 取消 1.2.0 之前注册的周期任务。
     * 老版本用的是 `trae_checkin` / `workbuddy_checkin` 两个周期任务，
     * 换成本次的一次性任务链后必须显式取消，否则两套排程会同时跑、一天签两次。
     */
    fun cancelLegacyPeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork("trae_checkin")
        wm.cancelUniqueWork("workbuddy_checkin")
    }
}
