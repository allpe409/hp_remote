package com.hpremote.clone

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hpremote.clone.databinding.ActivitySendBinding
import com.hpremote.clone.transfer.Category
import com.hpremote.clone.transfer.TimeEstimate
import com.hpremote.clone.transfer.TransferClient
import com.hpremote.clone.transfer.categoryLabel

class SendActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySendBinding

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { showEstimateThenStart() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySendBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnConnectSend.setOnClickListener {
            val host = binding.editHost.text.toString().trim()
            val pin = binding.editPin.text.toString().trim()
            if (host.isEmpty() || pin.length != 6) {
                binding.textStatusSend.text = "새 폰의 IP와 6자리 PIN을 입력하세요"
                return@setOnClickListener
            }
            if (selectedCategories().isEmpty()) {
                binding.textStatusSend.text = "보낼 데이터를 하나 이상 선택하세요"
                return@setOnClickListener
            }
            binding.btnConnectSend.isEnabled = false
            binding.textStatusSend.text = ""
            binding.textCategoryIndexSend.text = ""
            binding.progressCategorySend.progress = 0
            binding.progressOverallSend.progress = 0
            permissionLauncher.launch(PermissionsHelper.sendPermissions())
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
        return result
    }

    // Before actually connecting, size up each selected category (fastest-first order)
    // and let the user see per-category / total estimated time before committing.
    private fun showEstimateThenStart() {
        val ordered = Category.ORDERED.filter { it in selectedCategories() }
        binding.textStatusSend.text = "예상 시간 계산 중..."
        Thread {
            val estimates = ordered.map { TimeEstimate.estimate(applicationContext, it) }
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
        val host = binding.editHost.text.toString().trim()
        val pin = binding.editPin.text.toString().trim()
        val categories = selectedCategories()

        TransferClient(
            context = applicationContext,
            host = host,
            pin = pin,
            categories = categories,
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
}
