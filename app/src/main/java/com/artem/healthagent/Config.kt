package com.artem.healthagent

object Config {
    // VPS endpoint — health-proxy.py listens here
    const val SERVER_URL = "http://YOUR_VPS_HOST:3200"

    // Daily sync schedule (24h format)
    const val MORNING_HOUR = 9
    const val MORNING_MINUTE = 0
    const val EVENING_HOUR = 21
    const val EVENING_MINUTE = 0

    // How far back to look for health data (milliseconds)
    const val DATA_WINDOW_MS = 24 * 60 * 60 * 1000L  // 24 hours

    // WorkManager tag
    const val WORK_TAG_MORNING = "health_sync_morning"
    const val WORK_TAG_EVENING = "health_sync_evening"
    const val WORK_TAG_MANUAL  = "health_sync_manual"
}
