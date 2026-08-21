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
    private val onProgress: (TransferProgress) -> Unit,
    private val onDone: (success: Boolean, message: String) -> Unit
) {
    private val ordered = Category.ORDERED.filter { it in categories }

    fun start() {
        Thread {
            try {
                run()
            } catch (e: Exception) {
                onDone(false, "오류: ${e.message}")
            }
        }.start()
    }

    // Reads a category via [export], recording nothing instead of aborting the
    // whole transfer if this phone denied that permission.
    private fun <T> safeExport(export: () -> T, empty: T): T {
        return try {
            export()
        } catch (e: Exception) {
            empty
        }
    }

    private fun exportStructured(category: Category, onRecord: (Int, Int) -> Unit): JSONArray = when (category) {
        Category.CONTACTS -> ContactsExporter.export(context, onRecord)
        Category.CALL_LOG -> CallLogExporter.export(context, onRecord)
        Category.CALENDAR -> CalendarExporter.export(context, onRecord)
        Category.SMS -> SmsExporter.export(context, onRecord)
        Category.APP_LIST -> AppListExporter.export(context, onRecord)
        else -> JSONArray()
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

            // A quick count/size pass (no JSON build, no file bytes read) just to size the manifest.
            val estimates = ordered.associateWith { safeExport({ TimeEstimate.estimate(context, it) }, CategoryEstimate(it, 0, 0L, 0L)) }

            val manifest = JSONObject()
            ordered.forEach { manifest.put(it.tag, estimates.getValue(it).count) }
            output.writeFrame(manifest.toString().toByteArray(Charsets.UTF_8))

            val totalUnits = ordered.sumOf { estimates.getValue(it).count }.coerceAtLeast(1)
            var doneUnitsBeforeCategory = 0

            for ((index, category) in ordered.withIndex()) {
                val categoryIndex = index + 1
                val categoryTotal = estimates.getValue(category).count.coerceAtLeast(1)

                fun emit(categoryPercent: Int, withinCategoryUnits: Int, message: String) {
                    val overall = ((doneUnitsBeforeCategory + withinCategoryUnits) * 100 / totalUnits).coerceIn(0, 100)
                    onProgress(TransferProgress(categoryIndex, ordered.size, category, categoryPercent.coerceIn(0, 100), overall, message))
                }

                if (category.isMedia) {
                    val files = safeExport({ MediaExporter.list(context, category) }, emptyList<MediaFile>())
                    val total = files.size.coerceAtLeast(1)
                    files.forEachIndexed { fi, file ->
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
                        emit((fi + 1) * 100 / total, fi + 1, "보냄: ${categoryLabel(category)} - ${file.displayName}")
                    }
                    doneUnitsBeforeCategory += files.size
                } else {
                    val records = safeExport({
                        exportStructured(category) { done, total ->
                            emit(if (total > 0) done * 100 / total else 100, done, "내보내는 중: ${categoryLabel(category)} ($done/$total)")
                        }
                    }, JSONArray())
                    output.writeUTF(category.tag)
                    output.writeFrame(records.toString().toByteArray(Charsets.UTF_8))
                    doneUnitsBeforeCategory += records.length()
                    emit(100, records.length(), "보냄: ${categoryLabel(category)} ${records.length()}/${categoryTotal}개")
                }
            }

            output.writeUTF(TAG_DONE)
            output.flush()
            onDone(true, "전송 완료 ($doneUnitsBeforeCategory/${totalUnits}개)")
        }
    }
}
