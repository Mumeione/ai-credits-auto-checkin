package com.example.checkin.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.checkin.data.TraeAccount
import com.example.checkin.data.TokenStore
import com.example.checkin.notify.Notifier
import kotlinx.coroutines.delay
import org.json.JSONObject

class TraeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = TokenStore(applicationContext)
        val accounts = store.getTraeAccounts()
        if (accounts.isEmpty()) return Result.failure()

        var allOk = true
        for ((index, acc) in accounts.withIndex()) {
            if (acc.access.isBlank()) continue
            // 多账号间隔请求，避免连发触发风控
            if (index > 0) delay(3000)
            if (!checkinOne(store, acc)) allOk = false
        }
        return if (allOk) Result.success() else Result.retry()
    }

    /**
     * 单账号签到，失败返回 false（外层整体 Result.retry）。
     * 认证失效时自动 ExchangeToken，并把新 Token 写回该账号自己。
     */
    private suspend fun checkinOne(store: TokenStore, acc: TraeAccount): Boolean {
        val ctx = applicationContext
        return try {
            var api = TraeApi(acc.access, acc.device)
            var status = api.status()

            if (status == null) {
                val renewed = TraeApi.exchangeToken(acc.refresh)
                if (renewed != null) {
                    val updated = acc.copy(access = renewed.first, refresh = renewed.second)
                    store.updateTraeAccount(updated)
                    api = TraeApi(updated.access, updated.device)
                    status = api.status()
                }
                if (status == null) {
                    val msg = "Token 失效，自动刷新被拒绝，请在电脑上重新运行 extract_tokens.py 提取"
                    store.updateTraeAccount(acc.copy(lastResult = msg))
                    Notifier.notify(ctx, "Trae 签到失败 · ${acc.name}", msg)
                    return false
                }
            }

            // 积分概要（used/total），失败不影响主流程
            val usage = api.entUsage()
            val usageText = usage?.let { "，已用 ${it.first} / ${it.second}" } ?: ""

            if (status.optBoolean("checked_in")) {
                val credits = status.optInt("credits", 0)
                val extra = status.optInt("extra_credits", 0)
                val msg = "今日 +${credits + extra} 积分$usageText"
                store.updateTraeAccount(acc.copy(lastResult = msg))
                Notifier.notify(ctx, "Trae 今日已签到 · ${acc.name}", msg)
                return true
            }

            val claim = api.claim()
            if (claim.optInt("code") == 0) {
                val credits = claim.optInt("credits", 150)
                val extra = claim.optInt("extra_credits", 50)
                val msg = "+${credits + extra} 积分$usageText"
                store.updateTraeAccount(acc.copy(lastResult = msg))
                Notifier.notify(ctx, "Trae 签到成功 · ${acc.name}", msg)
                true
            } else {
                val msg = claim.optString("message").ifBlank { "code=${claim.optInt("code")}" }
                store.updateTraeAccount(acc.copy(lastResult = msg))
                Notifier.notify(ctx, "Trae 签到失败 · ${acc.name}", msg)
                false
            }
        } catch (e: Exception) {
            store.updateTraeAccount(acc.copy(lastResult = "失败: ${e.message}"))
            false
        }
    }
}
