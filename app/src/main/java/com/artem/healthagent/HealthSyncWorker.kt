package com.artem.healthagent

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "HealthSyncWorker"

/**
 * WorkManager worker that:
 * 1. Checks that a server URL is configured
 * 2. Connects to Samsung Health
 * 3. Collects all data for the last 24h
 * 4. POSTs to health-proxy.py
 */
class HealthSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting health sync")
        val settings = SettingsManager(applicationContext)

        // Guard: URL must be configured
        if (!settings.isConfigured) {
            Log.w(TAG, "Server URL not configured — skipping sync")
            return@withContext Result.failure(
                workDataOf("error" to "URL не настроен — открой Настройки")
            )
        }

        val mgr = HealthDataManager(applicationContext)
        try {
            val connected = mgr.connectBackground()
            if (!connected) {
                Log.e(TAG, "Failed to connect to Samsung Health")
                return@withContext Result.retry()
            }

            if (!mgr.hasAllPermissions()) {
                Log.w(TAG, "Missing Samsung Health permissions")
                return@withContext Result.failure(
                    workDataOf("error" to "Нет разрешений — открой приложение")
                )
            }

            Log.i(TAG, "Collecting health data...")
            val data = mgr.collectAll()
            Log.i(TAG, "Collected: ${data.keys().asSequence().toList()}")

            val sendResult = WebhookSender.send(data, settings.serverUrl)
            return@withContext if (sendResult.success) {
                settings.recordSync(true, sendResult.statusCode)
                Log.i(TAG, "Sent successfully (HTTP ${sendResult.statusCode})")
                Result.success()
            } else {
                settings.recordSync(false, sendResult.statusCode)
                Log.e(TAG, "Send failed: ${sendResult.error} (HTTP ${sendResult.statusCode})")
                Result.retry()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Sync error", e)
            return@withContext Result.retry()
        } finally {
            mgr.disconnect()
        }
    }

    companion object {
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .addTag(Config.WORK_TAG_MANUAL)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(Config.WORK_TAG_MANUAL, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
