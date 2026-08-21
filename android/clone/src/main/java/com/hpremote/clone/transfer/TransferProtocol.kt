package com.hpremote.clone.transfer

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Wire format, one TCP connection per transfer:
 *
 *   client -> writeUTF(pin)
 *   server -> writeUTF("OK" | "DENY")          (closes the socket on DENY)
 *   client -> writeFrame(manifestJsonBytes)     one JSON object: {"CATEGORY": count, ...}
 *   client -> repeated items, each starting with writeUTF(categoryTag):
 *               structured (CONTACTS/CALL_LOG/CALENDAR/SMS/APP_LIST):
 *                 writeFrame(jsonArrayBytes)    every record of that category, once
 *               media (PHOTO/VIDEO):
 *                 writeFrame(metadataJsonBytes) {"name":..,"mime":..,"size":..}
 *                 <raw file bytes, exactly `size` of them, written directly>
 *   client -> writeUTF("DONE")                  ends the session
 */
const val TRANSFER_PORT = 58642

/** Same server hp_remote Agent already uses (render.yaml auto-deploys it) - just a different message type. */
const val DEFAULT_RELAY_URL = "wss://hp-remote-server.onrender.com/ws"

enum class ConnectionMethod {
    LOCAL_NETWORK,
    WIFI_DIRECT,
    RELAY
}

enum class Category(val tag: String) {
    APP_LIST("APP_LIST"),
    CONTACTS("CONTACTS"),
    CALL_LOG("CALL_LOG"),
    CALENDAR("CALENDAR"),
    SMS("SMS"),
    PHOTO("PHOTO"),
    VIDEO("VIDEO");

    val isMedia: Boolean get() = this == PHOTO || this == VIDEO

    companion object {
        fun fromTag(tag: String): Category? = values().firstOrNull { it.tag == tag }

        // Fastest-first: cheap structured records before large binary media,
        // and within structured data, roughly by per-record insert cost.
        val ORDERED: List<Category> = listOf(APP_LIST, CONTACTS, CALL_LOG, CALENDAR, SMS, PHOTO, VIDEO)
    }
}

const val TAG_DONE = "DONE"
const val TAG_OK = "OK"
const val TAG_DENY = "DENY"

/** One progress update: which category (N of total) and how far along it and the whole transfer are. */
data class TransferProgress(
    val categoryIndex: Int,
    val totalCategories: Int,
    val category: Category,
    val categoryPercent: Int,
    val overallPercent: Int,
    val message: String
)

private const val MAX_FRAME_BYTES = 200 * 1024 * 1024

fun DataOutputStream.writeFrame(bytes: ByteArray) {
    writeInt(bytes.size)
    write(bytes)
    flush()
}

fun DataInputStream.readFrame(): ByteArray {
    val len = readInt()
    require(len in 0..MAX_FRAME_BYTES) { "frame too large: $len" }
    val buf = ByteArray(len)
    readFully(buf)
    return buf
}
