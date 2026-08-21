package com.hpremote.clone

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hpremote.clone.databinding.ActivityReceiveBinding
import com.hpremote.clone.transfer.NetworkUtils
import com.hpremote.clone.transfer.TransferServer
import kotlin.random.Random

class ReceiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiveBinding
    private val pin: String = Random.nextInt(0, 1_000_000).toString().padStart(6, '0')
    private var server: TransferServer? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { requestSmsRoleThenStart() }

    // Whether the user grants the default-SMS role or not, we still start the
    // server - SMS import is simply skipped per-record if we don't hold it.
    private val smsRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startServer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.textPin.text = pin
        refreshIp()

        binding.btnStartServer.setOnClickListener {
            binding.btnStartServer.isEnabled = false
            permissionLauncher.launch(PermissionsHelper.receivePermissions())
        }
    }

    override fun onResume() {
        super.onResume()
        refreshIp()
    }

    private fun refreshIp() {
        binding.textIp.text = NetworkUtils.getLocalIpAddress() ?: "Wi-Fi에 연결되어 있지 않습니다"
    }

    private fun requestSmsRoleThenStart() {
        val roleIntent = PermissionsHelper.createSmsRoleIntent(this)
        if (roleIntent != null) {
            smsRoleLauncher.launch(roleIntent)
        } else {
            startServer()
        }
    }

    private fun startServer() {
        binding.textStatusReceive.text = "대기 중..."
        server = TransferServer(
            context = applicationContext,
            pin = pin,
            onProgress = { log, percent ->
                runOnUiThread {
                    binding.textStatusReceive.text = log
                    binding.progressReceive.progress = percent
                }
            },
            onDone = { _, message ->
                runOnUiThread {
                    binding.textStatusReceive.append("\n$message")
                    binding.btnStartServer.isEnabled = true
                }
            }
        ).also { it.start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }
}
