package com.artem.healthagent

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.artem.healthagent.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding  = ActivitySettingsBinding.inflate(layoutInflater)
        settings = SettingsManager(this)
        setContentView(binding.root)
        supportActionBar?.title = "Настройки"

        loadCurrent()
        setupButtons()
    }

    private fun loadCurrent() {
        binding.etServerUrl.setText(settings.serverUrl)
        binding.etMorningHour.setText(settings.morningHour.toString())
        binding.etEveningHour.setText(settings.eveningHour.toString())
        binding.tvStats.text = settings.lastSyncDescription()
    }

    private fun setupButtons() {

        // Save URL
        binding.btnSaveUrl.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            val error = settings.validateUrl(url)
            if (error != null) {
                binding.tvUrlError.text = error
                binding.tvUrlError.visibility = View.VISIBLE
            } else {
                binding.tvUrlError.visibility = View.GONE
                settings.serverUrl = url
                Toast.makeText(this, "URL сохранён", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
            }
        }

        // Test connection
        binding.btnTestConnection.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            val error = settings.validateUrl(url)
            if (error != null) {
                binding.tvUrlError.text = error
                binding.tvUrlError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            binding.tvUrlError.visibility = View.GONE
            binding.btnTestConnection.isEnabled = false
            binding.btnTestConnection.text = "Проверка..."
            binding.tvTestResult.visibility = View.VISIBLE
            binding.tvTestResult.text = "Подключение к $url..."

            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { testConnection(url) }
                binding.tvTestResult.text  = result.message
                binding.tvTestResult.setTextColor(
                    getColor(if (result.ok) android.R.color.holo_green_light else android.R.color.holo_red_light)
                )
                binding.btnTestConnection.isEnabled = true
                binding.btnTestConnection.text = "Проверить"
            }
        }

        // Save schedule
        binding.btnSaveSchedule.setOnClickListener {
            val morning = binding.etMorningHour.text.toString().toIntOrNull()
            val evening = binding.etEveningHour.text.toString().toIntOrNull()
            if (morning == null || morning !in 0..23 || evening == null || evening !in 0..23) {
                Toast.makeText(this, "Час должен быть от 0 до 23", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            settings.morningHour = morning
            settings.eveningHour = evening
            SchedulerManager.scheduleDailySyncs(this)
            Toast.makeText(this, "Расписание: ${morning}:00 и ${evening}:00", Toast.LENGTH_SHORT).show()
        }

        // Reset stats
        binding.btnResetStats.setOnClickListener {
            settings.totalSyncs     = 0
            settings.lastSyncTime   = 0L
            settings.lastSyncStatus = ""
            binding.tvStats.text    = settings.lastSyncDescription()
            Toast.makeText(this, "Статистика сброшена", Toast.LENGTH_SHORT).show()
        }
    }

    private data class TestResult(val ok: Boolean, val message: String)

    private fun testConnection(rawUrl: String): TestResult {
        return try {
            // Try GET /health first (ClaudeClaw), then plain POST to root (health-proxy)
            val healthUrl = if (rawUrl.contains(":3100")) rawUrl.trimEnd('/') + "/health" else rawUrl
            val conn = URL(healthUrl).openConnection() as HttpURLConnection
            conn.requestMethod  = "GET"
            conn.connectTimeout = 5_000
            conn.readTimeout    = 5_000
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) TestResult(true, "✓ Соединение установлено (HTTP $code)")
            else TestResult(false, "✗ Сервер ответил HTTP $code")
        } catch (e: Exception) {
            TestResult(false, "✗ Ошибка: ${e.message}")
        }
    }
}
