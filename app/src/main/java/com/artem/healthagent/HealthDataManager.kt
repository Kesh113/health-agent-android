package com.artem.healthagent

import android.app.Activity
import android.content.Context
import android.util.Log
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.AggregatedData
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

private const val TAG = "HealthDataManager"

/**
 * Reads health data from Samsung Health using the Samsung Health Data SDK.
 * AAR: samsung-health-data-api-1.0.0-b2.aar (no Client ID, developer mode only).
 */
class HealthDataManager(private val context: Context) {

    private var store: HealthDataStore? = null

    // ─── Permissions ──────────────────────────────────────────────────────────

    private val requiredPermissions: Set<Permission> = setOf(
        Permission.of(DataTypes.STEPS,         AccessType.READ),
        Permission.of(DataTypes.HEART_RATE,    AccessType.READ),
        Permission.of(DataTypes.SLEEP,         AccessType.READ),
    )

    // ─── Connection ───────────────────────────────────────────────────────────

    /** Connect from UI (Activity context). */
    fun connect(activity: Activity): Boolean {
        return runCatching {
            store = HealthDataService.getStore(context)
            Log.i(TAG, "Connected to Samsung Health Data SDK")
            true
        }.getOrElse { e ->
            Log.e(TAG, "Connection failed: ${e.message}")
            false
        }
    }

    /** Connect from WorkManager background task (no Activity). */
    fun connectBackground(): Boolean {
        return runCatching {
            store = HealthDataService.getStore(context)
            Log.i(TAG, "Connected (background)")
            true
        }.getOrElse { e ->
            Log.e(TAG, "Background connection failed: ${e.message}")
            false
        }
    }

    fun disconnect() { store = null }

    // ─── Permissions ──────────────────────────────────────────────────────────

    suspend fun hasAllPermissions(): Boolean {
        val s = store ?: return false
        return runCatching {
            val granted = s.getGrantedPermissions(requiredPermissions)
            granted.containsAll(requiredPermissions)
        }.getOrDefault(false)
    }

    suspend fun requestPermissions(activity: Activity): Set<Permission> {
        val s = store ?: return emptySet()
        return runCatching {
            s.requestPermissions(requiredPermissions, activity)
        }.getOrDefault(emptySet())
    }

    // ─── Main collection ──────────────────────────────────────────────────────

    /**
     * Collect all available health data for the last [windowMs] ms.
     * Each query failure is silently caught — partial data is always returned.
     */
    suspend fun collectAll(windowMs: Long = Config.DATA_WINDOW_MS): JSONObject {
        val s = store ?: return JSONObject()

        val zone      = ZoneId.systemDefault()
        val endTime   = LocalDateTime.now(zone)
        val startTime = endTime.minusSeconds(windowMs / 1000)
        val filter    = LocalTimeFilter.of(startTime, endTime)

        return JSONObject().apply {
            safePut("steps",            querySteps(s, filter))
            safePut("heart_rate",       queryHeartRate(s, filter))
            safePut("sleep",            querySleep(s, filter))
        }
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    private suspend fun querySteps(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val request = DataType.StepsType.TOTAL.requestBuilder
                .setLocalTimeFilter(filter)
                .build()
            val result = s.aggregateData(request)
            result.dataList.forEach { data: AggregatedData ->
                val count = data.value as? Long ?: 0L
                if (count > 0) {
                    array.put(JSONObject().apply {
                        put("count",      count)
                        put("start_time", data.startTime.toEpochMilli())
                        put("end_time",   data.endTime.toEpochMilli())
                    })
                }
            }
        }.onFailure { Log.w(TAG, "querySteps failed: ${it.message}") }
        return array
    }

    private suspend fun queryHeartRate(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val request = DataTypes.HEART_RATE.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.ASC)
                .build()
            val result = s.readData(request)
            result.dataList.forEach { point: HealthDataPoint ->
                runCatching {
                    val bpm = point.getValue(DataType.HeartRateType.HEART_RATE) as? Int ?: return@runCatching
                    array.put(JSONObject().apply {
                        put("bpm",       bpm)
                        put("timestamp", point.startTime.toEpochMilli())
                    })
                }
            }
        }.onFailure { Log.w(TAG, "queryHeartRate failed: ${it.message}") }
        return array
    }

    private suspend fun querySleep(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val request = DataTypes.SLEEP.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.DESC)
                .build()
            val result = s.readData(request)
            result.dataList.forEach { point: HealthDataPoint ->
                runCatching {
                    val durationObj = point.getValue(DataType.SleepType.DURATION)
                    val durSec: Long = when (durationObj) {
                        is java.time.Duration -> durationObj.seconds
                        is Long               -> durationObj / 1000
                        else                  -> 0L
                    }
                    val score = runCatching {
                        point.getValue(DataType.SleepType.SLEEP_SCORE) as? Int
                    }.getOrNull()

                    val stagesArray = JSONArray()
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        val sessions = point.getValue(DataType.SleepType.SESSIONS) as? List<HealthDataPoint>
                        sessions?.forEach { stage ->
                            runCatching {
                                val stageType = stage.getValue(DataType.SleepType.STAGE)
                                val stageName = when {
                                    stageType.toString().contains("AWAKE", ignoreCase = true) -> "awake"
                                    stageType.toString().contains("LIGHT", ignoreCase = true) -> "light"
                                    stageType.toString().contains("DEEP",  ignoreCase = true) -> "deep"
                                    stageType.toString().contains("REM",   ignoreCase = true) -> "rem"
                                    else -> stageType.toString().lowercase()
                                }
                                val stageDur = stage.getValue(DataType.SleepType.DURATION)
                                val stageSec: Long = when (stageDur) {
                                    is java.time.Duration -> stageDur.seconds
                                    is Long               -> stageDur / 1000
                                    else                  -> 0L
                                }
                                stagesArray.put(JSONObject().apply {
                                    put("stage",            stageName)
                                    put("duration_seconds", stageSec)
                                })
                            }
                        }
                    }

                    array.put(JSONObject().apply {
                        put("start_time",       point.startTime.toEpochMilli())
                        put("end_time",         point.endTime.toEpochMilli())
                        put("duration_seconds", durSec)
                        if (score != null) put("score", score)
                        if (stagesArray.length() > 0) put("stages", stagesArray)
                    })
                }
            }
        }.onFailure { Log.w(TAG, "querySleep failed: ${it.message}") }
        return array
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private fun JSONObject.safePut(key: String, value: Any) {
        try { put(key, value) } catch (_: Exception) {}
    }
}
