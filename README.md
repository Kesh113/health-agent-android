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

---

## Сборка и установка

### Что нужно

- Android Studio (любая свежая версия)
- Android-телефон Samsung с приложением Samsung Health
- VPS с запущенным [ClaudeClaw](https://github.com/anthropics/claudeclaw) и Telegram-ботом

---

### Шаг 1 — Скачай проект

```bash
git clone https://github.com/Kesh113/health-agent-android.git
cd health-agent-android
```

---

### Шаг 2 — Скачай Samsung Health Data SDK

Официальный портал Samsung недоступен из России, поэтому берём AAR напрямую:

```bash
# Перейди в папку проекта и выполни:
curl -L -o app/libs/samsung-health-data-api-1.0.0-b2.aar \
  https://raw.githubusercontent.com/cjae/rn-samsung-health-data-api/main/android/libs/samsung-health-data-api-1.0.0-b2.aar
```

Или скачай вручную по ссылке и положи файл в `app/libs/`.

---

### Шаг 3 — Включи Developer Mode в Samsung Health

Без этого приложение не сможет читать данные.

1. Открой **Samsung Health** на телефоне
2. Перейди в **Настройки → О приложении** (или «О Samsung Health»)
3. Нажми на номер версии **10 раз подряд**
4. Появится сообщение «Developer mode enabled»
5. Вернись в настройки → найди **Developer Mode for Data Read** и включи

---

### Шаг 4 — Открой в Android Studio

1. Запусти Android Studio
2. **File → Open** → выбери папку `health-agent-android`
3. Дождись **Gradle Sync** (первый раз загружает зависимости, 2–5 минут)
4. Убедись что в нижней панели нет красных ошибок

---

### Шаг 5 — Установи на телефон

1. Подключи телефон к компьютеру по USB
2. На телефоне разреши **USB Debugging** (Настройки → О телефоне → тапни «Номер сборки» 7 раз → Параметры разработчика → USB-отладка)
3. В Android Studio нажми **Run ▶** (или Shift+F10)
4. Выбери свой телефон из списка → приложение установится и запустится

---

### Шаг 6 — Настрой сервер (на VPS)

Скопируй `health-proxy.py` на сервер и создай systemd-сервис:

```bash
# 1. Создай сервис
cat > /etc/systemd/system/health-proxy.service << 'EOF'
[Unit]
Description=Health Proxy
After=network.target

[Service]
EnvironmentFile=/path/to/claudeclaw/.env
ExecStart=/usr/bin/python3 /root/health-proxy.py
Restart=always

[Install]
WantedBy=multi-user.target
EOF

# 2. Запусти
systemctl enable --now health-proxy

# 3. Проверь что работает
curl http://localhost:3200/
# → {"status":"ok"}
```

В `/path/to/claudeclaw/.env` должно быть:
```env
WEBHOOK_SECRET=<сгенерируй: openssl rand -hex 32>
WEBHOOK_PORT=3100
```

---

### Шаг 7 — Настрой приложение

1. При первом запуске автоматически откроется экран **Настройки**
2. Введи URL прокси: `http://ВАШ_IP:3200`
3. Нажми **Проверить** — должно появиться «✓ Соединение установлено»
4. Нажми **Сохранить**
5. Вернись на главный экран → нажми **Разрешения Samsung Health** → выдай все доступные

---

### Шаг 8 — Тест

Нажми **Отправить сейчас**. В логе должно появиться:

```
Сбор данных...
  steps: 1 записей
  heart_rate: N записей
  sleep: 1 записей
  ...
Отправка на http://ВАШ_IP:3200...
✓ Отправлено (HTTP 200)
```

После этого в Telegram придёт сообщение от AI-агента с анализом данных.

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
| Нет данных в логе | Включи Developer Mode в Samsung Health (Шаг 3) |
| Приложение не подключается к Samsung Health | Samsung Health должен быть установлен и хотя бы раз открыт |
| HTTP 502 | `journalctl -u health-proxy -n 30` — смотри логи прокси |
| HTTP 401 | Не совпадает `WEBHOOK_SECRET` — проверь `EnvironmentFile` в сервисе |
| «✓ Соединение», но нет сообщения в Telegram | ClaudeClaw не запущен: `systemctl status claudeclaw-*` |
| `NoClassDefFoundError: Parceler` | Добавь `id("kotlin-parcelize")` в `app/build.gradle.kts` |
