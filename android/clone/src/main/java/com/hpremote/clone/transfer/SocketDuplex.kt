package com.hpremote.clone.transfer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Wraps a plain TCP socket - used for both same-network and Wi-Fi Direct transfers,
 *  since once Wi-Fi Direct is connected it's just an ordinary IP socket too. */
class SocketDuplex(private val socket: Socket) : Duplex {
    override val input = DataInputStream(socket.getInputStream())
    override val output = DataOutputStream(socket.getOutputStream())

    override fun close() {
        socket.close()
    }
}

/** Listens on [port] and hands back the first connection as a [SocketDuplex]. */
class LocalServerAcceptor(private val port: Int) : DuplexAcceptor {
    @Volatile private var serverSocket: ServerSocket? = null

    override fun accept(): Duplex {
        val socket = ServerSocket(port)
        serverSocket = socket
        return SocketDuplex(socket.accept())
    }

    override fun cancel() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Already closed / never opened - nothing to do.
        }
    }
}

/** Connects to [host]:[port] and wraps the result as a [SocketDuplex]. */
class LocalConnector(private val host: String, private val port: Int) : DuplexConnector {
    override fun connect(): Duplex {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 8000)
        return SocketDuplex(socket)
    }
}
