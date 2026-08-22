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

// Order to send files within a file-based category. Large media transfers can get
// interrupted partway through, so newest-first (the default) makes sure the most
// recent - usually most wanted - photos/videos/audio land first.
enum class SortOrder {
    NEWEST_FIRST,
    OLDEST_FIRST
}

enum class Category(val tag: String) {
    APP_LIST("APP_LIST"),
    CONTACTS("CONTACTS"),
    CALL_LOG("CALL_LOG"),
    CALENDAR("CALENDAR"),
    SMS("SMS"),
    PHOTO("PHOTO"),
    AUDIO("AUDIO"),
    VIDEO("VIDEO"),
    SNS_BACKUP("SNS_BACKUP");

    // True for categories carried as raw files (metadata frame + exact-size byte copy)
    // rather than a single JSON array of structured records.
    val isFileBased: Boolean get() = this == PHOTO || this == AUDIO || this == VIDEO || this == SNS_BACKUP

    companion object {
        fun fromTag(tag: String): Category? = values().firstOrNull { it.tag == tag }

        // Fastest-first: cheap structured records before large binary media. SNS_BACKUP
        // goes last - it's an arbitrary user-picked folder, so it's the least predictable.
        val ORDERED: List<Category> = listOf(APP_LIST, CONTACTS, CALL_LOG, CALENDAR, SMS, PHOTO, AUDIO, VIDEO, SNS_BACKUP)
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
