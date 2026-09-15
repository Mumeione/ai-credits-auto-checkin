package com.example.checkin.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.checkin.data.TokenStore
import com.example.checkin.notify.Notifier
import org.json.JSONObject

class TraeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = TokenStore(applicationContext)
        if (store.getTraeAccess().isBlank()) return Result.failure()
        val api = TraeApi(store)

        val status = api.status() ?: run {
            // 认证失效，尝试自动刷新
            if (api.refreshAccess()) {
                api.status() ?: run {
                    Notifier.notify(applicationContext, "Trae 签到失败", "Token 刷新后仍无效，请重新提取")
                    return Result.failure()
                }
            } else {
                Notifier.notify(applicationContext, "Trae Token 已失效", "自动刷新被拒绝，请在电脑上重新运行 extract_tokens.py 提取")
                return Result.failure()
            }
        }

        // 积分概要（used/total），失败不影响主流程
        val usage = api.entUsage()
        val usageText = usage?.let { "，已用 ${it.first} / ${it.second}" } ?: ""

        if (status.optBoolean("checked_in")) {
            val credits = status.optInt("credits", 0)
            val extra = status.optInt("extra_credits", 0)
            Notifier.notify(
                applicationContext, "Trae 今日已签到",
                "今日 +${credits + extra} 积分$usageText"
            )
            return Result.success()
        }

        return try {
            val claim = api.claim()
            if (claim.optInt("code") == 0) {
                val credits = claim.optInt("credits", 150)
                val extra = claim.optInt("extra_credits", 50)
                Notifier.notify(
                    applicationContext, "Trae 签到成功",
                    "+${credits + extra} 积分$usageText"
                )
                Result.success()
            } else {
                Notifier.notify(applicationContext, "Trae 签到失败", claim.optString("message").ifBlank { "code=${claim.optInt("code")}" })
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
