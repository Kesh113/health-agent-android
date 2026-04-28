# Health Agent — Android App

Android app that reads health data from Samsung Health and sends it to a self-hosted AI agent at scheduled times (default: 09:00 and 21:00). Server URL and schedule are configured inside the app — no secrets in the build.

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
| ClaudeClaw | VPS (any path) | Runs AI agent, posts to Telegram |

---

## Data collected

| Type | Period | SDK constant |
|------|--------|-------------|
| Steps (count, calories, distance) | 24h | `DataTypes.STEPS` |
| Heart rate (avg/min/max) | 24h | `DataTypes.HEART_RATE` |
| Blood pressure (systolic/diastolic/pulse) | 24h | `DataTypes.BLOOD_PRESSURE` |
| Blood oxygen / SpO2 | 24h | `DataTypes.BLOOD_OXYGEN` |
| Sleep (duration, score) | 24h | `DataTypes.SLEEP` |
| Floors climbed | 24h | `DataTypes.FLOORS_CLIMBED` |
| Water intake | 24h | `DataTypes.WATER_INTAKE` |
| Nutrition (kcal, carbs, protein, fat) | 24h | `DataTypes.NUTRITION` |
| Blood glucose | 24h | `DataTypes.BLOOD_GLUCOSE` |
| Weight (kg, height, BMI, body fat %) | 24h | `DataTypes.WEIGHT` |
| Body temperature | 24h | `DataTypes.BODY_TEMPERATURE` |
| Skin temperature | 24h | `DataTypes.SKIN_TEMPERATURE` |
| Exercise sessions | 24h | `DataTypes.EXERCISE` |

Each query is wrapped in `runCatching` — a failure in one type never blocks others.

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

### 3. Android Studio

1. File → Open → select `android-health-app` folder
2. Gradle sync (wait for it to finish)
3. Build → Make Project

### 4. Install on device

```bash
./gradlew installDebug
```
or Run → Run 'app' in Android Studio.

### 5. First launch

1. The app opens **Settings** automatically on first run
2. Enter your proxy server URL (e.g. `http://YOUR_VPS_IP:3200`) and tap **Сохранить**
3. Optionally adjust sync schedule hours
4. Back on the main screen — tap **Разрешения Samsung Health** and grant access
5. Tap **Отправить сейчас** to send a test payload
6. Check the log — you should see data counts and HTTP 200

> The server URL is stored in the app's private SharedPreferences — never in source code or build config.

---

## Server setup

### health-proxy.py

Receives raw JSON from the app, formats it into a human-readable prompt, signs with HMAC-SHA256, and forwards to ClaudeClaw webhook.

```python
# Minimal example — full version at /root/health-proxy.py on your VPS
import http.server, json, hmac, hashlib, os, urllib.request

WEBHOOK_SECRET = os.environ["WEBHOOK_SECRET"]
CLAUDECLAW_URL = f"http://localhost:{os.environ.get('WEBHOOK_PORT', '3100')}/webhook/<your-group>"

class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        data = json.loads(body)
        prompt = format_prompt(data)      # turn JSON into readable text
        payload = json.dumps({"prompt": prompt}).encode()
        sig = hmac.new(WEBHOOK_SECRET.encode(), payload, hashlib.sha256).hexdigest()
        req = urllib.request.Request(CLAUDECLAW_URL, data=payload,
              headers={"Content-Type": "application/json", "X-Signature": sig})
        urllib.request.urlopen(req)
        self.send_response(200); self.end_headers()
```

Install as a systemd service — note the `EnvironmentFile` line so it reads `WEBHOOK_SECRET` from ClaudeClaw's `.env` automatically:

```bash
cat > /etc/systemd/system/health-proxy.service << 'EOF'
[Unit]
Description=Health Proxy
After=network.target

[Service]
ExecStart=/usr/bin/python3 /root/health-proxy.py
Restart=always
EnvironmentFile=/path/to/claudeclaw/.env

[Install]
WantedBy=multi-user.target
EOF

systemctl enable --now health-proxy.service
```

### ClaudeClaw .env

Required keys:
```env
WEBHOOK_SECRET=<your-secret>   # generate: openssl rand -hex 32
WEBHOOK_PORT=3100
```

### Test the pipeline

```bash
# Send a test payload directly to the proxy
curl -X POST http://YOUR_VPS_IP:3200 \
  -H "Content-Type: application/json" \
  -d '{"steps":[{"count":8000,"calories":320,"distance":6200}]}'
```

A message should arrive in Telegram within a few seconds.

---

## Known DataType fields (SDK 1.0.0-b2)

