package com.artem.healthagent

import android.app.Activity
import android.content.Context
import android.util.Log
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.DataTypes
import com.samsung.android.sdk.health.data.data.entries.HeartRate
import com.samsung.android.sdk.health.data.data.entries.SleepSession
import com.samsung.android.sdk.health.data.data.entries.StepCount
import com.samsung.android.sdk.health.data.exception.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private const val TAG = "HealthDataManager"

/**
 * Reads all available health data from Samsung Health using the
 * new Samsung Health Data SDK (no Client ID, developer mode only).
 *
 * SDK: samsung-health-data-api-*.aar — download from
 * https://developer.samsung.com/health/data/overview.html
 */
class HealthDataManager(private val context: Context) {

    private var store: HealthDataStore? = null

    // ─── Required permissions ─────────────────────────────────────────────────

    private val requiredPermissions: Set<Permission> = setOf(
        Permission.of(DataTypes.STEPS,               AccessType.READ),
        Permission.of(DataTypes.HEART_RATE,          AccessType.READ),
        Permission.of(DataTypes.SLEEP_SESSION,       AccessType.READ),
        Permission.of(DataTypes.BLOOD_OXYGEN,        AccessType.READ),
        Permission.of(DataTypes.STRESS,              AccessType.READ),
        Permission.of(DataTypes.EXERCISE,            AccessType.READ),
        Permission.of(DataTypes.WEIGHT,              AccessType.READ),
        Permission.of(DataTypes.BLOOD_PRESSURE,      AccessType.READ),
        Permission.of(DataTypes.FLOORS_CLIMBED,      AccessType.READ),
        Permission.of(DataTypes.WATER_INTAKE,        AccessType.READ),
        Permission.of(DataTypes.BODY_TEMPERATURE,    AccessType.READ),
    )

    // ─── Connection ───────────────────────────────────────────────────────────

    /**
     * Connect from UI (Activity) — can show Samsung Health install dialog on error.
     */
    fun connect(activity: Activity): Boolean {
        return runCatching {
            store = HealthDataService.getStore(context)
            Log.i(TAG, "Connected to Samsung Health Data SDK")
            true
        }.getOrElse { error ->
            Log.e(TAG, "Connection failed: ${error.message}")
            if (error is ResolvablePlatformException && error.hasResolution()) {
                error.resolve(activity)
            }
            false
        }
    }

    /**
     * Connect from background (WorkManager) — no Activity, no resolution dialog.
     */
    fun connectBackground(): Boolean {
        return runCatching {
            store = HealthDataService.getStore(context)
            Log.i(TAG, "Connected (background)")
            true
        }.getOrElse { error ->
            Log.e(TAG, "Background connection failed: ${error.message}")
            false
        }
    }

    fun disconnect() {
        store = null
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    suspend fun hasAllPermissions(): Boolean {
        val s = store ?: return false
        return runCatching {
            val granted = s.permissionController.getGrantedPermissions(requiredPermissions)
            granted.containsAll(requiredPermissions)
        }.getOrDefault(false)
    }

    suspend fun requestPermissions(activity: Activity): Set<Permission> {
        val s = store ?: return emptySet()
        return runCatching {
            s.permissionController.requestPermissions(requiredPermissions, activity)
        }.getOrDefault(emptySet())
    }

    // ─── Main data collection ─────────────────────────────────────────────────

    /**
     * Collect all health data for the last [windowMs] milliseconds.
     * Returns a JSONObject ready to POST to health-proxy.py.
     * Failed queries return empty arrays — partial data is always returned.
     */
    suspend fun collectAll(windowMs: Long = Config.DATA_WINDOW_MS): JSONObject {
        val s = store ?: return JSONObject()

        val zone     = ZoneId.systemDefault()
        val endTime  = LocalDateTime.now(zone)
        val startTime = endTime.minusSeconds(windowMs / 1000)
        val timeFilter = LocalTimeFilter.of(startTime, endTime)

        val result = JSONObject()

        result.safePut("steps",              querySteps(s, timeFilter))
        result.safePut("heart_rate",         queryHeartRate(s, timeFilter))
        result.safePut("sleep",              querySleep(s, timeFilter))
        result.safePut("oxygen_saturation",  querySpO2(s, timeFilter))
        result.safePut("stress",             queryStress(s, timeFilter))
        result.safePut("exercise",           queryExercise(s, timeFilter))
        result.safePut("weight",             queryWeight(s, timeFilter))
        result.safePut("blood_pressure",     queryBloodPressure(s, timeFilter))
        result.safePut("floors",             queryFloors(s, timeFilter))
        result.safePut("water",              queryWater(s, timeFilter))
        result.safePut("body_temperature",   queryTemperature(s, timeFilter))

        return result
    }

    // ─── Individual queries ───────────────────────────────────────────────────

    private suspend fun querySteps(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val request = DataType.StepsType.TOTAL.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.DESC)
                .build()
            val data = s.readData(request)
            data.dataList.forEach { entry ->
                array.put(JSONObject().apply {
                    put("count",    entry.count)
                    put("calories", entry.calories ?: 0.0)
                    put("distance", entry.distance ?: 0.0)
                })
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
            val data = s.readData(request)
            data.dataList.forEach { entry: HeartRate ->
                array.put(JSONObject().apply {
                    put("bpm",       entry.heartRate)
                    put("timestamp", entry.startTime.toEpochMilli())
                })
            }
        }.onFailure { Log.w(TAG, "queryHeartRate failed: ${it.message}") }
        return array
    }

