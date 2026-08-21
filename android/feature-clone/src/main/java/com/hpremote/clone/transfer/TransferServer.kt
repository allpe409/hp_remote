package com.hpremote.clone.transfer

import android.content.Context
import com.hpremote.clone.data.CalendarImporter
import com.hpremote.clone.data.CallLogImporter
import com.hpremote.clone.data.ContactsImporter
import com.hpremote.clone.data.MediaImporter
import com.hpremote.clone.data.SmsImporter
import org.json.JSONArray
import org.json.JSONObject

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
            val orderedCategories = ArrayList<Category>()
            val keysIterator = manifest.keys()
            while (keysIterator.hasNext()) {
                Category.fromTag(keysIterator.next())?.let { orderedCategories.add(it) }
            }
            val categoryTotalUnits = orderedCategories.associateWith { manifest.getInt(it.tag) }
            val totalUnits = categoryTotalUnits.values.sum().coerceAtLeast(1)

            var doneUnits = 0
            var currentCategory: Category? = null
            var currentCategoryIndex = 0
            var currentCategoryFilesReceived = 0
            val appList = JSONArray()

            fun emit(category: Category, categoryPercent: Int, message: String) {
                val overall = (doneUnits * 100 / totalUnits).coerceIn(0, 100)
                onProgress(TransferProgress(currentCategoryIndex, orderedCategories.size, category, categoryPercent.coerceIn(0, 100), overall, message))
            }

            while (true) {
                val tag = input.readUTF()
                if (tag == TAG_DONE) break
                val category = Category.fromTag(tag) ?: continue

                if (category != currentCategory) {
                    currentCategory = category
                    currentCategoryIndex++
                    currentCategoryFilesReceived = 0
                }
                val categoryTotal = (categoryTotalUnits[category] ?: 0).coerceAtLeast(1)

                if (category.isMedia) {
                    val meta = JSONObject(String(input.readFrame(), Charsets.UTF_8))
                    val name = meta.getString("name")
                    val mime = meta.getString("mime")
                    val size = meta.getLong("size")
                    val uri = MediaImporter.begin(context, category, name, mime)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            copyExactly(input, out, size)
                        }
                        MediaImporter.finish(context, uri)
                    } else {
                        skipExactly(input, size)
                    }
                    currentCategoryFilesReceived++
                    doneUnits++
                    emit(category, currentCategoryFilesReceived * 100 / categoryTotal, "받음: ${categoryLabel(category)} - $name")
                } else {
                    val records = JSONArray(String(input.readFrame(), Charsets.UTF_8))
                    val doneUnitsBeforeThisCategory = doneUnits
                    val imported = if (category == Category.APP_LIST) {
                        for (i in 0 until records.length()) appList.put(records.getJSONObject(i))
                        records.length()
                    } else {
                        importStructured(category, records) { done, total ->
                            doneUnits = doneUnitsBeforeThisCategory + done
                            emit(category, if (total > 0) done * 100 / total else 100, "가져오는 중: ${categoryLabel(category)} ($done/$total)")
                        }
                    }
                    doneUnits = doneUnitsBeforeThisCategory + records.length()
                    emit(category, 100, "가져옴: ${categoryLabel(category)} $imported/${records.length()}개")
                }
            }

            if (appList.length() > 0) {
                emit(currentCategory ?: Category.APP_LIST, 100, "설치된 앱 ${appList.length()}개는 새 폰에서 Play 스토어로 직접 재설치해 주세요.")
            }
            onDone(true, "전송 완료")
        }
    }
}
