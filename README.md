# Health Agent — Android App

Android app that reads health data from Samsung Health and sends it to a self-hosted AI agent at scheduled times (default: 09:00 and 21:00).

**Stack:** Samsung Health Data SDK · WorkManager · ClaudeClaw · Telegram

---

## Architecture

```
Samsung Health app
      │
      │  Samsung Health Data SDK (IPC)
      ▼
Health Agent app  ──── HTTP POST ────►  health-proxy.py (port 3200)
                                               │
                                               │  HMAC-signed POST
                                               ▼
                                        ClaudeClaw webhook (port 3100)
                                               │
                                               ▼
                                        AI Agent → Telegram
```

### Components

| Component | Location | Role |
|-----------|----------|------|
| Android app | this repo | Reads Samsung Health, sends to proxy |
| `health-proxy.py` | VPS `/root/health-proxy.py` | Formats data, signs with HMAC, forwards to ClaudeClaw |
| ClaudeClaw | VPS `/root/my-assistant/claudeclaw` | Runs AI agent, posts to Telegram |

---

## Data collected

| Type | Period | SDK constant |
|------|--------|-------------|
| Steps (count, calories, distance) | 24h | `DataTypes.STEPS` |
| Heart rate (all measurements) | 24h | `DataTypes.HEART_RATE` |
| Sleep (duration, score) | 24h | `DataTypes.SLEEP` |
| Exercise sessions | 24h | `DataTypes.EXERCISE` |
| Blood oxygen (SpO2) | 24h | `DataTypes.BLOOD_OXYGEN` |

Each query is wrapped in `runCatching` — a failure in one type never breaks others.

---

## Setup

### 1. Samsung Health Data SDK (AAR)

The SDK is **not on Maven Central**. Download manually:

**Option A — Samsung Developer Portal** (requires non-RU account):
https://developer.samsung.com/health/data/overview.html

**Option B — from GitHub** (no account needed):
```
https://raw.githubusercontent.com/cjae/rn-samsung-health-data-api/main/android/libs/samsung-health-data-api-1.0.0-b2.aar
```

Place the AAR in:
```
app/libs/samsung-health-data-api-1.0.0-b2.aar
```

The `build.gradle.kts` picks up all `*.aar` from `libs/` automatically.

### 2. Samsung Health developer mode

In Samsung Health: **Settings → About Samsung Health → tap version 10 times**
Enable **"Developer Mode for Data Read"**.

Without this, the SDK returns no data.

### 3. Secrets — local.properties

Copy the example and fill in your values:
```bash
cp local.properties.example local.properties
```

Edit `local.properties`:
```properties
health.server.url=http://YOUR_VPS_IP:3200
health.morning.hour=9
health.evening.hour=21
```

`local.properties` is gitignored — **never commit it**.

### 4. Android Studio

1. File → Open → select `android-health-app` folder
2. Gradle sync (wait for it to finish)
3. Build → Make Project

### 5. Install on device

```bash
./gradlew installDebug
```
or Run → Run 'app' in Android Studio.

### 6. First launch

1. Tap **"Samsung Health permissions"** → grant Steps, Heart Rate, Sleep, Exercise, Blood Oxygen
2. Tap **"Send now"** to test
3. Check the log — you should see data counts and HTTP 200

---

## Server setup

### health-proxy.py

Receives raw JSON from the app, formats it into a human-readable prompt, signs with HMAC-SHA256, and forwards to ClaudeClaw webhook.

```bash
# Install as systemd service
cat > /etc/systemd/system/health-proxy.service << 'EOF'
[Unit]
Description=Health Proxy
After=network.target

[Service]
ExecStart=/usr/bin/python3 /root/health-proxy.py
Restart=always
EnvironmentFile=/root/my-assistant/claudeclaw/.env

[Install]
WantedBy=multi-user.target
EOF

systemctl enable --now health-proxy.service
```

Note the `EnvironmentFile` line — it reads `WEBHOOK_SECRET` from ClaudeClaw's `.env`.

