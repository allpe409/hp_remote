package com.hpremote.clone.transfer

import android.content.Context
import com.hpremote.clone.data.CalendarImporter
import com.hpremote.clone.data.CallLogImporter
import com.hpremote.clone.data.ContactsImporter
import com.hpremote.clone.data.MediaImporter
import com.hpremote.clone.data.SmsImporter
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

class TransferServer(
    private val context: Context,
    private val pin: String,
    private val onProgress: (log: String, percent: Int) -> Unit,
    private val onDone: (success: Boolean, message: String) -> Unit
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var stopping = false
    private val log = StringBuilder()

    fun start() {
        stopping = false
        Thread {
            try {
                val socket = ServerSocket(TRANSFER_PORT)
                serverSocket = socket
                progress("연결 대기 중... (${NetworkUtils.getLocalIpAddress() ?: "?"})", 0)
                val client = socket.accept()
                handleClient(client)
            } catch (e: Exception) {
                if (!stopping) onDone(false, "오류: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        stopping = true
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Already closed / never opened - nothing to do.
        }
    }

    private fun progress(line: String, percent: Int) {
        log.append(line).append('\n')
        onProgress(log.toString(), percent)
    }

    private fun handleClient(socket: Socket) {
        socket.use {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            val receivedPin = input.readUTF()
            if (receivedPin != pin) {
                output.writeUTF(TAG_DENY)
                onDone(false, "PIN이 일치하지 않습니다")
                return
            }
            output.writeUTF(TAG_OK)

            val manifest = JSONObject(String(input.readFrame(), Charsets.UTF_8))
            var totalUnits = 0
            val manifestKeys = manifest.keys()
            while (manifestKeys.hasNext()) totalUnits += manifest.getInt(manifestKeys.next())
            totalUnits = totalUnits.coerceAtLeast(1)
            var doneUnits = 0

            val appList = JSONArray()

            while (true) {
                val tag = input.readUTF()
                if (tag == TAG_DONE) break
                val category = Category.fromTag(tag)
                if (category == null) {
                    continue
                }

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
                    doneUnits++
                    progress("받음: ${categoryLabel(category)} - $name", doneUnits * 100 / totalUnits)
                } else {
                    val records = JSONArray(String(input.readFrame(), Charsets.UTF_8))
                    val imported = when (category) {
                        Category.CONTACTS -> ContactsImporter.import(context, records)
                        Category.CALL_LOG -> CallLogImporter.import(context, records)
                        Category.CALENDAR -> CalendarImporter.import(context, records)
                        Category.SMS -> SmsImporter.import(context, records)
                        Category.APP_LIST -> {
                            for (i in 0 until records.length()) appList.put(records.getJSONObject(i))
                            records.length()
                        }
                        else -> 0
                    }
                    doneUnits += records.length()
                    progress(
                        "가져옴: ${categoryLabel(category)} $imported/${records.length()}개",
                        doneUnits * 100 / totalUnits
                    )
                }
            }

            if (appList.length() > 0) {
                progress("설치된 앱 ${appList.length()}개는 새 폰에서 Play 스토어로 직접 재설치해 주세요.", 100)
            }
            progress("전송 완료", 100)
            onDone(true, "전송 완료")
        }
    }
}
