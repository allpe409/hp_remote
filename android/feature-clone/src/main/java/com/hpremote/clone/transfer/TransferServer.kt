package com.hpremote.clone.transfer

import android.content.Context
import com.hpremote.clone.data.CalendarImporter
import com.hpremote.clone.data.CallLogImporter
import com.hpremote.clone.data.ContactsImporter
import com.hpremote.clone.data.MediaImporter
import com.hpremote.clone.data.SmsImporter
import com.hpremote.clone.data.SnsBackupImporter
import org.json.JSONArray
import org.json.JSONObject

private data class UnitInfo(val tag: String, val label: String, val count: Int)

class TransferServer(
    private val context: Context,
    private val pin: String,
    private val acceptor: DuplexAcceptor,
    private val onProgress: (TransferProgress) -> Unit,
    private val onDone: (success: Boolean, message: String) -> Unit
) {
    @Volatile private var stopping = false

    fun start() {
        stopping = false
        Thread {
            try {
                val duplex = acceptor.accept()
                handleClient(duplex)
            } catch (e: Exception) {
                if (!stopping) onDone(false, "오류: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        stopping = true
        acceptor.cancel()
    }

    // APP_LIST is handled separately by the caller (display-only, no importer).
    private fun importStructured(category: Category, records: JSONArray, onRecord: (Int, Int) -> Unit): Int = when (category) {
        Category.CONTACTS -> ContactsImporter.import(context, records, onRecord)
        Category.CALL_LOG -> CallLogImporter.import(context, records, onRecord)
        Category.CALENDAR -> CalendarImporter.import(context, records, onRecord)
        Category.SMS -> SmsImporter.import(context, records, onRecord)
        else -> 0
    }

    private fun handleClient(duplex: Duplex) {
        duplex.use {
            val input = duplex.input
            val output = duplex.output

            val receivedPin = input.readUTF()
            if (receivedPin != pin) {
                output.writeUTF(TAG_DENY)
                onDone(false, "PIN이 일치하지 않습니다")
                return
            }
            output.writeUTF(TAG_OK)

            val manifest = JSONObject(String(input.readFrame(), Charsets.UTF_8))
            val unitsJson = manifest.getJSONArray("units")
            val orderedUnits = (0 until unitsJson.length()).map {
                val u = unitsJson.getJSONObject(it)
                UnitInfo(u.getString("tag"), u.getString("label"), u.getInt("count"))
            }
            val unitByTag = orderedUnits.associateBy { it.tag }
            val totalUnits = orderedUnits.sumOf { it.count }.coerceAtLeast(1)

            var doneUnits = 0
            var currentTag: String? = null
            var currentUnitIndex = 0
            var currentUnitFilesReceived = 0
            var receivedCustomFile = false
            val appList = JSONArray()

            fun emit(label: String, unitPercent: Int, message: String) {
                val overall = (doneUnits * 100 / totalUnits).coerceIn(0, 100)
                onProgress(TransferProgress(currentUnitIndex, orderedUnits.size, label, unitPercent.coerceIn(0, 100), overall, message))
            }

            while (true) {
                val tag = input.readUTF()
                if (tag == TAG_DONE) break
                val info = unitByTag[tag] ?: continue
                val category = Category.fromTag(tag)

                if (tag != currentTag) {
                    currentTag = tag
                    currentUnitIndex++
                    currentUnitFilesReceived = 0
                }
                val unitTotal = info.count.coerceAtLeast(1)

                if (category == null || category.isFileBased) {
                    val meta = JSONObject(String(input.readFrame(), Charsets.UTF_8))
                    val name = meta.getString("name")
                    val mime = meta.getString("mime")
                    val size = meta.getLong("size")
                    val uri = if (category == null) {
                        SnsBackupImporter.begin(context, info.label, name, mime)
                    } else {
                        MediaImporter.begin(context, category, name, mime)
                    }
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            copyExactly(input, out, size)
                        }
                        if (category == null) SnsBackupImporter.finish(context, uri) else MediaImporter.finish(context, uri)
                    } else {
                        skipExactly(input, size)
                    }
                    currentUnitFilesReceived++
                    doneUnits++
                    if (category == null) receivedCustomFile = true
                    emit(info.label, currentUnitFilesReceived * 100 / unitTotal, "받음: ${info.label} - $name")
                } else {
                    val records = JSONArray(String(input.readFrame(), Charsets.UTF_8))
                    val doneUnitsBeforeThisUnit = doneUnits
                    val imported = if (category == Category.APP_LIST) {
                        for (i in 0 until records.length()) appList.put(records.getJSONObject(i))
                        records.length()
                    } else {
                        importStructured(category, records) { done, total ->
                            doneUnits = doneUnitsBeforeThisUnit + done
                            emit(info.label, if (total > 0) done * 100 / total else 100, "가져오는 중: ${info.label} ($done/$total)")
                        }
                    }
                    doneUnits = doneUnitsBeforeThisUnit + records.length()
                    emit(info.label, 100, "가져옴: ${info.label} $imported/${records.length()}개")
                }
            }

            if (appList.length() > 0) {
                emit(currentTag?.let { unitByTag[it]?.label } ?: "설치된 앱 목록", 100, "설치된 앱 ${appList.length()}개는 새 폰에서 Play 스토어로 직접 재설치해 주세요.")
            }
            if (receivedCustomFile) {
                emit(currentTag?.let { unitByTag[it]?.label } ?: "사용자 지정 파일", 100, "받은 파일은 다운로드 폴더의 hp_control_clone/custom/<항목명>에 저장됐습니다.")
            }
            onDone(true, "전송 완료")
        }
    }
}
