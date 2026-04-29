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
 * Reads all available health data from Samsung Health.
 * Uses Samsung Health Data SDK 1.0.0-b2 (no Client ID required, developer mode).
 *
 * Each query is wrapped in runCatching — a failure in one type never breaks others.
 */
class HealthDataManager(private val context: Context) {

    private var store: HealthDataStore? = null

    // ─── Permissions ──────────────────────────────────────────────────────────

    private val requiredPermissions: Set<Permission> = setOf(
        Permission.of(DataTypes.STEPS,            AccessType.READ),
        Permission.of(DataTypes.HEART_RATE,       AccessType.READ),
        Permission.of(DataTypes.SLEEP,            AccessType.READ),
        Permission.of(DataTypes.EXERCISE,         AccessType.READ),
        Permission.of(DataTypes.BLOOD_OXYGEN,     AccessType.READ),
        Permission.of(DataTypes.BLOOD_PRESSURE,   AccessType.READ),
        Permission.of(DataTypes.FLOORS_CLIMBED,   AccessType.READ),
        Permission.of(DataTypes.WATER_INTAKE,     AccessType.READ),
        Permission.of(DataTypes.BLOOD_GLUCOSE,    AccessType.READ),
        Permission.of(DataTypes.BODY_COMPOSITION, AccessType.READ),
        Permission.of(DataTypes.SKIN_TEMPERATURE, AccessType.READ),
        Permission.of(DataTypes.NUTRITION,        AccessType.READ),
    )

    // ─── Connection ───────────────────────────────────────────────────────────

    fun connect(activity: Activity): Boolean = runCatching {
        store = HealthDataService.getStore(context)
        Log.i(TAG, "Connected")
        true
    }.getOrElse { Log.e(TAG, "Connect failed: ${it.message}"); false }

    fun connectBackground(): Boolean = runCatching {
        store = HealthDataService.getStore(context)
        true
    }.getOrElse { false }

    fun disconnect() { store = null }

    // ─── Permissions ──────────────────────────────────────────────────────────

    suspend fun hasAllPermissions(): Boolean {
        val s = store ?: return false
        return runCatching {
            s.getGrantedPermissions(requiredPermissions).containsAll(requiredPermissions)
        }.getOrDefault(false)
    }

    suspend fun requestPermissions(activity: Activity): Set<Permission> {
        val s = store ?: return emptySet()
        return runCatching { s.requestPermissions(requiredPermissions, activity) }
            .getOrDefault(emptySet())
    }

    // ─── Main collection ──────────────────────────────────────────────────────

    suspend fun collectAll(windowMs: Long = Config.DATA_WINDOW_MS): JSONObject {
        val s = store ?: return JSONObject()
        val zone   = ZoneId.systemDefault()
        val end    = LocalDateTime.now(zone)
        val start  = end.minusSeconds(windowMs / 1000)
        val filter = LocalTimeFilter.of(start, end)

        return JSONObject().apply {
            // Activity
            safePut("steps",             querySteps(s, filter))
            safePut("floors_climbed",    queryFloors(s, filter))
            // Cardiovascular
            safePut("heart_rate",        queryHeartRate(s, filter))
            safePut("blood_pressure",    queryBloodPressure(s, filter))
            safePut("oxygen_saturation", queryBloodOxygen(s, filter))
            // Sleep
            safePut("sleep",             querySleep(s, filter))
            // Metabolic
            safePut("water_intake",      queryWater(s, filter))
            safePut("nutrition",         queryNutrition(s, filter))
            safePut("blood_glucose",     queryBloodGlucose(s, filter))
            // Body composition
            safePut("body_composition",  queryBodyComposition(s, filter))
            safePut("skin_temperature",  querySkinTemperature(s, filter))
            // Exercise
            safePut("exercise",          queryExercise(s, filter))
        }
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    private suspend fun querySteps(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val req = DataType.StepsType.TOTAL.requestBuilder.setLocalTimeFilter(filter).build()
            s.aggregateData(req).dataList.forEach { data: AggregatedData<Long> ->
                val count = data.value ?: 0L
                if (count > 0) array.put(JSONObject().apply {
                    put("count",      count)
                    put("start_time", data.startTime?.toEpochMilli() ?: 0L)
                    put("end_time",   data.endTime?.toEpochMilli() ?: 0L)
                })
            }
        }.onFailure { Log.w(TAG, "steps: ${it.message}") }
        return array
    }

