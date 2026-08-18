package com.hpremote.agent

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.hpremote.agent.databinding.ActivityMainBinding
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager
    private val pairingCode = Random.nextInt(0, 1_000_000).toString().padStart(6, '0')

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startCaptureService(result.resultCode, result.data!!)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager = getSystemService(MediaProjectionManager::class.java)
        binding.textCode.text = pairingCode
        binding.editServerUrl.setText(defaultServerUrl())

        binding.btnStart.setOnClickListener {
            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
        binding.btnStop.setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
        }
        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        binding.textAccessibilityStatus.text =
            if (isAccessibilityServiceEnabled()) "접근성 서비스: 활성화됨"
            else "접근성 서비스: 비활성화됨 (아래 버튼으로 켜주세요)"
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val serverUrl = binding.editServerUrl.text.toString().trim()
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_SERVER_URL, serverUrl)
            putExtra(ScreenCaptureService.EXTRA_PAIR_CODE, pairingCode)
        }
        startForegroundService(intent)
    }

    private fun defaultServerUrl(): String = "ws://192.168.0.10:8080/ws"

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = "$packageName/${RemoteAccessibilityService::class.java.name}"
        return enabled.split(":").any { TextUtils.equals(it, target) }
    }
}
