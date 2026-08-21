package com.hpremote.agent

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Single shared WebSocket connection to the relay server, used by both
 * [ScreenCaptureService] (sends frames, sends "info") and
 * [RemoteAccessibilityService] (receives input commands).
 */
object RelayConnection {
    private const val TAG = "RelayConnection"

    // Every binary WS message is prefixed with one of these so the controller
    // can tell a video frame apart from an audio chunk.
    private const val TYPE_VIDEO: Byte = 1
    private const val TYPE_AUDIO: Byte = 2

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    var commandListener: ((JSONObject) -> Unit)? = null

    fun connect(serverUrl: String, code: String, onRegistered: () -> Unit = {}) {
        val request = Request.Builder().url(serverUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val register = JSONObject().apply {
                    put("type", "register")
                    put("role", "device")
                    put("code", code)
                }
                webSocket.send(register.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    // The device's own registration ack has no payload beyond "type" -
                    // handle it here instead of forwarding it as an input command.
                    if (msg.optString("type") == "registered") {
                        onRegistered()
                        return
                    }
                    commandListener?.invoke(msg)
                } catch (e: Exception) {
                    Log.w(TAG, "bad message from server: $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "websocket failure", t)
            }
        })
    }

    fun sendInfo(width: Int, height: Int) {
        val info = JSONObject().apply {
            put("type", "info")
            put("width", width)
            put("height", height)
        }
        socket?.send(info.toString())
    }

    fun sendFrame(jpegBytes: ByteArray) = send(TYPE_VIDEO, jpegBytes)

    fun sendAudio(pcmBytes: ByteArray) = send(TYPE_AUDIO, pcmBytes)

    private fun send(type: Byte, payload: ByteArray) {
        val out = ByteArray(payload.size + 1)
        out[0] = type
        System.arraycopy(payload, 0, out, 1, payload.size)
        socket?.send(ByteString.of(*out))
    }

    fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
    }
}
