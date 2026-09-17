package com.example.checkin.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** HTTP 响应：保留状态码，便于把 429 / 5xx 识别成「限流，值得重试」而不是鉴权失败。 */
data class HttpResult(val code: Int, val body: String)

object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun postRaw(
        url: String,
        body: String,
        authHeader: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResult {
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .addHeader("Accept", "application/json")
        // authHeader 为空时不带 Authorization（WorkBuddy 的 RT 续期接口只认 X-Refresh-Token 头）
        if (authHeader.isNotBlank()) builder.addHeader("Authorization", authHeader)

        extraHeaders.forEach { (k, v) -> builder.addHeader(k, v) }

        client.newCall(builder.build()).execute().use { response ->
            return HttpResult(response.code, response.body?.string() ?: "")
        }
    }

    /** 只要响应体，忽略状态码（业务成败看 body 里的 code 字段）。 */
    fun post(
        url: String,
        body: String,
        authHeader: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String = postRaw(url, body, authHeader, extraHeaders).body
}
