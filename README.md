# Health Agent — Android App

Приложение читает все доступные данные из Samsung Health и отправляет на VPS в 09:00 и 21:00.

## Что использует

**Samsung Health Data SDK** (новый, без Client ID)
- Работает в developer mode без регистрации партнёра
- Suspend-функции, нет колбэков
- Старый Samsung Health SDK for Android (с Client ID) — депрекейтед с июля 2025

## Настройка

### 1. Samsung Health SDK — скачать AAR

Открой на телефоне или ПК:
https://developer.samsung.com/health/data/overview.html

Скачай SDK → распакуй → найди файл `samsung-health-data-api-*.aar`

Положи его в папку проекта:
```
app/libs/samsung-health-data-api-1.x.x.aar
```

### 2. Gradle

В `app/build.gradle.kts` уже есть:
```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
```
Файл подхватится автоматически.

### 3. Android Studio

1. File → Open → папка `android-health-app`
2. Gradle sync
3. Build → Make Project

### 4. Установка

```bash
./gradlew installDebug
```

### 5. Первый запуск

1. Открой приложение
2. Нажми **"Разрешения Samsung Health"** — выдай все
3. Нажми **"Отправить сейчас"** для теста — посмотри лог

### 6. Samsung Health Developer Mode

Настройки Samsung Health → О Samsung Health → тапни на версию 10 раз.
Включи "Developer Mode for Data Read".

## Обновить health-proxy.py на сервере

```bash
cp /root/my-assistant/claudeclaw/groups/telegram_personal/android-health-app/health-proxy.py /root/health-proxy.py
systemctl restart health-proxy
```

## Данные которые собирает приложение

| Тип данных | Период |
|---|---|
| Шаги (кол-во, калории, дистанция) | 24ч |
| Пульс (все измерения) | 24ч |
| Сон + стадии (глубокий/REM/лёгкий) | 24ч |
| SpO2 | 24ч |
| Стресс | 24ч |
| Тренировки (тип, длительность, калории) | 24ч |
| Давление | 24ч |
| Вес / ИМТ | 24ч |
| Этажи | 24ч |
| Вода | 24ч |
| Температура тела | 24ч |

## Примечание по property-именам

Exact property names (`entry.heartRate`, `entry.bloodOxygenSaturation`, etc.) зависят от версии SDK.
Если после импорта AAR Android Studio показывает ошибки — смотри autocomplete по `entry.` и поправь имена.
Все queries обёрнуты в `runCatching` — ошибки в одном типе данных не ломают остальные.
