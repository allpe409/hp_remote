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

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    var commandListener: ((JSONObject) -> Unit)? = null

    fun connect(serverUrl: String, code: String) {
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
                    commandListener?.invoke(JSONObject(text))
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

    fun sendFrame(jpegBytes: ByteArray) {
        socket?.send(ByteString.of(*jpegBytes))
    }

    fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
    }
}