    private suspend fun querySleep(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val request = DataTypes.SLEEP_SESSION.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.DESC)
                .build()
            val data = s.readData(request)
            data.dataList.forEach { session: SleepSession ->
                val durSec = session.endTime.epochSecond - session.startTime.epochSecond
                val stagesArray = JSONArray()
                session.stages.forEach { stage ->
                    val stageName = when (stage.stage) {
                        SleepSession.Stage.AWAKE -> "awake"
                        SleepSession.Stage.LIGHT -> "light"
                        SleepSession.Stage.DEEP  -> "deep"
                        SleepSession.Stage.REM   -> "rem"
                        else                     -> "unknown"
                    }
                    val stageDur = stage.endTime.epochSecond - stage.startTime.epochSecond
                    stagesArray.put(JSONObject().apply {
                        put("stage",            stageName)
                        put("duration_seconds", stageDur)
                    })
                }
                array.put(JSONObject().apply {
                    put("start_time",       session.startTime.toEpochMilli())
                    put("end_time",         session.endTime.toEpochMilli())
                    put("duration_seconds", durSec)
                    put("stages",           stagesArray)
                })
            }
        }.onFailure { Log.w(TAG, "querySleep failed: ${it.message}") }
        return array
    }

    private suspend fun querySpO2(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val request = DataTypes.BLOOD_OXYGEN.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.ASC)
                .build()
            val data = s.readData(request)
            data.dataList.forEach { entry ->
                array.put(JSONObject().apply {
                    put("percentage", entry.bloodOxygenSaturation.toDouble())
                    put("timestamp",  entry.startTime.toEpochMilli())
                })
            }
        }.onFailure { Log.w(TAG, "querySpO2 failed: ${it.message}") }
        return array
    }

    private suspend fun queryStress(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val request = DataTypes.STRESS.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.ASC)
                .build()
            val data = s.readData(request)
            data.dataList.forEach { entry ->
                array.put(JSONObject().apply {
                    put("score",     entry.score)
                    put("timestamp", entry.startTime.toEpochMilli())
                })
            }
        }.onFailure { Log.w(TAG, "queryStress failed: ${it.message}") }
        return array
    }

    private suspend fun queryExercise(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val request = DataTypes.EXERCISE.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.DESC)
                .build()
            val data = s.readData(request)
            data.dataList.forEach { entry ->
                val durMin = (entry.endTime.epochSecond - entry.startTime.epochSecond) / 60.0
                array.put(JSONObject().apply {
                    put("type",             entry.exerciseType?.name ?: "Exercise")
                    put("duration_minutes", durMin)
                    put("calories",         entry.calories ?: 0.0)
                    put("distance_m",       entry.distance ?: 0.0)
                })
            }
        }.onFailure { Log.w(TAG, "queryExercise failed: ${it.message}") }
        return array
    }

    private suspend fun queryWeight(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val request = DataTypes.WEIGHT.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.DESC)
                .build()
            val data = s.readData(request)
            data.dataList.firstOrNull()?.let { entry ->
                array.put(JSONObject().apply {
                    put("kg",  entry.weight)
                    put("bmi", entry.bmi ?: 0.0)
                })
            }
        }.onFailure { Log.w(TAG, "queryWeight failed: ${it.message}") }
        return array
    }

    private suspend fun queryBloodPressure(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val request = DataTypes.BLOOD_PRESSURE.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.DESC)
                .build()
            val data = s.readData(request)
            data.dataList.forEach { entry ->
                array.put(JSONObject().apply {
                    put("systolic",  entry.systolic)
                    put("diastolic", entry.diastolic)
                    put("pulse",     entry.pulse ?: 0)
                })
            }
        }.onFailure { Log.w(TAG, "queryBloodPressure failed: ${it.message}") }
        return array
    }

    private suspend fun queryFloors(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val request = DataTypes.FLOORS_CLIMBED.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.ASC)
                .build()
            val data = s.readData(request)
            data.dataList.forEach { entry ->
                array.put(JSONObject().apply {
                    put("count", entry.floorsClimbed)
                })
            }
        }.onFailure { Log.w(TAG, "queryFloors failed: ${it.message}") }
        return array
    }

    private suspend fun queryWater(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val request = DataTypes.WATER_INTAKE.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.ASC)
                .build()
            val data = s.readData(request)
            data.dataList.forEach { entry ->
                // amount is in milliliters
                array.put(JSONObject().apply {
                    put("ml", entry.amount)
                })
            }
        }.onFailure { Log.w(TAG, "queryWater failed: ${it.message}") }
        return array
    }

    private suspend fun queryTemperature(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val request = DataTypes.BODY_TEMPERATURE.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.DESC)
                .build()
            val data = s.readData(request)
            data.dataList.firstOrNull()?.let { entry ->
                array.put(JSONObject().apply {
                    put("celsius", entry.bodyTemperature)
                })
            }
        }.onFailure { Log.w(TAG, "queryTemperature failed: ${it.message}") }
        return array
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun JSONObject.safePut(key: String, value: Any) {
        try { put(key, value) } catch (_: Exception) {}
    }
}
