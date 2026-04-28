package com.artem.healthagent

import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
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

    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        healthMgr = HealthDataManager(this)

        binding.tvSchedule.text = SchedulerManager.nextSyncDescription(this)

        // Connect to Samsung Health (synchronous in new SDK)
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
            appendLog("✗ Не удалось подключиться. Убедись что Samsung Health установлен и включён developer mode")
        }

        // "Send Now" button
        binding.btnSendNow.setOnClickListener {
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

    private suspend fun runManualSync() {
        appendLog("=== Ручная синхронизация ===")

        val hasPerms = withContext(Dispatchers.Default) { healthMgr.hasAllPermissions() }
        if (!hasPerms) {
            appendLog("✗ Нет разрешений — нажми 'Разрешения Samsung Health'")
            toast("Сначала выдай разрешения")
            return
        }

        appendLog("Сбор данных...")
        val data = withContext(Dispatchers.IO) { healthMgr.collectAll() }

        // Log what we got
        val keys = data.keys().asSequence().toList()
        if (keys.isEmpty()) {
            appendLog("(нет данных)")
        } else {
            keys.forEach { key ->
                val arr = runCatching { data.getJSONArray(key) }.getOrNull()
                if (arr != null && arr.length() > 0) appendLog("  $key: ${arr.length()} записей")
            }
        }

        appendLog("Отправка на ${Config.SERVER_URL}...")
        val result = withContext(Dispatchers.IO) { WebhookSender.send(data) }

        if (result.success) {
            appendLog("✓ Отправлено (HTTP ${result.statusCode})")
            binding.tvStatus.text = "Последняя синхронизация: ${time()}"
            binding.tvSchedule.text = SchedulerManager.nextSyncDescription(this)
        } else {
            val msg = result.error.ifEmpty { "HTTP ${result.statusCode}" }
            appendLog("✗ Ошибка: $msg")
            toast("Ошибка: $msg")
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
