package com.example.checkin.worker

/**
 * 单个账号的一次签到结果（两个平台的 Worker 共用）。
 *
 * 原先 [TraeWorker] 与 [WorkBuddyWorker] 各自在 `companion object` 里私有了一份字段完全
 * 相同的 data class——两处字段一旦漂移（比如一边加了标记、另一边没加），
 * 通知与收尾逻辑就会各按各的理解走。这里是收敛后的唯一一份。
 *
 * @param outcome 归类结果，决定外层 WorkManager 是 success 还是 failure
 * @param title 通知标题（不含账号名，账号名由调用方拼上）
 * @param message 通知正文，同时会写回账号卡片的「最近一次结果」
 */
internal data class Attempt(
    val outcome: CheckinOutcome,
    val title: String,
    val message: String,
)
