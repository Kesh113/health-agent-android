package com.artem.healthagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores schedule after device reboot */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            SchedulerManager.scheduleDailySyncs(context)
        }
    }
}
