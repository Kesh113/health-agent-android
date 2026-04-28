package com.artem.healthagent

import android.app.Activity
import android.content.Context
import android.util.Log
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.AggregatedData
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.entries.ExerciseSession
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
        Permission.of(DataTypes.STEPS,        AccessType.READ),
        Permission.of(DataTypes.HEART_RATE,   AccessType.READ),
        Permission.of(DataTypes.SLEEP,        AccessType.READ),
        Permission.of(DataTypes.EXERCISE,     AccessType.READ),
        Permission.of(DataTypes.BLOOD_OXYGEN, AccessType.READ),
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
            safePut("steps",             querySteps(s, filter))
            safePut("heart_rate",        queryHeartRate(s, filter))
            safePut("sleep",             querySleep(s, filter))
            safePut("exercise",          queryExercise(s, filter))
            safePut("oxygen_saturation", queryBloodOxygen(s, filter))
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
            result.dataList.forEach { data: AggregatedData<Long> ->
                val count = data.value ?: 0L
                if (count > 0) {
                    array.put(JSONObject().apply {
                        put("count",      count)
                        put("start_time", data.startTime?.toEpochMilli() ?: 0L)
                        put("end_time",   data.endTime?.toEpochMilli() ?: 0L)
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
                        put("timestamp", point.startTime?.toEpochMilli() ?: 0L)
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
                    val durSec: Long = runCatching {
                        (point.getValue(DataType.SleepType.DURATION) as java.time.Duration).seconds
                    }.getOrDefault(0L)
                    val score = runCatching {
                        point.getValue(DataType.SleepType.SLEEP_SCORE) as? Int
                    }.getOrNull()

                    array.put(JSONObject().apply {
                        put("start_time",       point.startTime?.toEpochMilli() ?: 0L)
                        put("end_time",         point.endTime?.toEpochMilli() ?: 0L)
                        put("duration_seconds", durSec.toInt())
                        if (score != null) put("score", score.toInt())
                    })
                }
            }
        }.onFailure { Log.w(TAG, "querySleep failed: ${it.message}") }
        return array
    }

    private suspend fun queryExercise(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val request = DataTypes.EXERCISE.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.DESC)
                .build()
            val result = s.readData(request)
            result.dataList.forEach { point: HealthDataPoint ->
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val sessions = point.getValue(DataType.ExerciseType.SESSIONS) as? List<ExerciseSession>
                    sessions?.forEach { session ->
                        runCatching {
                            val typeRaw = runCatching {
                                point.getValue(DataType.ExerciseType.EXERCISE_TYPE)
                            }.getOrNull()
                            val typeName = exerciseTypeName(typeRaw)
                            val durSec = session.duration?.seconds ?: 0L
                            val dist   = session.distance ?: 0.0
                            val cal    = session.calories ?: 0.0
                            val hrMean = session.meanHeartRate ?: 0
                            val hrMax  = session.maxHeartRate ?: 0
                            array.put(JSONObject().apply {
                                put("type",             typeName)
                                put("duration_minutes", durSec / 60.0)
                                put("distance_m",       dist)
                                put("calories",         cal)
                                if (hrMean > 0) put("mean_hr", hrMean)
                                if (hrMax > 0)  put("max_hr",  hrMax)
                                put("start_time", session.startTime?.toEpochMilli() ?: 0L)
                            })
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "queryExercise failed: ${it.message}") }
        return array
    }

    private suspend fun queryBloodOxygen(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val request = DataTypes.BLOOD_OXYGEN.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.DESC)
                .build()
            val result = s.readData(request)
            result.dataList.forEach { point: HealthDataPoint ->
                runCatching {
                    val spo2 = point.getValue(DataType.BloodOxygenType.OXYGEN_SATURATION)
                    val pct = when (spo2) {
                        is Float  -> spo2.toDouble()
                        is Double -> spo2
                        else      -> return@runCatching
                    }
                    array.put(JSONObject().apply {
                        put("percentage", pct)
                        put("timestamp",  point.startTime?.toEpochMilli() ?: 0L)
                    })
                }
            }
        }.onFailure { Log.w(TAG, "queryBloodOxygen failed: ${it.message}") }
        return array
    }

    private fun exerciseTypeName(typeRaw: Any?): String {
        val code = when (typeRaw) {
            is Int  -> typeRaw
            is Long -> typeRaw.toInt()
            else    -> return typeRaw?.toString() ?: "Unknown"
        }
        return when (code) {
            1000 -> "Walking";   1001 -> "Running";     1002 -> "Cycling"
            1003 -> "Swimming";  1004 -> "Hiking";       1005 -> "Elliptical"
            1006 -> "Rowing";    2001 -> "Strength";     2002 -> "Yoga"
            2003 -> "Pilates";   3001 -> "Basketball";   3002 -> "Soccer"
            3003 -> "Tennis";    3004 -> "Golf";         4001 -> "Skiing"
            4002 -> "Snowboard"; 5001 -> "Dance";        6001 -> "Jump rope"
            else -> "Exercise ($code)"
        }
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private fun JSONObject.safePut(key: String, value: Any) {
        try { put(key, value) } catch (_: Exception) {}
    }
}