    private suspend fun queryHeartRate(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val req = DataTypes.HEART_RATE.readDataRequestBuilder
                .setLocalTimeFilter(filter).setOrdering(Ordering.ASC).build()
            s.readData(req).dataList.forEach { point: HealthDataPoint ->
                runCatching {
                    val bpm = point.getValue(DataType.HeartRateType.HEART_RATE)
                    array.put(JSONObject().apply {
                        put("bpm",       numericValue(bpm))
                        put("timestamp", point.startTime?.toEpochMilli() ?: 0L)
                    })
                }
            }
        }.onFailure { Log.w(TAG, "heart_rate: ${it.message}") }
        return array
    }

    private suspend fun queryBloodPressure(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val req = DataTypes.BLOOD_PRESSURE.readDataRequestBuilder
                .setLocalTimeFilter(filter).setOrdering(Ordering.DESC).build()
            s.readData(req).dataList.forEach { point: HealthDataPoint ->
                runCatching {
                    array.put(JSONObject().apply {
                        put("systolic",  numericValue(point.getValue(DataType.BloodPressureType.SYSTOLIC)))
                        put("diastolic", numericValue(point.getValue(DataType.BloodPressureType.DIASTOLIC)))
                        put("timestamp", point.startTime?.toEpochMilli() ?: 0L)
                    })
                }
            }
        }.onFailure { Log.w(TAG, "blood_pressure: ${it.message}") }
        return array
    }

    private suspend fun queryBloodOxygen(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val req = DataTypes.BLOOD_OXYGEN.readDataRequestBuilder
                .setLocalTimeFilter(filter).setOrdering(Ordering.DESC).build()
            s.readData(req).dataList.forEach { point: HealthDataPoint ->
                runCatching {
                    val pct = point.getValue(DataType.BloodOxygenType.OXYGEN_SATURATION)
                    array.put(JSONObject().apply {
                        put("percentage", numericValue(pct))
                        put("timestamp",  point.startTime?.toEpochMilli() ?: 0L)
                    })
                }
            }
        }.onFailure { Log.w(TAG, "blood_oxygen: ${it.message}") }
        return array
    }

    private suspend fun querySleep(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val req = DataTypes.SLEEP.readDataRequestBuilder
                .setLocalTimeFilter(filter).setOrdering(Ordering.DESC).build()
            s.readData(req).dataList.forEach { point: HealthDataPoint ->
                runCatching {
                    val durSec = runCatching {
                        (point.getValue(DataType.SleepType.DURATION) as java.time.Duration).seconds
                    }.getOrDefault(0L)
                    val score = runCatching {
                        numericValue(point.getValue(DataType.SleepType.SLEEP_SCORE)).toInt()
                    }.getOrNull()
                    array.put(JSONObject().apply {
                        put("start_time",       point.startTime?.toEpochMilli() ?: 0L)
                        put("end_time",         point.endTime?.toEpochMilli() ?: 0L)
                        put("duration_seconds", durSec.toInt())
                        if (score != null) put("score", score)
                    })
                }
            }
        }.onFailure { Log.w(TAG, "sleep: ${it.message}") }
        return array
    }

    private suspend fun queryFloors(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            // FLOORS_CLIMBED is an aggregate type (like STEPS) — use aggregateData(), not readData()
            val req = DataType.FloorsClimbedType.TOTAL.requestBuilder
                .setLocalTimeFilter(filter).build()
            s.aggregateData(req).dataList.forEach { data: AggregatedData<Float> ->
                val floors = data.value ?: 0f
                if (floors > 0) array.put(JSONObject().apply {
                    put("count",      floors.toInt())
                    put("start_time", data.startTime?.toEpochMilli() ?: 0L)
                    put("end_time",   data.endTime?.toEpochMilli() ?: 0L)
                })
            }
        }.onFailure { Log.w(TAG, "floors: ${it.message}") }
        return array
    }

    private suspend fun queryWater(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val req = DataTypes.WATER_INTAKE.readDataRequestBuilder
                .setLocalTimeFilter(filter).build()
            s.readData(req).dataList.forEach { point: HealthDataPoint ->
                runCatching {
                    val ml = numericValue(point.getValue(DataType.WaterIntakeType.AMOUNT))
                    array.put(JSONObject().apply {
                        put("ml",        ml.toLong())
                        put("timestamp", point.startTime?.toEpochMilli() ?: 0L)
                    })
                }
            }
        }.onFailure { Log.w(TAG, "water: ${it.message}") }
        return array
    }

    private suspend fun queryNutrition(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val req = DataTypes.NUTRITION.readDataRequestBuilder
                .setLocalTimeFilter(filter).build()
            s.readData(req).dataList.forEach { point: HealthDataPoint ->
                runCatching {
                    array.put(JSONObject().apply {
                        put("calories",  numericValue(runCatching { point.getValue(DataType.NutritionType.CALORIES) }.getOrDefault(0)))
                        put("carbs_g",   numericValue(runCatching { point.getValue(DataType.NutritionType.CARBOHYDRATE) }.getOrDefault(0)))
                        put("protein_g", numericValue(runCatching { point.getValue(DataType.NutritionType.PROTEIN) }.getOrDefault(0)))
                        put("fat_g",     numericValue(runCatching { point.getValue(DataType.NutritionType.TOTAL_FAT) }.getOrDefault(0)))
                        put("timestamp", point.startTime?.toEpochMilli() ?: 0L)
                    })
                }
            }
        }.onFailure { Log.w(TAG, "nutrition: ${it.message}") }
        return array
    }

    private suspend fun queryBloodGlucose(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val req = DataTypes.BLOOD_GLUCOSE.readDataRequestBuilder
                .setLocalTimeFilter(filter).setOrdering(Ordering.DESC).build()
            s.readData(req).dataList.forEach { point: HealthDataPoint ->
                runCatching {
                    array.put(JSONObject().apply {
                        put("mmol_l",    numericValue(point.getValue(DataType.BloodGlucoseType.GLUCOSE_LEVEL)))
                        put("timestamp", point.startTime?.toEpochMilli() ?: 0L)
                    })
                }
            }
        }.onFailure { Log.w(TAG, "blood_glucose: ${it.message}") }
        return array
    }

    private suspend fun queryBodyComposition(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val req = DataTypes.BODY_COMPOSITION.readDataRequestBuilder
                .setLocalTimeFilter(filter).setOrdering(Ordering.DESC).build()
            s.readData(req).dataList.forEach { point: HealthDataPoint ->
                runCatching {
                    array.put(JSONObject().apply {
                        put("weight_kg", numericValue(point.getValue(DataType.BodyCompositionType.WEIGHT)))
                        put("height_cm", numericValue(runCatching { point.getValue(DataType.BodyCompositionType.HEIGHT) }.getOrDefault(0)) * 100)
                        put("bmi",       numericValue(runCatching { point.getValue(DataType.BodyCompositionType.BODY_MASS_INDEX) }.getOrDefault(0)))
                        put("body_fat",  numericValue(runCatching { point.getValue(DataType.BodyCompositionType.BODY_FAT) }.getOrDefault(0)))
                        put("timestamp", point.startTime?.toEpochMilli() ?: 0L)
                    })
                }
            }
        }.onFailure { Log.w(TAG, "body_composition: ${it.message}") }
        return array
    }

    private suspend fun querySkinTemperature(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val req = DataTypes.SKIN_TEMPERATURE.readDataRequestBuilder
                .setLocalTimeFilter(filter).setOrdering(Ordering.DESC).build()
            s.readData(req).dataList.forEach { point: HealthDataPoint ->
                runCatching {
                    array.put(JSONObject().apply {
                        put("celsius",   numericValue(point.getValue(DataType.SkinTemperatureType.SKIN_TEMPERATURE)))
                        put("timestamp", point.startTime?.toEpochMilli() ?: 0L)
                    })
                }
            }
        }.onFailure { Log.w(TAG, "skin_temp: ${it.message}") }
        return array
    }

    private suspend fun queryExercise(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
        val array = JSONArray()
        runCatching {
            val req = DataTypes.EXERCISE.readDataRequestBuilder
                .setLocalTimeFilter(filter).setOrdering(Ordering.DESC).build()
            s.readData(req).dataList.forEach { point: HealthDataPoint ->
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val sessions = point.getValue(DataType.ExerciseType.SESSIONS) as? List<ExerciseSession>
                    val typeRaw  = runCatching { point.getValue(DataType.ExerciseType.EXERCISE_TYPE) }.getOrNull()
                    sessions?.forEach { session ->
                        runCatching {
                            array.put(JSONObject().apply {
                                put("type",             exerciseTypeName(typeRaw))
                                put("duration_minutes", (session.duration?.seconds ?: 0L) / 60.0)
                                put("distance_m",       session.distance ?: 0.0)
                                put("calories",         session.calories ?: 0.0)
                                put("mean_hr",          session.meanHeartRate ?: 0)
                                put("max_hr",           session.maxHeartRate ?: 0)
                                put("start_time",       session.startTime?.toEpochMilli() ?: 0L)
                            })
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "exercise: ${it.message}") }
        return array
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun numericValue(v: Any?): Double = when (v) {
        is Int    -> v.toDouble()
        is Long   -> v.toDouble()
        is Float  -> v.toDouble()
        is Double -> v
        else      -> 0.0
    }

    private fun exerciseTypeName(typeRaw: Any?): String {
        val code = when (typeRaw) {
            is Int  -> typeRaw
            is Long -> typeRaw.toInt()
            else    -> return typeRaw?.toString() ?: "Unknown"
        }
        return when (code) {
            1000 -> "Walking";   1001 -> "Running";    1002 -> "Cycling"
            1003 -> "Swimming";  1004 -> "Hiking";     1005 -> "Elliptical"
            1006 -> "Rowing";    2001 -> "Strength";   2002 -> "Yoga"
            2003 -> "Pilates";   3001 -> "Basketball"; 3002 -> "Soccer"
            3003 -> "Tennis";    3004 -> "Golf";       4001 -> "Skiing"
            4002 -> "Snowboard"; 5001 -> "Dance";      6001 -> "Jump rope"
            else -> "Exercise ($code)"
        }
    }

    private fun JSONObject.safePut(key: String, value: Any) {
        try { put(key, value) } catch (_: Exception) {}
    }
}