| Category | DataTypes constant | DataType fields |
|----------|--------------------|-----------------|
| Steps (aggregate) | `DataType.StepsType.TOTAL` | `.value: Long` (use `aggregateData()`) |
| Heart rate | `DataTypes.HEART_RATE` | `HeartRateType.HEART_RATE / MAX / MIN` |
| Blood pressure | `DataTypes.BLOOD_PRESSURE` | `BloodPressureType.SYSTOLIC / DIASTOLIC / PULSE` |
| Blood oxygen | `DataTypes.BLOOD_OXYGEN` | `BloodOxygenType.OXYGEN_SATURATION` |
| Sleep | `DataTypes.SLEEP` | `SleepType.DURATION / SLEEP_SCORE / SESSIONS` |
| Floors | `DataTypes.FLOORS_CLIMBED` | `FloorsClimbedType.FLOORS_CLIMBED` |
| Water | `DataTypes.WATER_INTAKE` | `WaterIntakeType.AMOUNT` |
| Nutrition | `DataTypes.NUTRITION` | `NutritionType.CALORIE / CARBOHYDRATE / PROTEIN / FAT_TOTAL` |
| Blood glucose | `DataTypes.BLOOD_GLUCOSE` | `BloodGlucoseType.GLUCOSE` |
| Weight | `DataTypes.WEIGHT` | `WeightType.WEIGHT / HEIGHT / BODY_MASS_INDEX / BODY_FAT` |
| Body temperature | `DataTypes.BODY_TEMPERATURE` | `BodyTemperatureType.TEMPERATURE` |
| Skin temperature | `DataTypes.SKIN_TEMPERATURE` | `SkinTemperatureType.TEMPERATURE` |
| Exercise | `DataTypes.EXERCISE` | `ExerciseType.SESSIONS` → `ExerciseSession` |

`ExerciseSession` fields: `.startTime`, `.endTime`, `.duration`, `.distance`, `.calories`, `.meanHeartRate`, `.maxHeartRate`

**Steps** use `aggregateData()`, all others use `readData()`.

---

## Adding a new data type

**1. Add permission** in `HealthDataManager.kt`:
```kotlin
Permission.of(DataTypes.NEW_TYPE, AccessType.READ),
```

**2. Add a query method**:
```kotlin
private suspend fun queryNewType(s: HealthDataStore, filter: LocalTimeFilter): JSONArray {
    val array = JSONArray()
    runCatching {
        val request = DataTypes.NEW_TYPE.readDataRequestBuilder
            .setLocalTimeFilter(filter).build()
        s.readData(request).dataList.forEach { point ->
            runCatching {
                array.put(JSONObject().apply {
                    put("value", point.getValue(DataType.NewType.FIELD))
                })
            }
        }
    }.onFailure { Log.w(TAG, "queryNewType failed: ${it.message}") }
    return array
}
```

**3. Call it in `collectAll()`**:
```kotlin
safePut("new_type", queryNewType(s, filter))
```

**4. Handle it in `health-proxy.py`** — add a formatting block in `format_prompt()`.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| App crashes on launch | Samsung Health must be installed and running |
| "No permissions" | Tap **Разрешения Samsung Health**, grant all in dialog |
| HTTP 502 from server | Check proxy logs: `journalctl -u health-proxy -n 30` |
| HTTP 401 from server | `WEBHOOK_SECRET` mismatch — check `EnvironmentFile` in service unit |
| No data returned | Enable developer mode in Samsung Health (tap version 10 times) |
| `NoClassDefFoundError: Parceler` | Add `id("kotlin-parcelize")` plugin to `app/build.gradle.kts` |
| Settings not saved | SharedPreferences are per-device — re-enter URL after clearing app data |

---

## Project structure

```
app/
  libs/                          ← place samsung-health-data-api-*.aar here
  src/main/java/com/artem/healthagent/
    Config.kt                    ← shared constants (work tags, window size)
    SettingsManager.kt           ← SharedPreferences: URL, schedule hours, sync stats
    SettingsActivity.kt          ← settings screen (URL input, test, schedule)
    HealthDataManager.kt         ← Samsung Health queries (13 data types)
    WebhookSender.kt             ← HTTP POST to health-proxy.py
    HealthSyncWorker.kt          ← WorkManager background worker
    SchedulerManager.kt          ← schedules syncs at user-configured hours
    BootReceiver.kt              ← restores schedule after reboot
    MainActivity.kt              ← main UI: log, send button, settings button
  src/main/res/
    layout/activity_main.xml     ← main screen layout
    layout/activity_settings.xml ← settings screen layout
    xml/network_security_config.xml  ← allows HTTP (user can enter any URL)
```
