package com.hpremote.clone.transfer

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * A byte-stream connection to the other phone, regardless of how it was
 * established (plain socket over local Wi-Fi/Wi-Fi Direct, or relayed
 * through the server over the internet). [TransferClient]/[TransferServer]
 * only ever talk to this, never to a concrete transport.
 */
interface Duplex : Closeable {
    val input: DataInputStream
    val output: DataOutputStream
}

/** Sender side: blocking - connects to the other phone and returns once ready. */
interface DuplexConnector {
    fun connect(): Duplex
}

/** Receiver side: blocking - waits for the other phone to connect. [cancel] unblocks it early. */
interface DuplexAcceptor {
    fun accept(): Duplex
    fun cancel()
}
