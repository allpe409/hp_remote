package com.hpremote.clone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hpremote.clone.databinding.ActivitySendBinding
import com.hpremote.clone.transfer.Category
import com.hpremote.clone.transfer.ConnectionMethod
import com.hpremote.clone.transfer.DEFAULT_RELAY_URL
import com.hpremote.clone.transfer.DuplexConnector
import com.hpremote.clone.transfer.LocalConnector
import com.hpremote.clone.transfer.TRANSFER_PORT
import com.hpremote.clone.transfer.TimeEstimate
import com.hpremote.clone.transfer.TransferClient
import com.hpremote.clone.transfer.categoryLabel
import com.hpremote.clone.transfer.relay.RelayConnector
import com.hpremote.clone.wifidirect.WifiDirectHelper
import com.hpremote.clone.wifidirect.WifiDirectPeer

class SendActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySendBinding
    private var selectedMethod = ConnectionMethod.LOCAL_NETWORK
    private val wifiDirect by lazy { WifiDirectHelper(this) }
    private var discoveredPeers: List<WifiDirectPeer> = emptyList()
    private var wifiDirectHost: String? = null
    private var snsBackupTreeUri: Uri? = null

    private val sendPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { showEstimateThenStart() }

    private val snsFolderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                snsBackupTreeUri = uri
                binding.textSnsFolderSend.text = uri.lastPathSegment ?: uri.toString()
            }
        }

    private val wifiDirectPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            wifiDirect.register()
            binding.textWifiDirectStatusSend.text = "검색 중..."
            wifiDirect.discoverPeers()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySendBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.editRelayUrlSend.setText(DEFAULT_RELAY_URL)

        val peerAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        binding.listPeers.adapter = peerAdapter
        wifiDirect.onPeersChanged = { peers ->
            runOnUiThread {
                discoveredPeers = peers
                peerAdapter.clear()
                peerAdapter.addAll(peers.map { it.name })
                binding.textWifiDirectStatusSend.text = if (peers.isEmpty()) "주변에서 기기를 찾지 못했습니다" else "기기를 선택하세요"
            }
        }
        binding.listPeers.setOnItemClickListener { _, _, position, _ ->
            val peer = discoveredPeers.getOrNull(position) ?: return@setOnItemClickListener
            binding.textWifiDirectStatusSend.text = "${peer.name}에 연결 중..."
            wifiDirect.connectToPeer(
                peer.address,
                onConnected = { ip ->
                    runOnUiThread {
                        wifiDirectHost = ip
                        binding.textWifiDirectStatusSend.text = "${peer.name}에 연결됨"
                    }
                },
                onFailed = { message ->
                    runOnUiThread { binding.textWifiDirectStatusSend.text = message }
                }
            )
        }
        binding.btnScanPeers.setOnClickListener {
            wifiDirectHost = null
            wifiDirectPermissionLauncher.launch(PermissionsHelper.wifiDirectPermissions())
        }

        binding.cbSnsBackup.setOnCheckedChangeListener { _, checked ->
            binding.layoutSnsBackupSend.visibility = if (checked) View.VISIBLE else View.GONE
        }
        binding.btnPickSnsFolder.setOnClickListener {
            snsFolderPicker.launch(null)
        }

        binding.radioMethodSend.setOnCheckedChangeListener { _, checkedId ->
            selectedMethod = when (checkedId) {
                binding.radioWifiDirectSend.id -> ConnectionMethod.WIFI_DIRECT
                binding.radioRelaySend.id -> ConnectionMethod.RELAY
                else -> ConnectionMethod.LOCAL_NETWORK
            }
            binding.layoutLocalSend.visibility = if (selectedMethod == ConnectionMethod.LOCAL_NETWORK) View.VISIBLE else View.GONE
            binding.layoutWifiDirectSend.visibility = if (selectedMethod == ConnectionMethod.WIFI_DIRECT) View.VISIBLE else View.GONE
            binding.layoutRelaySend.visibility = if (selectedMethod == ConnectionMethod.RELAY) View.VISIBLE else View.GONE
            if (checkedId == binding.radioHotspotSend.id) {
                binding.textLocalHintSend.text = "한쪽 폰에서 개인 핫스팟을 켜고 다른 폰이 그 핫스팟에 접속하세요"
            } else {
                binding.textLocalHintSend.text = "두 폰이 같은 공유기 Wi-Fi에 연결되어 있어야 합니다"
            }
        }

        binding.btnConnectSend.setOnClickListener {
            val pin = binding.editPin.text.toString().trim()
            if (pin.length != 6) {
                binding.textStatusSend.text = "6자리 PIN을 입력하세요"
                return@setOnClickListener
            }
            if (selectedMethod == ConnectionMethod.LOCAL_NETWORK && binding.editHost.text.toString().trim().isEmpty()) {
                binding.textStatusSend.text = "새 폰의 IP를 입력하세요"
                return@setOnClickListener
            }
            if (selectedMethod == ConnectionMethod.WIFI_DIRECT && wifiDirectHost == null) {
                binding.textStatusSend.text = "먼저 주변 기기를 검색해 연결하세요"
                return@setOnClickListener
            }
            if (selectedMethod == ConnectionMethod.RELAY && binding.editRelayUrlSend.text.toString().trim().isEmpty()) {
                binding.textStatusSend.text = "릴레이 서버 주소를 입력하세요"
                return@setOnClickListener
            }
            if (selectedCategories().isEmpty()) {
                binding.textStatusSend.text = "보낼 데이터를 하나 이상 선택하세요"
                return@setOnClickListener
            }
            if (binding.cbSnsBackup.isChecked && snsBackupTreeUri == null) {
                binding.textStatusSend.text = "SNS 백업 파일이 저장된 폴더를 먼저 선택하세요"
                return@setOnClickListener
            }
            binding.btnConnectSend.isEnabled = false
            binding.textStatusSend.text = ""
            binding.textCategoryIndexSend.text = ""
            binding.progressCategorySend.progress = 0
            binding.progressOverallSend.progress = 0
            sendPermissionLauncher.launch(PermissionsHelper.sendPermissions())
        }
    }

    private fun selectedCategories(): Set<Category> {
        val result = mutableSetOf<Category>()
        if (binding.cbContacts.isChecked) result += Category.CONTACTS
        if (binding.cbCallLog.isChecked) result += Category.CALL_LOG
        if (binding.cbCalendar.isChecked) result += Category.CALENDAR
        if (binding.cbSms.isChecked) result += Category.SMS
        if (binding.cbApps.isChecked) result += Category.APP_LIST
        if (binding.cbMedia.isChecked) {
            result += Category.PHOTO
            result += Category.VIDEO
        }
        if (binding.cbSnsBackup.isChecked) result += Category.SNS_BACKUP
        return result
    }

    private fun buildConnector(): DuplexConnector? {
        val pin = binding.editPin.text.toString().trim()
        return when (selectedMethod) {
            ConnectionMethod.LOCAL_NETWORK -> LocalConnector(binding.editHost.text.toString().trim(), TRANSFER_PORT)
            ConnectionMethod.WIFI_DIRECT -> wifiDirectHost?.let { LocalConnector(it, TRANSFER_PORT) }
            ConnectionMethod.RELAY -> RelayConnector(binding.editRelayUrlSend.text.toString().trim(), pin)
        }
    }

    // Before actually connecting, size up each selected category (fastest-first order)
    // and let the user see per-category / total estimated time before committing.
    private fun showEstimateThenStart() {
        val ordered = Category.ORDERED.filter { it in selectedCategories() }
        binding.textStatusSend.text = "예상 시간 계산 중..."
        Thread {
            val estimates = ordered.map { TimeEstimate.estimate(applicationContext, it, snsBackupTreeUri) }
            val totalMs = estimates.sumOf { it.estimatedMs }
            val summary = buildString {
                estimates.forEach { e ->
                    append("${categoryLabel(e.category)}: ${e.count}개 (약 ${TimeEstimate.formatDuration(e.estimatedMs)})\n")
                }
                append("\n총 예상 시간: 약 ${TimeEstimate.formatDuration(totalMs)}")
                append("\n(실제 소요 시간은 기기·네트워크 속도에 따라 달라질 수 있습니다)")
            }
            runOnUiThread {
                binding.textStatusSend.text = ""
                AlertDialog.Builder(this)
                    .setTitle("전송 순서 및 예상 시간")
                    .setMessage(summary)
                    .setPositiveButton("전송 시작") { _, _ -> startTransfer() }
                    .setNegativeButton("취소") { _, _ -> binding.btnConnectSend.isEnabled = true }
                    .setOnCancelListener { binding.btnConnectSend.isEnabled = true }
                    .show()
            }
        }.start()
    }

    private fun startTransfer() {
        val connector = buildConnector()
        if (connector == null) {
            binding.textStatusSend.text = "연결 정보가 없습니다. 다시 시도하세요"
            binding.btnConnectSend.isEnabled = true
            return
        }
        val pin = binding.editPin.text.toString().trim()
        val categories = selectedCategories()

        TransferClient(
            context = applicationContext,
            connector = connector,
            pin = pin,
            categories = categories,
            snsBackupTreeUri = snsBackupTreeUri,
            onProgress = { p ->
                runOnUiThread {
                    binding.textCategoryIndexSend.text =
                        "${p.categoryIndex}/${p.totalCategories} 처리 중: ${categoryLabel(p.category)}"
                    binding.progressCategorySend.progress = p.categoryPercent
                    binding.progressOverallSend.progress = p.overallPercent
                    binding.textStatusSend.text = p.message
                }
            },
            onDone = { _, message ->
                runOnUiThread {
                    binding.textStatusSend.text = message
                    binding.btnConnectSend.isEnabled = true
                }
            }
        ).start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (selectedMethod == ConnectionMethod.WIFI_DIRECT) {
            wifiDirect.removeGroup()
            wifiDirect.unregister()
        }
    }
}
