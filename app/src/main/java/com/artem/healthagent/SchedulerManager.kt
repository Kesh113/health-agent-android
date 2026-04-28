package com.artem.healthagent

import android.content.Context
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules daily health syncs at 09:00 and 21:00.
 * Uses OneTimeWorkRequest with self-rescheduling to hit exact times
 * (PeriodicWorkRequest has ±10min flex which is fine, but this is cleaner).
 */
object SchedulerManager {

    fun scheduleDailySyncs(context: Context) {
        scheduleAt(context, Config.MORNING_HOUR, Config.MORNING_MINUTE, Config.WORK_TAG_MORNING)
        scheduleAt(context, Config.EVENING_HOUR, Config.EVENING_MINUTE, Config.WORK_TAG_EVENING)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelAllWorkByTag(Config.WORK_TAG_MORNING)
        wm.cancelAllWorkByTag(Config.WORK_TAG_EVENING)
    }

    private fun scheduleAt(context: Context, hour: Int, minute: Int, tag: String) {
        val delay = msUntilNext(hour, minute)

        val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .addTag(tag)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(tag, ExistingWorkPolicy.REPLACE, request)
    }

    /** Milliseconds until the next occurrence of [hour]:[minute] */
    private fun msUntilNext(hour: Int, minute: Int): Long {
        val now    = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target <= now) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis - now.timeInMillis
    }

    fun nextSyncDescription(context: Context): String {
        val morning = msUntilNext(Config.MORNING_HOUR, Config.MORNING_MINUTE)
        val evening = msUntilNext(Config.EVENING_HOUR, Config.EVENING_MINUTE)
        val next    = minOf(morning, evening)
        val h = next / 3_600_000
        val m = (next % 3_600_000) / 60_000
        return "Следующая синхронизация через ${h}ч ${m}м"
    }
}
