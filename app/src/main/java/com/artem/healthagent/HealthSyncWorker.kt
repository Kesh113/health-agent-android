package com.artem.healthagent

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "HealthSyncWorker"

/**
 * WorkManager worker that:
 * 1. Connects to Samsung Health
 * 2. Collects all data for the last 24h
 * 3. POSTs to health-proxy.py
 * 4. Schedules the next run (self-rescheduling for exact times)
 */
class HealthSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting health sync")
        val mgr = HealthDataManager(applicationContext)

        try {
            // Connect to Samsung Health (synchronous in new SDK, no Activity needed in background)
            val connected = mgr.connectBackground()
            if (!connected) {
                Log.e(TAG, "Failed to connect to Samsung Health")
                return@withContext Result.retry()
            }

            // Check permissions
            if (!mgr.hasAllPermissions()) {
                Log.w(TAG, "Missing Samsung Health permissions")
                return@withContext Result.failure(
                    workDataOf("error" to "Missing permissions — open the app to grant them")
                )
            }

            // Collect all health data
            Log.i(TAG, "Collecting health data...")
            val data = mgr.collectAll()
            Log.i(TAG, "Collected: ${data.keys().asSequence().toList()}")

            // Send to proxy
            val sendResult = WebhookSender.send(data)
            return@withContext if (sendResult.success) {
                Log.i(TAG, "Sent successfully (HTTP ${sendResult.statusCode})")
                Result.success()
            } else {
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
        /**
         * Enqueue a one-time immediate sync (manual "Send Now")
         */
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
