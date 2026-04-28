package com.artem.healthagent

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * POSTs health data JSON to health-proxy.py on the VPS.
 * The proxy handles HMAC signing and forwarding to ClaudeClaw.
 *
 * The server URL is read from SettingsManager at call time — no hardcoded URLs.
 */
object WebhookSender {

    data class Result(
        val success: Boolean,
        val statusCode: Int = 0,
        val error: String = ""
    )

    fun send(data: JSONObject, serverUrl: String): Result {
        if (serverUrl.isBlank()) return Result(success = false, error = "URL не настроен — открой Настройки")
        val body = data.toString()
        return try {
            val conn = URL(serverUrl).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod          = "POST"
                doOutput               = true
                connectTimeout         = 10_000
                readTimeout            = 15_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent",   "HealthAgent/1.0 Android")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            conn.disconnect()

            Result(success = code in 200..299, statusCode = code)
        } catch (e: Exception) {
            Result(success = false, error = e.message ?: "unknown error")
        }
    }
}
