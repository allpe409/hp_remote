package com.hpremote.clone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hpremote.clone.data.AppEntry
import com.hpremote.clone.data.AppListExporter
import com.hpremote.clone.data.CalendarExporter
import com.hpremote.clone.data.CallLogExporter
import com.hpremote.clone.data.ContactsExporter
import com.hpremote.clone.data.MediaExporter
import com.hpremote.clone.data.SmsExporter
import com.hpremote.clone.databinding.ActivitySendBinding
import com.hpremote.clone.transfer.Category
import com.hpremote.clone.transfer.ConnectionMethod
import com.hpremote.clone.transfer.DEFAULT_RELAY_URL
import com.hpremote.clone.transfer.DuplexConnector
import com.hpremote.clone.transfer.LocalConnector
import com.hpremote.clone.transfer.SortOrder
import com.hpremote.clone.transfer.TRANSFER_PORT
import com.hpremote.clone.transfer.TimeEstimate
import com.hpremote.clone.transfer.TransferClient
import com.hpremote.clone.transfer.TransferUnit
import com.hpremote.clone.transfer.UnitEstimate
import com.hpremote.clone.transfer.relay.RelayConnector
import com.hpremote.clone.wifidirect.WifiDirectHelper
import com.hpremote.clone.wifidirect.WifiDirectPeer

private data class KnownCategoryInfo(val category: Category, val checkbox: CheckBox, val label: String, val count: Int, val totalBytes: Long)

class SendActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySendBinding
    private var selectedMethod = ConnectionMethod.LOCAL_NETWORK
    private val wifiDirect by lazy { WifiDirectHelper(this) }
    private var discoveredPeers: List<WifiDirectPeer> = emptyList()
    private var wifiDirectHost: String? = null

    private var installedApps: List<AppEntry> = emptyList()
    private var knownCategoryInfos: List<KnownCategoryInfo> = emptyList()

    private var archiveTreeUri: Uri? = null
    private var downloadsTreeUri: Uri? = null
    private var installerTreeUri: Uri? = null
    private var otherTreeUri: Uri? = null

    private val sendPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { showEstimateThenStart() }

    private val wifiDirectPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            wifiDirect.register()
            binding.textWifiDirectStatusSend.text = "검색 중..."
            wifiDirect.discoverPeers()
        }

    private fun folderPickerLauncher(assign: (Uri) -> Unit) =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                assign(uri)
            }
        }

    private val archiveFolderPicker = folderPickerLauncher {
        archiveTreeUri = it
        binding.textArchiveFolderSend.text = it.lastPathSegment ?: it.toString()
    }
    private val downloadsFolderPicker = folderPickerLauncher {
        downloadsTreeUri = it
        binding.textDownloadsFolderSend.text = it.lastPathSegment ?: it.toString()
    }
    private val installerFolderPicker = folderPickerLauncher {
        installerTreeUri = it
        binding.textInstallerFolderSend.text = it.lastPathSegment ?: it.toString()
    }
    private val otherFolderPicker = folderPickerLauncher {
        otherTreeUri = it
        binding.textOtherFolderSend.text = it.lastPathSegment ?: it.toString()
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

        setUpAppsSection()
        setUpFilesSection()

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
            if (selectedCategories().isEmpty() && customUnits().isEmpty()) {
                binding.textStatusSend.text = "보낼 데이터를 하나 이상 선택하세요"
                return@setOnClickListener
            }
            missingFolderLabel()?.let {
                binding.textStatusSend.text = "'$it' 폴더를 먼저 선택하세요"
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

    // "앱목록" - 정보성 목록뿐(각 앱 백업 방식이 다 다르고 위험해서 실제 데이터 복사는 안 함),
    // 체크한 앱들만 이름을 모아 새 폰에서 Play스토어로 재설치하라고 안내하는 용도.
    private fun setUpAppsSection() {
        binding.cbAppsSelectAll.setOnCheckedChangeListener { _, checked ->
            for (i in 0 until binding.listAppsSend.count) {
                binding.listAppsSend.setItemChecked(i, checked)
            }
        }
        Thread {
            val apps = AppListExporter.installedApps(applicationContext)
            runOnUiThread {
                installedApps = apps
                binding.listAppsSend.adapter = ArrayAdapter(
                    this, android.R.layout.simple_list_item_multiple_choice,
                    apps.map { "${it.label} (${formatBytes(it.apkSizeBytes)})" }
                )
                binding.textAppsSummarySend.text = "${apps.size}개 설치됨 (${formatBytes(apps.sumOf { it.apkSizeBytes })})"
            }
        }.start()
    }

    private fun selectedApps(): List<AppEntry> {
        val checked = binding.listAppsSend.checkedItemPositions
        val result = mutableListOf<AppEntry>()
        for (i in 0 until checked.size()) {
            if (checked.valueAt(i)) installedApps.getOrNull(checked.keyAt(i))?.let { result.add(it) }
        }
        return result
    }

    // "파일분류" - 연락처~동영상 8종(건수/용량 조회 후 오름차순 재배치)과
    // 압축 파일/다운로드 폴더/설치 파일/기타 4종(폴더 지정 방식)
    private fun setUpFilesSection() {
        binding.cbFilesSelectAll.setOnCheckedChangeListener { _, checked ->
            listOf(
                binding.cbContacts, binding.cbCallLog, binding.cbCalendar, binding.cbSms,
                binding.cbPhoto, binding.cbMusic, binding.cbAudio, binding.cbVideo,
                binding.cbArchive, binding.cbDownloads, binding.cbInstaller, binding.cbOther
            ).forEach { it.isChecked = checked }
        }

        val knownCheckboxes = listOf(
            binding.cbContacts, binding.cbCallLog, binding.cbCalendar, binding.cbSms,
            binding.cbPhoto, binding.cbMusic, binding.cbAudio, binding.cbVideo
        )
        knownCheckboxes.forEach { it.setOnCheckedChangeListener { _, _ -> updateFilesSummary() } }

        setUpFolderItem(binding.cbArchive, binding.layoutArchiveSend, binding.btnPickArchiveFolder, archiveFolderPicker)
        setUpFolderItem(binding.cbDownloads, binding.layoutDownloadsSend, binding.btnPickDownloadsFolder, downloadsFolderPicker)
        setUpFolderItem(binding.cbInstaller, binding.layoutInstallerSend, binding.btnPickInstallerFolder, installerFolderPicker)
        setUpFolderItem(binding.cbOther, binding.layoutOtherSend, binding.btnPickOtherFolder, otherFolderPicker)

        Thread {
            // Permission hasn't been requested yet at this point (that only happens when
            // "전송 시작" is pressed), so a not-yet-granted permission must not crash this
            // preview - just show 0 for that category until the user grants it later.
            val contactsCount = safePreview(0) { ContactsExporter.count(applicationContext) }
            val callLogCount = safePreview(0) { CallLogExporter.count(applicationContext) }
            val calendarCount = safePreview(0) { CalendarExporter.count(applicationContext) }
            val smsCount = safePreview(0) { SmsExporter.count(applicationContext) }
            val photos = safePreview(emptyList()) { MediaExporter.list(applicationContext, Category.PHOTO) }
            val music = safePreview(emptyList()) { MediaExporter.list(applicationContext, Category.MUSIC) }
            val audio = safePreview(emptyList()) { MediaExporter.list(applicationContext, Category.AUDIO) }
            val videos = safePreview(emptyList()) { MediaExporter.list(applicationContext, Category.VIDEO) }
            runOnUiThread {
                val infos = listOf(
                    KnownCategoryInfo(Category.CONTACTS, binding.cbContacts, "연락처", contactsCount, 0L),
                    KnownCategoryInfo(Category.CALL_LOG, binding.cbCallLog, "통화 기록", callLogCount, 0L),
                    KnownCategoryInfo(Category.CALENDAR, binding.cbCalendar, "캘린더", calendarCount, 0L),
                    KnownCategoryInfo(Category.SMS, binding.cbSms, "문자 메시지", smsCount, 0L),
                    KnownCategoryInfo(Category.PHOTO, binding.cbPhoto, "사진", photos.size, photos.sumOf { it.size }),
                    KnownCategoryInfo(Category.MUSIC, binding.cbMusic, "음악", music.size, music.sumOf { it.size }),
                    KnownCategoryInfo(Category.AUDIO, binding.cbAudio, "음성 파일", audio.size, audio.sumOf { it.size }),
                    KnownCategoryInfo(Category.VIDEO, binding.cbVideo, "동영상", videos.size, videos.sumOf { it.size })
                )
                infos.forEach { info ->
                    info.checkbox.text = if (info.category.isFileBased) {
                        "${info.label} (${info.count}개, ${formatBytes(info.totalBytes)})"
                    } else {
                        "${info.label} (${info.count}개)"
                    }
                }
                knownCategoryInfos = infos
                // 구조화 4종은 건수 기준, 미디어 4종은 용량 기준으로 각각 오름차순 - 서로 다른 단위라 통째로
                // 섞어 정렬하지 않고, 원래의 "구조화 -> 미디어" 순서 안에서만 재배치.
                val sorted = infos.filter { !it.category.isFileBased }.sortedBy { it.count } +
                    infos.filter { it.category.isFileBased }.sortedBy { it.totalBytes }
                binding.layoutKnownFileCategoriesSend.removeAllViews()
                sorted.forEach { binding.layoutKnownFileCategoriesSend.addView(it.checkbox) }
                updateFilesSummary()
            }
        }.start()
    }

    // Both the pre-transfer preview and the estimate dialog query content providers before
    // permissions are guaranteed to be granted - denied/not-yet-granted access must not crash.
    private fun <T> safePreview(default: T, query: () -> T): T {
        return try {
            query()
        } catch (e: SecurityException) {
            default
        }
    }

    private fun setUpFolderItem(checkbox: CheckBox, layout: View, button: View, picker: androidx.activity.result.ActivityResultLauncher<Uri?>) {
        checkbox.setOnCheckedChangeListener { _, checked ->
            layout.visibility = if (checked) View.VISIBLE else View.GONE
            updateFilesSummary()
        }
        button.setOnClickListener { picker.launch(null) }
    }

    private fun updateFilesSummary() {
        var count = 0L
        var bytes = 0L
        knownCategoryInfos.forEach { if (it.checkbox.isChecked) { count += it.count; bytes += it.totalBytes } }
        binding.textFilesSummarySend.text = if (bytes > 0) "${count}개 선택됨 (${formatBytes(bytes)})" else "${count}개 선택됨"
    }

    private fun missingFolderLabel(): String? {
        if (binding.cbArchive.isChecked && archiveTreeUri == null) return "압축 파일"
        if (binding.cbDownloads.isChecked && downloadsTreeUri == null) return "다운로드 폴더"
        if (binding.cbInstaller.isChecked && installerTreeUri == null) return "설치 파일"
        if (binding.cbOther.isChecked && otherTreeUri == null) return "기타"
        return null
    }

    private fun selectedCategories(): Set<Category> {
        val result = mutableSetOf<Category>()
        if (binding.cbContacts.isChecked) result += Category.CONTACTS
        if (binding.cbCallLog.isChecked) result += Category.CALL_LOG
        if (binding.cbCalendar.isChecked) result += Category.CALENDAR
        if (binding.cbSms.isChecked) result += Category.SMS
        if (binding.cbPhoto.isChecked) result += Category.PHOTO
        if (binding.cbMusic.isChecked) result += Category.MUSIC
        if (binding.cbAudio.isChecked) result += Category.AUDIO
        if (binding.cbVideo.isChecked) result += Category.VIDEO
        if (selectedApps().isNotEmpty()) result += Category.APP_LIST
        return result
    }

    private fun customUnits(): List<TransferUnit.Custom> {
        val result = mutableListOf<TransferUnit.Custom>()
        if (binding.cbArchive.isChecked) archiveTreeUri?.let { result += TransferUnit.Custom("CUSTOM_ARCHIVE", "압축 파일", it) }
        if (binding.cbDownloads.isChecked) downloadsTreeUri?.let { result += TransferUnit.Custom("CUSTOM_DOWNLOADS", "다운로드 폴더", it) }
        if (binding.cbInstaller.isChecked) installerTreeUri?.let { result += TransferUnit.Custom("CUSTOM_INSTALLER", "설치 파일", it) }
        if (binding.cbOther.isChecked) otherTreeUri?.let { result += TransferUnit.Custom("CUSTOM_OTHER", "기타", it) }
        return result
    }

    private fun selectedSortOrder(): SortOrder =
        if (binding.radioSortOldestSend.isChecked) SortOrder.OLDEST_FIRST else SortOrder.NEWEST_FIRST

    private fun buildConnector(): DuplexConnector? {
        val pin = binding.editPin.text.toString().trim()
        return when (selectedMethod) {
            ConnectionMethod.LOCAL_NETWORK -> LocalConnector(binding.editHost.text.toString().trim(), TRANSFER_PORT)
            ConnectionMethod.WIFI_DIRECT -> wifiDirectHost?.let { LocalConnector(it, TRANSFER_PORT) }
            ConnectionMethod.RELAY -> RelayConnector(binding.editRelayUrlSend.text.toString().trim(), pin)
        }
    }

    // Before actually connecting, size up each selected unit (fastest-first order)
    // and let the user see per-unit / total estimated time before committing.
    private fun showEstimateThenStart() {
        val categories = selectedCategories()
        val customs = customUnits()
        val appsSnapshot = selectedApps()
        val orderedUnits: List<TransferUnit> = Category.ORDERED.filter { it in categories }.map { TransferUnit.Builtin(it) } + customs
        binding.textStatusSend.text = "예상 시간 계산 중..."
        Thread {
            val estimates = orderedUnits.map { unit ->
                safePreview(UnitEstimate(unit, 0, 0L, 0L)) { TimeEstimate.estimate(applicationContext, unit, selectedSortOrder(), appsSnapshot.size) }
            }
            val totalMs = estimates.sumOf { it.estimatedMs }
            val summary = buildString {
                estimates.forEach { e ->
                    append("${e.unit.label}: ${e.count}개 (약 ${TimeEstimate.formatDuration(e.estimatedMs)})\n")
                }
                append("\n총 예상 시간: 약 ${TimeEstimate.formatDuration(totalMs)}")
                append("\n(실제 소요 시간은 기기·네트워크 속도에 따라 달라질 수 있습니다)")
            }
            runOnUiThread {
                binding.textStatusSend.text = ""
                AlertDialog.Builder(this)
                    .setTitle("전송 순서 및 예상 시간")
                    .setMessage(summary)
                    .setPositiveButton("전송 시작") { _, _ -> startTransfer(appsSnapshot, customs) }
                    .setNegativeButton("취소") { _, _ -> binding.btnConnectSend.isEnabled = true }
                    .setOnCancelListener { binding.btnConnectSend.isEnabled = true }
                    .show()
            }
        }.start()
    }

    private fun startTransfer(selectedApps: List<AppEntry>, customUnits: List<TransferUnit.Custom>) {
        val connector = buildConnector()
        if (connector == null) {
            binding.textStatusSend.text = "연결 정보가 없습니다. 다시 시도하세요"
            binding.btnConnectSend.isEnabled = true
            return
        }
        val pin = binding.editPin.text.toString().trim()

        TransferClient(
            context = applicationContext,
            connector = connector,
            pin = pin,
            categories = selectedCategories(),
            selectedApps = selectedApps,
            customUnits = customUnits,
            sortOrder = selectedSortOrder(),
            onProgress = { p ->
                runOnUiThread {
                    binding.textCategoryIndexSend.text =
                        "${p.categoryIndex}/${p.totalCategories} 처리 중: ${p.label}"
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

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "${bytes}B" else String.format("%.1f%s", value, units[unitIndex])
}
