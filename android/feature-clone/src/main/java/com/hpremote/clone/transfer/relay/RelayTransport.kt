package com.hpremote.clone.transfer.relay

import com.hpremote.clone.transfer.Duplex
import com.hpremote.clone.transfer.DuplexAcceptor
import com.hpremote.clone.transfer.DuplexConnector

/** Receiver side of an internet-relayed transfer. [code] is the same 6-digit PIN shown on screen. */
class RelayServerAcceptor(private val serverUrl: String, private val code: String) : DuplexAcceptor {
    @Volatile private var duplex: RelaySocketDuplex? = null

    override fun accept(): Duplex {
        val connected = RelaySocketDuplex.connect(serverUrl, code, RELAY_ROLE_HOST)
        duplex = connected
        return connected
    }

    override fun cancel() {
        duplex?.close()
    }
}

/** Sender side of an internet-relayed transfer. */
class RelayConnector(private val serverUrl: String, private val code: String) : DuplexConnector {
    override fun connect(): Duplex = RelaySocketDuplex.connect(serverUrl, code, RELAY_ROLE_PEER)
}
