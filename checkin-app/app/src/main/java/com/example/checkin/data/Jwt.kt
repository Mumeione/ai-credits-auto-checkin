package com.example.checkin.data

import android.util.Base64
import org.json.JSONObject

/**
 * 极简 JWT 解析（只读 payload，不校验签名——签名权威在服务端，本地只用它取稳定标识）。
 *
 * 用途：给账号算一个「跨 Token 轮换保持稳定」的身份键。
 * - Trae 的 accessToken payload 里有 `data.id`（用户 ID，16 位左右的长数字）
 * - WorkBuddy 的 accessToken 是 Keycloak 风格，有 `sub`（用户 UUID）与 `preferred_username`
 *
 * 1.2.0 之前靠 `accessToken` / `refreshToken` 字符串完全相等来判断「同一个账号」，
 * 但 Token 过期后重新提取必然拿到新字符串（Trae 的 refreshToken 还会轮换），
 * 于是同一个账号被当成新账号重复追加。改为按这里的稳定标识匹配。
 */
object Jwt {

    fun claims(token: String): JSONObject? {
        val parts = token.split(".")
        if (parts.size < 2) return null
        val payload = decode(parts[1]) ?: return null
        return try {
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    /** JWT 用 base64url 且去掉尾部 '='，这里补回填充再解码。 */
    private fun decode(seg: String): ByteArray? {
        var s = seg.replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) s += "="
        return try {
            Base64.decode(s, Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    /** Trae 用户 ID：payload.data.id，退化到常见字段名。 */
    fun traeUserId(accessToken: String): String? {
        val c = claims(accessToken) ?: return null
        val d = c.optJSONObject("data")
        return firstNonBlank(
            d?.optString("id"),
            d?.optString("userId"),
            d?.optString("uid"),
            c.optString("uid"),
            c.optString("user_id"),
            c.optString("sub"),
        )
    }

    /** WorkBuddy 用户标识：sub，退化到手机号/用户名的 preferred_username。 */
    fun wbUserId(token: String): String? {
        val c = claims(token) ?: return null
        return firstNonBlank(c.optString("sub"), c.optString("preferred_username"))
    }

    /** accessToken 的过期时间（epoch 秒，payload.exp）；解析不出返回 null。 */
    fun expEpochSeconds(token: String): Long? {
        val c = claims(token) ?: return null
        val exp = c.optLong("exp", -1L)
        return if (exp > 0) exp else null
    }
}
