package com.example.checkin.worker

import java.time.LocalDate
import java.time.ZoneId

/**
 * 活动时区下的「今天」。
 *
 * 固定东八区：签到接口的活动赛季本身就用东八区（`start_time`/`end_time` 形如
 * `2026-09-16 00:00:00`），用手机本地时区判「今天」在半夜或时区设错时会差一天，
 * 排程与「今日已签」判断都会跟着偏。
 *
 * 本文件原名 `CheckinStreak.kt`，曾承载「连续签到天数的逐日回溯计算」。
 * 那套逻辑在 1.4.0 删除（当时改为取成长中心的 `days`），而 1.4.0 当晚上线的
 * 成长中心口径又被推翻——`days` 是**连续登录 PC 端**的天数，不是连续签到，
 * 该调用现也已删除（详见 [WorkBuddyApi] 与 [WorkBuddyWorker] 的类注释）。
 * 连签天数目前 App 里**不再显示**；这里只剩时间基准，故文件名叫 CnTime。
 */

/** 活动时区固定东八区。 */
val CN_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

fun cnToday(): LocalDate = LocalDate.now(CN_ZONE)
