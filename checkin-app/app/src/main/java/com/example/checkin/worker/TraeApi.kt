package com.example.checkin.worker

import com.example.checkin.data.TokenStore
import com.example.checkin.network.ApiClient
import org.json.JSONObject

/**
 * Trae 签到/积分/刷新 API 封装。
 * 认证方式：Authorization: Cloud-IDE-JWT <token>（不是 Bearer），
 * 且必须携带 X-Device-Id 头（缺失返回 9004）。
 */
class TraeApi(private val store: TokenStore) {

    companion object {
        private const val UG_BASE = "https://api.trae.cn"
        private const val STATUS = "$UG_BASE/trae/api/v2/ug/checkin_credits/status"
        private const val CLAIM = "$UG_BASE/trae/api/v2/ug/checkin_credits/claim"
        private const val ENT_USAGE = "$UG_BASE/trae/api/v2/pay/ide_user_ent_usage"
        private const val EXCHANGE = "https://api.trae.com.cn/cloudide/api/v3/trae/oauth/ExchangeToken"
        // Trae CN 桌面端 stable 的 ClientID（来自 Trae 安装目录 product.json iCubeApp.authConfig）
        // SOLO 版是 en1oxy7wnw8j9n，用错会返回 "refresh token is not matched to the client"
        private const val CLIENT_ID = "ono9krqynydwx5"
        private const val UA = "Trae/0.1.43"
    }

    private fun headers(deviceId: String) = mapOf(
        "User-Agent" to UA,
        "X-User-Region" to "CN",
        "X-Device-Id" to deviceId,
    )

    private suspend fun auth(deviceId: String) =
        "Cloud-IDE-JWT ${store.getTraeAccess()}" to headers(deviceId)

    /** 签到状态。返回 null 表示认证失效（code 1001/9004 等）。*/
    suspend fun status(): JSONObject? {
        val (auth, extra) = auth(store.getTraeDevice())
        val json = JSONObject(
            ApiClient.post(STATUS, "{}", auth, extra)
        )
        return when (json.optInt("code", -1)) {
            0 -> json
            else -> null
        }
    }

    /** 执行签到，返回响应 JSON（code==0 成功）。*/
    suspend fun claim(): JSONObject {
        val (auth, extra) = auth(store.getTraeDevice())
        return JSONObject(ApiClient.post(CLAIM, "{}", auth, extra))
    }

    /**
     * 积分概要：used/total 来自 ide_user_ent_usage 的 usage_summary。
     * 失败返回 null（不影响签到主流程）。
     */
    suspend fun entUsage(): Pair<Double, Double>? = try {
        val (auth, extra) = auth(store.getTraeDevice())
        val json = JSONObject(ApiClient.post(ENT_USAGE, "{}", auth, extra))
        val summary = json.optJSONObject("usage_summary")
        if (summary != null) {
            Pair(summary.optDouble("consumed_amount", 0.0), summary.optDouble("total_amount", 0.0))
        } else null
    } catch (e: Exception) {
        null
    }

    /**
     * 用 refreshToken 调 ExchangeToken 换新 accessToken（可能轮换 refreshToken）。
     * 兼容两种响应结构：traework2api 的 Result.Token 和 trae-mate 的 data.access_token。
     * 成功返回 true 并落盘；失败返回 false（refreshToken 绑定客户端，Trae CN 桌面端
     * 的 refreshToken 由桌面客户端自行刷新，第三方刷新可能不被接受）。
     */
    suspend fun refreshAccess(): Boolean {
        val refreshToken = store.getTraeRefresh()
        if (refreshToken.isBlank()) return false
        val body = JSONObject().apply {
            put("ClientID", CLIENT_ID)
            put("RefreshToken", refreshToken)
            put("ClientSecret", "-")
            put("UserID", "")
        }
        return try {
            val resp = JSONObject(
                ApiClient.post(EXCHANGE, body.toString(), "Bearer")
            )
            val result = resp.optJSONObject("Result")
            val data = resp.optJSONObject("data")
            val newToken = result?.optString("Token").orEmpty().ifBlank {
                data?.optString("access_token").orEmpty().ifBlank {
                    data?.optString("token").orEmpty()
                }
            }
            if (newToken.isBlank()) return false
            val newRefresh = result?.optString("RefreshToken").orEmpty().ifBlank {
                data?.optString("refresh_token").orEmpty()
            }
            store.saveTraeToken(newToken, newRefresh, "")
            true
        } catch (e: Exception) {
            false
        }
    }
}
