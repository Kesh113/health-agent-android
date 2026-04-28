package com.artem.healthagent

object Config {
    const val MORNING_MINUTE = 0
    const val EVENING_MINUTE = 0

    // How far back to look for health data (milliseconds)
    const val DATA_WINDOW_MS = 24 * 60 * 60 * 1000L  // 24 hours

    // WorkManager tags
    const val WORK_TAG_MORNING = "health_sync_morning"
    const val WORK_TAG_EVENING = "health_sync_evening"
    const val WORK_TAG_MANUAL  = "health_sync_manual"
}
