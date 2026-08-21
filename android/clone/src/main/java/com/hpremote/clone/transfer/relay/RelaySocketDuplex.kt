package com.hpremote.clone.transfer.relay

import android.util.Log
import com.hpremote.clone.transfer.Duplex
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val RELAY_ROLE_HOST = "host"
const val RELAY_ROLE_PEER = "peer"

/**
 * A [Duplex] backed by a WebSocket to the hp_remote relay server, for two
 * phones that aren't on the same local network. The server only relays
 * binary frames verbatim between the paired "host" (receiver) and "peer"
 * (sender) - it doesn't parse or understand the transfer protocol inside
 * (same "dumb relay" principle as the screen-mirror relay in server/src/index.js).
 */
class RelaySocketDuplex private constructor(private val webSocket: WebSocket) : Duplex {

    private val pipedIn = PipedInputStream(PIPE_BUFFER_BYTES)
    private val pipedOut = PipedOutputStream(pipedIn)

    override val input = DataInputStream(pipedIn)
    override val output = DataOutputStream(RelayOutputStream())

    private fun deliver(bytes: ByteArray) {
        try {
            pipedOut.write(bytes)
        } catch (e: IOException) {
            // Pipe already closed (transfer finished/aborted) - nothing to do.
        }
    }

    private inner class RelayOutputStream : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            webSocket.send(ByteString.of(*b.copyOfRange(off, off + len)))
        }
    }

    override fun close() {
        try {
            pipedOut.close()
        } catch (e: IOException) {
            // Already closed - nothing to do.
        }
        webSocket.close(1000, "done")
    }

    companion object {
        private const val TAG = "RelaySocketDuplex"
        private const val PIPE_BUFFER_BYTES = 1 * 1024 * 1024
        private const val CONNECT_TIMEOUT_SEC = 30L

        private val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        /** Blocks until this device and its peer are both registered on [code]. */
        fun connect(serverUrl: String, code: String, role: String): RelaySocketDuplex {
            val registeredLatch = CountDownLatch(1)
            val peerJoinedLatch = CountDownLatch(1)
            // Plain var is safe here: every write happens-before the matching
            // countDown(), and CountDownLatch.await() happens-after that countDown().
            var failure: Exception? = null

            lateinit var duplex: RelaySocketDuplex
            val request = Request.Builder().url(serverUrl).build()
            val webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        JSONObject().apply {
                            put("type", "register-clone")
                            put("role", role)
                            put("code", code)
                        }.toString()
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = try {
                        JSONObject(text)
                    } catch (e: Exception) {
                        return
                    }
                    when (msg.optString("type")) {
                        "registered" -> registeredLatch.countDown()
                        "peer-joined" -> peerJoinedLatch.countDown()
                        "error" -> {
                            failure = IOException(msg.optString("message", "relay error"))
                            registeredLatch.countDown()
                            peerJoinedLatch.countDown()
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (::duplex.isInitialized) duplex.deliver(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "relay websocket failure", t)
                    failure = IOException(t)
                    registeredLatch.countDown()
                    peerJoinedLatch.countDown()
                }
            })

            duplex = RelaySocketDuplex(webSocket)

            if (!registeredLatch.await(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                throw IOException("릴레이 서버 연결 시간 초과")
            }
            failure?.let { throw it }

            if (!peerJoinedLatch.await(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                throw IOException("상대 폰이 아직 연결되지 않았습니다")
            }
            failure?.let { throw it }

            return duplex
        }
    }
}
