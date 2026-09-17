package com.example.checkin.worker

import kotlinx.coroutines.sync.Mutex

/**
 * WorkBuddy 签到的**进程内互斥闸门**。
 *
 * 「手动签到」按钮（唯一名 `wb_once`）与定时链（`workbuddy_<日期>`）用的是**不同**的唯一任务名，
 * WorkManager 层面不互斥，两者可以真正并发。Trae 侧并发无害（`ExchangeToken` 幂等，
 * 重复刷新返回同一会话、旧 RT 不作废），**WorkBuddy 侧有害**：
 * - 续期会**轮换 refreshToken**：两个流程同时拿同一个 RT 去刷，会把对方刚拿到的那条顶失效，
 *   最坏的结果是要在电脑上重新提取凭据；
 * - `lastResult` 的写回是「读全量 → 改一条 → 写全量」，并发会丢更新。
 *
 * 概率不高（得恰好在签到窗口或重试链运行的那几分钟里点手动签到），但代价不划算，
 * 所以让两个入口共用这一把锁串行执行。
 *
 * **为什么必须是这个独立的 object**，而不是各 Worker 的 `companion object` 里的锁：
 * companion 属于各自的类，两个类拿到的是**两把不同的锁**，等于没锁。
 *
 * 前提：WorkManager 默认在应用主进程里跑 Worker（本项目 `AndroidManifest.xml` 没有
 * `android:process`），所以进程内互斥足够。若将来把 Worker 挪进 `:worker` 之类的独立进程，
 * 这把锁就失效了——那时得换跨进程手段（文件锁 / DataStore）。
 */
internal object WbCheckinGate {
    val mutex = Mutex()
}
