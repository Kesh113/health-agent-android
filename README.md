# Health Agent — Android App

Читает данные Samsung Health и отправляет их на self-hosted AI-агент по расписанию (09:00 и 21:00). Адрес сервера вводится прямо в приложении — никаких секретов в коде.

---

## Как это работает

```
Samsung Health → Health Agent app → health-proxy.py (3200) → ClaudeClaw webhook (3100) → Telegram
```

| Компонент | Роль |
|-----------|------|
| Android app | Читает Samsung Health, отправляет JSON на прокси |
| `health-proxy.py` | Форматирует данные, подписывает HMAC, шлёт в ClaudeClaw |
| ClaudeClaw | Запускает AI-агента, отвечает в Telegram |

---

## Данные (13 типов)

Steps · Heart Rate · Blood Pressure · SpO₂ · Sleep · Floors Climbed · Water · Nutrition · Blood Glucose · Weight/BMI/Body Fat · Body Temperature · Skin Temperature · Exercise Sessions

Каждый запрос завёрнут в `runCatching` — ошибка одного типа не ломает остальные.

---

## Сборка

### 1. Samsung Health Data SDK (AAR)

Положи файл в `app/libs/`:
```
# Скачать (без регистрации):
https://raw.githubusercontent.com/cjae/rn-samsung-health-data-api/main/android/libs/samsung-health-data-api-1.0.0-b2.aar
```

### 2. Developer Mode в Samsung Health

**Настройки → О приложении → тапни версию 10 раз** → включи "Developer Mode for Data Read".

### 3. Android Studio

```
File → Open → папка android-health-app → Gradle Sync → Run
```

### 4. Первый запуск

1. Приложение сразу откроет **Настройки** — введи URL прокси и нажми Сохранить
2. Нажми **Разрешения Samsung Health** и выдай доступ
3. Нажми **Отправить сейчас** — в логе должны появиться данные и HTTP 200

---

## Сервер

### health-proxy.py

Принимает JSON от приложения, форматирует в текст, подписывает HMAC-SHA256, шлёт в ClaudeClaw.

```bash
cat > /etc/systemd/system/health-proxy.service << 'EOF'
[Unit]
Description=Health Proxy
After=network.target

[Service]
ExecStart=/usr/bin/python3 /root/health-proxy.py
Restart=always
EnvironmentFile=/path/to/claudeclaw/.env   # берёт WEBHOOK_SECRET отсюда

[Install]
WantedBy=multi-user.target
EOF
systemctl enable --now health-proxy.service
```

### ClaudeClaw .env

```env
WEBHOOK_SECRET=<openssl rand -hex 32>
WEBHOOK_PORT=3100
```

---

## Структура проекта

```
app/libs/                        ← сюда положи *.aar
app/src/main/java/.../
  SettingsManager.kt             ← хранит URL и расписание в SharedPreferences
  SettingsActivity.kt            ← экран настроек
  HealthDataManager.kt           ← запросы к Samsung Health (13 типов)
  WebhookSender.kt               ← HTTP POST на прокси
  HealthSyncWorker.kt            ← фоновый WorkManager worker
  SchedulerManager.kt            ← расписание 2× в день
  BootReceiver.kt                ← восстанавливает расписание после перезагрузки
  MainActivity.kt                ← UI
```

---

## Troubleshooting

| Проблема | Решение |
|----------|---------|
| Нет данных | Включи Developer Mode в Samsung Health |
| HTTP 502 | `journalctl -u health-proxy -n 30` — смотри логи прокси |
| HTTP 401 | Не совпадает `WEBHOOK_SECRET` — проверь `EnvironmentFile` в сервисе |
| `NoClassDefFoundError: Parceler` | Добавь `id("kotlin-parcelize")` в `app/build.gradle.kts` |
| Краш при подключении | Samsung Health должен быть установлен и запущен |
