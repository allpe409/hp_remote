package com.hpremote.clone.transfer

import android.content.Context
import com.hpremote.clone.data.AppListExporter
import com.hpremote.clone.data.CalendarExporter
import com.hpremote.clone.data.CallLogExporter
import com.hpremote.clone.data.ContactsExporter
import com.hpremote.clone.data.MediaExporter
import com.hpremote.clone.data.MediaFile
import com.hpremote.clone.data.SmsExporter
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class TransferClient(
    private val context: Context,
    private val host: String,
    private val pin: String,
    private val categories: Set<Category>,
    private val onProgress: (log: String, percent: Int) -> Unit,
    private val onDone: (success: Boolean, message: String) -> Unit
) {
    private val log = StringBuilder()

    fun start() {
        Thread {
            try {
                run()
            } catch (e: Exception) {
                onDone(false, "오류: ${e.message}")
            }
        }.start()
    }

    private fun progress(line: String, percent: Int) {
        log.append(line).append('\n')
        onProgress(log.toString(), percent)
    }

    // Reads a category via [export], recording zero records instead of aborting the
    // whole transfer if this phone denied that permission.
    private fun <T> safeExport(label: String, export: () -> T, empty: T): T {
        return try {
            export()
        } catch (e: SecurityException) {
            progress("권한 없음: $label - 건너뜀", 0)
            empty
        } catch (e: Exception) {
            progress("$label 읽기 실패: ${e.message}", 0)
            empty
        }
    }

    private fun run() {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, TRANSFER_PORT), 8000)
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            output.writeUTF(pin)
            output.flush()
            if (input.readUTF() != TAG_OK) {
                onDone(false, "PIN이 일치하지 않습니다")
                return
            }

            val structured = LinkedHashMap<Category, JSONArray>()
            if (Category.CONTACTS in categories) {
                structured[Category.CONTACTS] = safeExport("연락처", { ContactsExporter.export(context) }, JSONArray())
            }
            if (Category.CALL_LOG in categories) {
                structured[Category.CALL_LOG] = safeExport("통화 기록", { CallLogExporter.export(context) }, JSONArray())
            }
            if (Category.CALENDAR in categories) {
                structured[Category.CALENDAR] = safeExport("캘린더", { CalendarExporter.export(context) }, JSONArray())
            }
            if (Category.SMS in categories) {
                structured[Category.SMS] = safeExport("문자 메시지", { SmsExporter.export(context) }, JSONArray())
            }
            if (Category.APP_LIST in categories) {
                structured[Category.APP_LIST] = safeExport("설치된 앱 목록", { AppListExporter.export(context) }, JSONArray())
            }

            val media = LinkedHashMap<Category, List<MediaFile>>()
            if (Category.PHOTO in categories) {
                media[Category.PHOTO] = safeExport("사진", { MediaExporter.list(context, Category.PHOTO) }, emptyList())
            }
            if (Category.VIDEO in categories) {
                media[Category.VIDEO] = safeExport("동영상", { MediaExporter.list(context, Category.VIDEO) }, emptyList())
            }

            val manifest = JSONObject()
            structured.forEach { (category, records) -> manifest.put(category.tag, records.length()) }
            media.forEach { (category, files) -> manifest.put(category.tag, files.size) }
            output.writeFrame(manifest.toString().toByteArray(Charsets.UTF_8))

            var totalUnits = 0
            val manifestKeys = manifest.keys()
            while (manifestKeys.hasNext()) totalUnits += manifest.getInt(manifestKeys.next())
            totalUnits = totalUnits.coerceAtLeast(1)
            var doneUnits = 0

            for ((category, records) in structured) {
                output.writeUTF(category.tag)
                output.writeFrame(records.toString().toByteArray(Charsets.UTF_8))
                doneUnits += records.length()
                progress("보냄: ${categoryLabel(category)} ${records.length()}개", doneUnits * 100 / totalUnits)
            }

            for ((category, files) in media) {
                for (file in files) {
                    output.writeUTF(category.tag)
                    val meta = JSONObject().apply {
                        put("name", file.displayName)
                        put("mime", file.mimeType)
                        put("size", file.size)
                    }
                    output.writeFrame(meta.toString().toByteArray(Charsets.UTF_8))
                    context.contentResolver.openInputStream(file.uri)?.use { ins ->
                        copyExactly(ins, output, file.size)
                    }
                    output.flush()
                    doneUnits++
                    progress("보냄: ${categoryLabel(category)} - ${file.displayName}", doneUnits * 100 / totalUnits)
                }
            }

            output.writeUTF(TAG_DONE)
            output.flush()
            onDone(true, "전송 완료 ($doneUnits/${totalUnits}개)")
        }
    }
}
