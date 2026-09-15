package com.example.checkin.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.checkin.data.TokenStore
import com.example.checkin.network.ApiClient
import com.example.checkin.notify.Notifier
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
        val (token, domain) = store.getWorkBuddyToken()

        if (token.isBlank() || domain.isBlank()) return Result.failure()

        return try {
            val statusBody = ApiClient.post(
                "$domain/v2/billing/meter/checkin-activity-status", "{}", "Bearer $token"
            )
            val statusJson = JSONObject(statusBody)

            if (statusJson.optInt("code") == 0) {
                val data = statusJson.optJSONObject("data")
                if (data != null && data.optBoolean("today_checked_in")) {
                    Notifier.notify(
                        applicationContext, "WorkBuddy 今日已签到",
                        "连续 ${consecutiveCheckinDays(data)} 天，今日 +${data.optInt("today_credit", 0)} 积分"
                    )
                    return Result.success()
                }
            }

            val claimBody = ApiClient.post(
                "$domain/v2/billing/meter/daily-checkin", "{}", "Bearer $token"
            )
            val claimJson = JSONObject(claimBody)

            when (claimJson.optInt("code")) {
                0 -> {
                    val data = claimJson.optJSONObject("data")
                    val credit = data?.optInt("today_credit") ?: data?.optInt("credit", 100) ?: 100
                    Notifier.notify(applicationContext, "WorkBuddy 签到成功", "+$credit 积分")
                    Result.success()
                }
                10001 -> {
                    Notifier.notify(applicationContext, "WorkBuddy", "今日已签到")
                    Result.success()
                }
                else -> {
                    Notifier.notify(
                        applicationContext, "WorkBuddy 签到失败",
                        claimJson.optString("msg").ifBlank { "code=${claimJson.optInt("code")}" }
                    )
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
