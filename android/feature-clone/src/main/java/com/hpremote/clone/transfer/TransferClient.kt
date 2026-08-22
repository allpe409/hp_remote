package com.hpremote.clone.transfer

import android.content.Context
import com.hpremote.clone.data.AppEntry
import com.hpremote.clone.data.AppListExporter
import com.hpremote.clone.data.CalendarExporter
import com.hpremote.clone.data.CallLogExporter
import com.hpremote.clone.data.ContactsExporter
import com.hpremote.clone.data.MediaExporter
import com.hpremote.clone.data.MediaFile
import com.hpremote.clone.data.SmsExporter
import com.hpremote.clone.data.SnsBackupExporter
import org.json.JSONArray
import org.json.JSONObject

class TransferClient(
    private val context: Context,
    private val connector: DuplexConnector,
    private val pin: String,
    private val categories: Set<Category>,
    private val selectedApps: List<AppEntry>,
    private val customUnits: List<TransferUnit.Custom>,
    private val sortOrder: SortOrder,
    private val onProgress: (TransferProgress) -> Unit,
    private val onDone: (success: Boolean, message: String) -> Unit
) {
    private val orderedUnits: List<TransferUnit> =
        Category.ORDERED.filter { it in categories }.map { TransferUnit.Builtin(it) } + customUnits

    fun start() {
        Thread {
            try {
                run()
            } catch (e: Exception) {
                onDone(false, "오류: ${e.message}")
            }
        }.start()
    }

    // Reads a unit via [export], recording nothing instead of aborting the
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
        Category.APP_LIST -> AppListExporter.exportSelected(selectedApps, onRecord)
        else -> JSONArray()
    }

    private fun run() {
        connector.connect().use { duplex ->
            val input = duplex.input
            val output = duplex.output

            output.writeUTF(pin)
            output.flush()
            if (input.readUTF() != TAG_OK) {
                onDone(false, "PIN이 일치하지 않습니다")
                return
            }

            // A quick count/size pass (no JSON build, no file bytes read) just to size the manifest.
            val estimates = orderedUnits.associateWith {
                safeExport({ TimeEstimate.estimate(context, it, sortOrder, selectedApps.size) }, UnitEstimate(it, 0, 0L, 0L))
            }

            val manifest = JSONObject()
            val unitsArray = JSONArray()
            orderedUnits.forEach {
                unitsArray.put(JSONObject().apply {
                    put("tag", it.tag)
                    put("label", it.label)
                    put("count", estimates.getValue(it).count)
                })
            }
            manifest.put("units", unitsArray)
            output.writeFrame(manifest.toString().toByteArray(Charsets.UTF_8))

            val totalUnits = orderedUnits.sumOf { estimates.getValue(it).count }.coerceAtLeast(1)
            var doneUnitsBeforeUnit = 0

            for ((index, unit) in orderedUnits.withIndex()) {
                val unitIndex = index + 1
                val unitTotal = estimates.getValue(unit).count.coerceAtLeast(1)

                fun emit(unitPercent: Int, withinUnitCount: Int, message: String) {
                    val overall = ((doneUnitsBeforeUnit + withinUnitCount) * 100 / totalUnits).coerceIn(0, 100)
                    onProgress(TransferProgress(unitIndex, orderedUnits.size, unit.label, unitPercent.coerceIn(0, 100), overall, message))
                }

                val files: List<MediaFile>? = when {
                    unit is TransferUnit.Custom -> safeExport({ SnsBackupExporter.list(context, unit.treeUri, sortOrder) }, emptyList())
                    unit is TransferUnit.Builtin && unit.category.isFileBased -> safeExport({ MediaExporter.list(context, unit.category, sortOrder) }, emptyList())
                    else -> null
                }

                if (files != null) {
                    val total = files.size.coerceAtLeast(1)
                    files.forEachIndexed { fi, file ->
                        output.writeUTF(unit.tag)
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
                        emit((fi + 1) * 100 / total, fi + 1, "보냄: ${unit.label} - ${file.displayName}")
                    }
                    doneUnitsBeforeUnit += files.size
                } else {
                    val category = (unit as TransferUnit.Builtin).category
                    val records = safeExport({
                        exportStructured(category) { done, total ->
                            emit(if (total > 0) done * 100 / total else 100, done, "내보내는 중: ${unit.label} ($done/$total)")
                        }
                    }, JSONArray())
                    output.writeUTF(unit.tag)
                    output.writeFrame(records.toString().toByteArray(Charsets.UTF_8))
                    doneUnitsBeforeUnit += records.length()
                    emit(100, records.length(), "보냄: ${unit.label} ${records.length()}/${unitTotal}개")
                }
            }

            output.writeUTF(TAG_DONE)
            output.flush()
            onDone(true, "전송 완료 ($doneUnitsBeforeUnit/${totalUnits}개)")
        }
    }
}
