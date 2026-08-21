package com.hpremote.clone

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hpremote.clone.databinding.ActivityReceiveBinding
import com.hpremote.clone.transfer.ConnectionMethod
import com.hpremote.clone.transfer.DEFAULT_RELAY_URL
import com.hpremote.clone.transfer.DuplexAcceptor
import com.hpremote.clone.transfer.LocalServerAcceptor
import com.hpremote.clone.transfer.NetworkUtils
import com.hpremote.clone.transfer.TRANSFER_PORT
import com.hpremote.clone.transfer.TransferServer
import com.hpremote.clone.transfer.categoryLabel
import com.hpremote.clone.transfer.relay.RelayServerAcceptor
import com.hpremote.clone.wifidirect.WifiDirectHelper
import kotlin.random.Random

class ReceiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiveBinding
    private val pin: String = Random.nextInt(0, 1_000_000).toString().padStart(6, '0')
    private var server: TransferServer? = null
    private var selectedMethod = ConnectionMethod.LOCAL_NETWORK
    private val wifiDirect by lazy { WifiDirectHelper(this) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { requestWifiDirectPermsIfNeeded() }

    private val wifiDirectPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { requestSmsRoleThenStart() }

    // Whether the user grants the default-SMS role or not, we still start the
    // server - SMS import is simply skipped per-record if we don't hold it.
    private val smsRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startServerForMethod() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.textPin.text = pin
        binding.editRelayUrlReceive.setText(DEFAULT_RELAY_URL)
        refreshIp()

        binding.radioMethodReceive.setOnCheckedChangeListener { _, checkedId ->
            selectedMethod = when (checkedId) {
                binding.radioWifiDirectReceive.id -> ConnectionMethod.WIFI_DIRECT
                binding.radioRelayReceive.id -> ConnectionMethod.RELAY
                else -> ConnectionMethod.LOCAL_NETWORK
            }
            binding.layoutLocalReceive.visibility = if (selectedMethod == ConnectionMethod.LOCAL_NETWORK) View.VISIBLE else View.GONE
            binding.layoutWifiDirectReceive.visibility = if (selectedMethod == ConnectionMethod.WIFI_DIRECT) View.VISIBLE else View.GONE
            binding.layoutRelayReceive.visibility = if (selectedMethod == ConnectionMethod.RELAY) View.VISIBLE else View.GONE
            if (checkedId == binding.radioHotspotReceive.id) {
                binding.textLocalHintReceive.text = "한쪽 폰에서 개인 핫스팟을 켜고 다른 폰이 그 핫스팟에 접속하세요"
            } else {
                binding.textLocalHintReceive.text = "두 폰이 같은 공유기 Wi-Fi에 연결되어 있어야 합니다"
            }
        }

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

    private fun requestWifiDirectPermsIfNeeded() {
        if (selectedMethod == ConnectionMethod.WIFI_DIRECT) {
            wifiDirectPermissionLauncher.launch(PermissionsHelper.wifiDirectPermissions())
        } else {
            requestSmsRoleThenStart()
        }
    }

    private fun requestSmsRoleThenStart() {
        val roleIntent = PermissionsHelper.createSmsRoleIntent(this)
        if (roleIntent != null) {
            smsRoleLauncher.launch(roleIntent)
        } else {
            startServerForMethod()
        }
    }

    private fun startServerForMethod() {
        binding.textStatusReceive.text = "대기 중..."
        when (selectedMethod) {
            ConnectionMethod.LOCAL_NETWORK -> startServer(LocalServerAcceptor(TRANSFER_PORT))
            ConnectionMethod.RELAY -> {
                val url = binding.editRelayUrlReceive.text.toString().trim()
                startServer(RelayServerAcceptor(url, pin))
            }
            ConnectionMethod.WIFI_DIRECT -> {
                wifiDirect.register()
                binding.textWifiDirectStatusReceive.text = "그룹 생성 중..."
                wifiDirect.createGroup(
                    onReady = {
                        runOnUiThread {
                            binding.textWifiDirectStatusReceive.text = "연결 대기 중"
                            startServer(LocalServerAcceptor(TRANSFER_PORT))
                        }
                    },
                    onFailed = { message ->
                        runOnUiThread {
                            binding.textWifiDirectStatusReceive.text = message
                            binding.btnStartServer.isEnabled = true
                        }
                    }
                )
            }
        }
    }

    private fun startServer(acceptor: DuplexAcceptor) {
        server = TransferServer(
            context = applicationContext,
            pin = pin,
            acceptor = acceptor,
            onProgress = { p ->
                runOnUiThread {
                    binding.textCategoryIndexReceive.text =
                        "${p.categoryIndex}/${p.totalCategories} 처리 중: ${categoryLabel(p.category)}"
                    binding.progressCategoryReceive.progress = p.categoryPercent
                    binding.progressOverallReceive.progress = p.overallPercent
                    binding.textStatusReceive.text = p.message
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
        if (selectedMethod == ConnectionMethod.WIFI_DIRECT) {
            wifiDirect.removeGroup()
            wifiDirect.unregister()
        }
    }
}
