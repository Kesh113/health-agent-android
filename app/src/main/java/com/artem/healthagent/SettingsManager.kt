package com.artem.healthagent

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stores user-configurable settings in SharedPreferences.
 * No secrets are hardcoded — everything is entered by the user.
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("health_agent_settings", Context.MODE_PRIVATE)

    // ─── Server ──────────────────────────────────────────────────────────────

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply() }

    val isConfigured: Boolean get() = serverUrl.isNotBlank()

    // ─── Schedule ─────────────────────────────────────────────────────────────

    var morningHour: Int
        get() = prefs.getInt(KEY_MORNING_HOUR, 9)
        set(value) { prefs.edit().putInt(KEY_MORNING_HOUR, value).apply() }

    var eveningHour: Int
        get() = prefs.getInt(KEY_EVENING_HOUR, 21)
        set(value) { prefs.edit().putInt(KEY_EVENING_HOUR, value).apply() }

    // ─── Sync history ─────────────────────────────────────────────────────────

    var lastSyncTime: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_SYNC, value).apply() }

    var lastSyncStatus: String
        get() = prefs.getString(KEY_LAST_STATUS, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LAST_STATUS, value).apply() }

    var totalSyncs: Int
        get() = prefs.getInt(KEY_TOTAL_SYNCS, 0)
        set(value) { prefs.edit().putInt(KEY_TOTAL_SYNCS, value).apply() }

    fun recordSync(success: Boolean, statusCode: Int = 0) {
        lastSyncTime   = System.currentTimeMillis()
        lastSyncStatus = if (success) "OK $statusCode" else "FAIL $statusCode"
        totalSyncs     = totalSyncs + 1
    }

    fun lastSyncDescription(): String {
        if (lastSyncTime == 0L) return "Синхронизаций ещё не было"
        val fmt  = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val time = fmt.format(Date(lastSyncTime))
        val ok   = lastSyncStatus.startsWith("OK")
        return "${if (ok) "✓" else "✗"} $time · всего: $totalSyncs"
    }

    // ─── URL validation ───────────────────────────────────────────────────────

    fun validateUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return "URL не может быть пустым"
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://"))
            return "URL должен начинаться с http:// или https://"
        if (!trimmed.contains(".") && !trimmed.contains("localhost") && !trimmed.matches(Regex("https?://\\d+\\.\\d+\\.\\d+\\.\\d+.*")))
            return "Некорректный URL"
        return null // valid
    }

    companion object {
        private const val KEY_SERVER_URL   = "server_url"
        private const val KEY_MORNING_HOUR = "morning_hour"
        private const val KEY_EVENING_HOUR = "evening_hour"
        private const val KEY_LAST_SYNC    = "last_sync_time"
        private const val KEY_LAST_STATUS  = "last_sync_status"
        private const val KEY_TOTAL_SYNCS  = "total_syncs"
    }
}
