package com.hpremote.clone

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hpremote.clone.databinding.ActivitySendBinding
import com.hpremote.clone.transfer.Category
import com.hpremote.clone.transfer.TransferClient

class SendActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySendBinding

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startTransfer() }

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
            binding.btnConnectSend.isEnabled = false
            binding.textStatusSend.text = ""
            binding.progressSend.progress = 0
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

    private fun startTransfer() {
        val host = binding.editHost.text.toString().trim()
        val pin = binding.editPin.text.toString().trim()
        val categories = selectedCategories()
        if (categories.isEmpty()) {
            binding.textStatusSend.text = "보낼 데이터를 하나 이상 선택하세요"
            binding.btnConnectSend.isEnabled = true
            return
        }

        TransferClient(
            context = applicationContext,
            host = host,
            pin = pin,
            categories = categories,
            onProgress = { log, percent ->
                runOnUiThread {
                    binding.textStatusSend.text = log
                    binding.progressSend.progress = percent
                }
            },
            onDone = { _, message ->
                runOnUiThread {
                    binding.textStatusSend.append("\n$message")
                    binding.btnConnectSend.isEnabled = true
                }
            }
        ).start()
    }
}
