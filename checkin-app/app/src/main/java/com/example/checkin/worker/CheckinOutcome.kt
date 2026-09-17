package com.example.checkin.worker

/**
 * 单个账号一次签到尝试的归类，决定外层 WorkManager 怎么收尾：
 * - [OK]        已签好（新签成功 / 今天本来就已签）
 * - [RETRYABLE] 暂时性失败（服务端限流、网络抖动），值得退避重试
 * - [FATAL]     重试也没用（Token 失效、活动已结束等），交给用户处理
 */
enum class CheckinOutcome { OK, RETRYABLE, FATAL }
