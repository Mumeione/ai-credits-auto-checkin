package com.example.checkin.worker

import org.json.JSONObject

/**
 * WorkBuddy 服务端字段的安全读取。
 *
 * 为什么不用 `optInt(key, 0)` / `optBoolean(key)` 这类带默认值的读法：
 * 「字段不存在」和「字段值是 0 / false」是两件完全不同的事。用默认值兜底，
 * 会把「服务端没返回这个字段」显示成「累计 0 积分」「今天不是连签奖励日」——
 * 也就是把未知当成已知。这些接口是社区逆向出来的，字段随时可能被去掉或改名，
 * 所以这里一律返回可空类型，由调用方自己决定「不显示」还是「显示」。
 */

/** 字段存在且非 null、且能解析为整数时返回；缺失 / null / 非数字都返回 null。 */
fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) {
        optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
    } else {
        null
    }

/** 字段存在且非 null 时返回布尔值，否则 null（`optBoolean` 默认 false，区分不出缺失）。 */
fun JSONObject.boolOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null
