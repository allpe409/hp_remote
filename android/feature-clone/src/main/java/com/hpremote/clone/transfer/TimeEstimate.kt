package com.hpremote.clone.transfer

import android.content.Context
import com.hpremote.clone.data.AppListExporter
import com.hpremote.clone.data.CalendarExporter
import com.hpremote.clone.data.CallLogExporter
import com.hpremote.clone.data.ContactsExporter
import com.hpremote.clone.data.MediaExporter
import com.hpremote.clone.data.SmsExporter

/** One category's size (record count, and total bytes for media) and rough time estimate. */
data class CategoryEstimate(
    val category: Category,
    val count: Int,
    val totalBytes: Long,
    val estimatedMs: Long
)

// Rough, per-record insert cost - real speed depends on the device and network,
// so these only produce a ballpark shown to the user, not a promise.
private const val MS_PER_CONTACT = 30L
private const val MS_PER_SIMPLE_RECORD = 10L // call log / calendar / SMS / app list

// Conservative assumed throughput for local Wi-Fi transfer + MediaStore write overhead.
private const val MEDIA_BYTES_PER_MS = 8L * 1024 * 1024 / 1000 // 8 MB/s

object TimeEstimate {

    /** Queries just what's needed to size up [category] - counts for structured data, sizes for media. */
    fun estimate(context: Context, category: Category): CategoryEstimate {
        if (category.isMedia) {
            val files = MediaExporter.list(context, category)
            val totalBytes = files.sumOf { it.size }
            return CategoryEstimate(category, files.size, totalBytes, totalBytes / MEDIA_BYTES_PER_MS)
        }
        val count = when (category) {
            Category.CONTACTS -> ContactsExporter.count(context)
            Category.CALL_LOG -> CallLogExporter.count(context)
            Category.CALENDAR -> CalendarExporter.count(context)
            Category.SMS -> SmsExporter.count(context)
            Category.APP_LIST -> AppListExporter.count(context)
            else -> 0
        }
        val msPerRecord = if (category == Category.CONTACTS) MS_PER_CONTACT else MS_PER_SIMPLE_RECORD
        return CategoryEstimate(category, count, 0L, count.toLong() * msPerRecord)
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(if (ms > 0) 1 else 0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}분 ${seconds}초" else "${seconds}초"
    }
}
