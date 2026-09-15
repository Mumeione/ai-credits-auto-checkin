package com.example.checkin.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.checkin.data.WbAccount
import com.example.checkin.data.TokenStore
import com.example.checkin.network.ApiClient
import com.example.checkin.notify.Notifier
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 从 checkin-activity-status 返回的 checkin_dates 计算真实连续签到天数。
 * 服务端的 streak_days 是本期活动（如"开学季"）累计签到天数，中间断签不清零，
 * 不能当作"连续天数"使用，因此由本地根据签到日期列表回溯计算。
 */
fun consecutiveCheckinDays(data: JSONObject): Int {
    val dates: JSONArray = data.optJSONArray("checkin_dates") ?: return 0
    val checked = HashSet<String>()
    for (i in 0 until dates.length()) checked.add(dates.optString(i))
    var day = LocalDate.now()
    // 今天还没签时，从昨天开始回溯
    if (day.toString() !in checked) day = day.minusDays(1)
    var streak = 0
    while (day.toString() in checked) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}

class WorkBuddyWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = TokenStore(applicationContext)
        val accounts = store.getWbAccounts()
        if (accounts.isEmpty()) return Result.failure()

        var allOk = true
        for ((index, acc) in accounts.withIndex()) {
            if (acc.token.isBlank() || acc.domain.isBlank()) continue
            // 多账号间隔请求，避免连发触发风控
            if (index > 0) delay(3000)
            if (!checkinOne(store, acc)) allOk = false
        }
        return if (allOk) Result.success() else Result.retry()
    }

    /** 单账号签到，失败返回 false（外层整体 Result.retry）。*/
    private suspend fun checkinOne(store: TokenStore, acc: WbAccount): Boolean {
        val ctx = applicationContext
        return try {
            val statusBody = ApiClient.post(
                "${acc.domain}/v2/billing/meter/checkin-activity-status", "{}", "Bearer ${acc.token}"
            )
            val statusJson = JSONObject(statusBody)

            if (statusJson.optInt("code") == 0) {
                val data = statusJson.optJSONObject("data")
                if (data != null && data.optBoolean("today_checked_in")) {
                    val msg = "连续 ${consecutiveCheckinDays(data)} 天，今日 +${data.optInt("today_credit", 0)} 积分"
                    store.updateWbAccount(acc.copy(lastResult = msg))
                    Notifier.notify(ctx, "WorkBuddy 今日已签到 · ${acc.name}", msg)
                    return true
                }
            }

            val claimBody = ApiClient.post(
                "${acc.domain}/v2/billing/meter/daily-checkin", "{}", "Bearer ${acc.token}"
            )
            val claimJson = JSONObject(claimBody)

            when (claimJson.optInt("code")) {
                0 -> {
                    val data = claimJson.optJSONObject("data")
                    val credit = data?.optInt("today_credit") ?: data?.optInt("credit", 100) ?: 100
                    val msg = "+$credit 积分"
                    store.updateWbAccount(acc.copy(lastResult = msg))
                    Notifier.notify(ctx, "WorkBuddy 签到成功 · ${acc.name}", msg)
                    true
                }
                10001 -> {
                    val msg = "今日已签到"
                    store.updateWbAccount(acc.copy(lastResult = msg))
                    Notifier.notify(ctx, "WorkBuddy · ${acc.name}", msg)
                    true
                }
                else -> {
                    val msg = claimJson.optString("msg").ifBlank { "code=${claimJson.optInt("code")}" }
                    store.updateWbAccount(acc.copy(lastResult = msg))
                    Notifier.notify(ctx, "WorkBuddy 签到失败 · ${acc.name}", msg)
                    false
                }
            }
        } catch (e: Exception) {
            store.updateWbAccount(acc.copy(lastResult = "失败: ${e.message}"))
            false
        }
    }
}
