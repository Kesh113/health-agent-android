package com.artem.healthagent

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.artem.healthagent.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var healthMgr: HealthDataManager
    private lateinit var settings: SettingsManager

    private val logLines = ArrayDeque<String>()

    // Re-read settings and refresh UI after returning from SettingsActivity
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        refreshServerBadge()
        binding.tvSchedule.text = SchedulerManager.nextSyncDescription(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding  = ActivityMainBinding.inflate(layoutInflater)
        settings = SettingsManager(this)
        setContentView(binding.root)

        healthMgr = HealthDataManager(this)

        refreshServerBadge()
        binding.tvSchedule.text = SchedulerManager.nextSyncDescription(this)

        // Auto-open settings on first launch
        if (!settings.isConfigured) {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        // Connect to Samsung Health
        val connected = healthMgr.connect(this)
        if (connected) {
            binding.viewDot.setBackgroundResource(R.drawable.dot_green)
            binding.tvConnection.text = "Samsung Health: подключено"
            lifecycleScope.launch {
                val hasPerms = healthMgr.hasAllPermissions()
                appendLog(if (hasPerms) "✓ Все разрешения получены" else "⚠ Нет разрешений — нажми кнопку ниже")
            }
        } else {
            binding.tvConnection.text = "Samsung Health: ошибка подключения"
            appendLog("✗ Samsung Health не найден. Убедись что приложение установлено и включён developer mode")
        }

        // Settings button
        binding.btnSettings.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        // Send Now button
        binding.btnSendNow.setOnClickListener {
            if (!settings.isConfigured) {
                toast("Сначала настрой URL сервера")
                settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }
            binding.btnSendNow.isEnabled = false
            binding.btnSendNow.text = "Отправка..."
            lifecycleScope.launch {
                runManualSync()
                binding.btnSendNow.isEnabled = true
                binding.btnSendNow.text = "Отправить сейчас"
            }
        }

        // Permissions button
        binding.btnPermissions.setOnClickListener {
            lifecycleScope.launch {
                val granted = healthMgr.requestPermissions(this@MainActivity)
                appendLog("Разрешений получено: ${granted.size}")
            }
        }

        // Observe WorkManager status
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(Config.WORK_TAG_MANUAL)
            .observe(this) { infos ->
                infos?.firstOrNull()?.let { info ->
                    when (info.state) {
                        WorkInfo.State.RUNNING   -> appendLog("Sync запущен...")
                        WorkInfo.State.SUCCEEDED -> {
                            appendLog("✓ Данные отправлены (фоновый sync)")
                            binding.tvStatus.text = "Последняя синхронизация: ${time()}"
                            refreshServerBadge()
                        }
                        WorkInfo.State.FAILED -> {
                            val err = info.outputData.getString("error") ?: ""
                            appendLog("✗ Ошибка: $err")
                        }
                        else -> {}
                    }
                }
            }
    }

    private fun refreshServerBadge() {
        val url = settings.serverUrl
        if (url.isBlank()) {
            binding.tvServerUrl.text = "⚠ Сервер не настроен — нажми Настройки"
            binding.tvServerUrl.setTextColor(getColor(android.R.color.holo_orange_light))
        } else {
            val host = url.removePrefix("https://").removePrefix("http://").trimEnd('/')
            val syncDesc = settings.lastSyncDescription()
            binding.tvServerUrl.text = "⇒ $host  ·  $syncDesc"
            binding.tvServerUrl.setTextColor(0xFF666666.toInt())
        }
    }

    private suspend fun runManualSync() {
        appendLog("=== Ручная синхронизация ===")
        try {
            val hasPerms = healthMgr.hasAllPermissions()
            if (!hasPerms) {
                appendLog("✗ Нет разрешений — нажми 'Разрешения Samsung Health'")
                toast("Сначала выдай разрешения")
                return
            }

            appendLog("Сбор данных...")
            val data = healthMgr.collectAll()

            val keys = data.keys().asSequence().toList()
            if (keys.isEmpty()) {
                appendLog("(нет данных)")
            } else {
                keys.forEach { key ->
                    val arr = runCatching { data.getJSONArray(key) }.getOrNull()
                    if (arr != null && arr.length() > 0) appendLog("  $key: ${arr.length()} записей")
                    else if (arr != null)                appendLog("  $key: пусто")
                }
            }

            val serverUrl = settings.serverUrl
            appendLog("Отправка на $serverUrl...")
            val result = withContext(Dispatchers.IO) { WebhookSender.send(data, serverUrl) }

            if (result.success) {
                settings.recordSync(true, result.statusCode)
                appendLog("✓ Отправлено (HTTP ${result.statusCode})")
                binding.tvStatus.text = "Последняя синхронизация: ${time()}"
                binding.tvSchedule.text = SchedulerManager.nextSyncDescription(this)
                refreshServerBadge()
            } else {
                settings.recordSync(false, result.statusCode)
                val msg = result.error.ifEmpty { "HTTP ${result.statusCode}" }
                appendLog("✗ Ошибка отправки: $msg")
                toast("Ошибка: $msg")
            }
        } catch (e: Exception) {
            appendLog("✗ CRASH: ${e.javaClass.simpleName}: ${e.message}")
            toast("Ошибка: ${e.message}")
        }
    }

    private fun appendLog(line: String) {
        val msg = "[${time()}] $line"
        logLines.addFirst(msg)
        if (logLines.size > 150) logLines.removeLast()
        runOnUiThread {
            binding.tvLog.text = logLines.joinToString("\n")
        }
    }

    private fun time() = DateFormat.format("HH:mm:ss", Date()).toString()

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        healthMgr.disconnect()
    }
}