### ClaudeClaw .env

Required keys:
```env
WEBHOOK_SECRET=<your-secret>   # generate: openssl rand -hex 32
WEBHOOK_PORT=3100
```

### Test the pipeline

```bash
# Generate signature
SECRET="your-webhook-secret"
BODY='{"steps":[{"count":8000}]}'
SIG=$(echo -n "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $2}')

# Send to proxy
curl -X POST http://localhost:3200 \
  -H "Content-Type: application/json" \
  -d "$BODY"
```

---

## Adding more data types

Samsung Health Data SDK supports additional types. To add one:

**1. Add permission** in `HealthDataManager.kt`:
```kotlin
private val requiredPermissions: Set<Permission> = setOf(
    // ... existing ...
    Permission.of(DataTypes.BLOOD_PRESSURE, AccessType.READ),
)
```

**2. Add query method**:
```kotlin
private suspend fun queryBloodPressure(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
    val array = JSONArray()
    runCatching {
        val request = DataTypes.BLOOD_PRESSURE.readDataRequestBuilder
            .setLocalTimeFilter(filter)
            .build()
        val result = s.readData(request)
        result.dataList.forEach { point ->
            runCatching {
                array.put(JSONObject().apply {
                    put("systolic",  point.getValue(DataType.BloodPressureType.SYSTOLIC))
                    put("diastolic", point.getValue(DataType.BloodPressureType.DIASTOLIC))
                })
            }
        }
    }.onFailure { Log.w(TAG, "queryBloodPressure failed: ${it.message}") }
    return array
}
```

**3. Call it in `collectAll()`**:
```kotlin
safePut("blood_pressure", queryBloodPressure(s, filter))
```

**4. Handle it in `health-proxy.py`** on the server — add a formatting block in `format_prompt()`.

---

## Known DataType fields (SDK 1.0.0-b2)

| Category | DataTypes constant | DataType fields |
|----------|--------------------|-----------------|
| Steps (aggregate) | `DataType.StepsType.TOTAL` | `data.value: Long` |
| Heart rate | `DataTypes.HEART_RATE` | `DataType.HeartRateType.HEART_RATE/MAX/MIN` |
| Sleep | `DataTypes.SLEEP` | `DataType.SleepType.DURATION/SLEEP_SCORE/SESSIONS` |
| Exercise | `DataTypes.EXERCISE` | `DataType.ExerciseType.SESSIONS/EXERCISE_TYPE` |
| Blood oxygen | `DataTypes.BLOOD_OXYGEN` | `DataType.BloodOxygenType.OXYGEN_SATURATION` |

**Steps** use `aggregateData()`, all others use `readData()`.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| App crashes on launch | Check that Samsung Health is installed and running |
| "No permissions" | Tap permissions button, grant all in Samsung Health dialog |
| HTTP 502 from server | Check `health-proxy.py` logs: `journalctl -u health-proxy -n 20` |
| HTTP 401 from server | `WEBHOOK_SECRET` mismatch — ensure `EnvironmentFile` is set in service |
| No data returned | Enable developer mode in Samsung Health (tap version 10 times) |
| `NoClassDefFoundError: Parceler` | `kotlin-parcelize` plugin missing in `build.gradle.kts` |

---

## Project structure

```
app/
  libs/                          ← place samsung-health-data-api-*.aar here
  src/main/java/com/artem/healthagent/
    Config.kt                    ← reads from BuildConfig (set via local.properties)
    HealthDataManager.kt         ← Samsung Health queries
    WebhookSender.kt             ← HTTP POST to health-proxy.py
    HealthSyncWorker.kt          ← WorkManager background worker
    SchedulerManager.kt          ← schedules 09:00 / 21:00 syncs
    BootReceiver.kt              ← restores schedule after reboot
    MainActivity.kt              ← UI with log + "Send now" button
  src/main/res/xml/
    network_security_config.xml  ← allows HTTP to your VPS
local.properties.example         ← copy to local.properties, fill secrets
```
