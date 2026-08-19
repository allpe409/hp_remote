package com.hpremote.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.hpremote.agent.databinding.ActivityMainBinding
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREF_PAIRING_CODE = "pairing_code"
        private const val PREF_SERVER_URL = "server_url"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager
    private val prefs by lazy { getSharedPreferences("hp_remote", MODE_PRIVATE) }

    // Generated once per install and reused forever after, so the same code
    // (and the controller page's remembered code) keep working without retyping.
    private val pairingCode: String by lazy {
        prefs.getString(PREF_PAIRING_CODE, null) ?: run {
            val code = Random.nextInt(0, 1_000_000).toString().padStart(6, '0')
            prefs.edit().putString(PREF_PAIRING_CODE, code).apply()
            code
        }
    }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startCaptureService(result.resultCode, result.data!!)
            }
        }

    // Result is ignored on purpose: whether the user grants mic access or not,
    // we still proceed to the screen-share prompt (audio is just skipped if denied).
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startCaptureFlow() }

    // Also ignored on purpose - whether or not the user allows this, we continue
    // to the mic/screen-share prompts either way.
    private val batteryOptLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { requestMicThenCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager = getSystemService(MediaProjectionManager::class.java)
        binding.textCode.text = pairingCode
        binding.editServerUrl.setText(prefs.getString(PREF_SERVER_URL, null) ?: defaultServerUrl())

        binding.btnStart.setOnClickListener { startCaptureFlow() }
        binding.btnStop.setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
        }
        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Ask for battery-optimization exemption, mic, then screen-capture consent
        // as soon as the app opens, so the only taps left are Android's own system
        // consent dialogs (those can't be skipped - they're an OS-enforced security
        // boundary, not something this app controls).
        if (savedInstanceState == null) requestBatteryExemptionThenMicThenCapture()
    }

    private fun requestBatteryExemptionThenMicThenCapture() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            // Without this, some manufacturers' aggressive battery savers can kill
            // the foreground service in the background even though a foreground
            // service is normally exempt - this keeps screen sharing alive until
            // the user actually taps "화면 공유 중지", not whenever the screen locks.
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            batteryOptLauncher.launch(intent)
        } else {
            requestMicThenCapture()
        }
    }

    private fun requestMicThenCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startCaptureFlow()
        }
    }

    private fun startCaptureFlow() {
        prefs.edit().putString(PREF_SERVER_URL, binding.editServerUrl.text.toString().trim()).apply()
        // On Android 14+ this skips the "entire screen vs. one app" picker and goes
        // straight to the plain allow/cancel consent screen for the whole display.
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        captureLauncher.launch(captureIntent)
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

    private fun defaultServerUrl(): String = "wss://hp-remote-server.onrender.com/ws"

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = "$packageName/${RemoteAccessibilityService::class.java.name}"
        return enabled.split(":").any { TextUtils.equals(it, target) }
    }
}
