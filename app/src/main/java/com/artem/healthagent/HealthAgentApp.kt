package com.artem.healthagent

import android.app.Application

class HealthAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Schedule daily syncs on first launch
        SchedulerManager.scheduleDailySyncs(this)
    }
}
