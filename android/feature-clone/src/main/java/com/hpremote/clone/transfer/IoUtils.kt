package com.hpremote.clone.transfer

import java.io.InputStream
import java.io.OutputStream

private const val BUFFER_SIZE = 64 * 1024

/** Copies exactly [size] bytes from [input] to [output], not relying on EOF. */
fun copyExactly(input: InputStream, output: OutputStream, size: Long) {
    val buffer = ByteArray(BUFFER_SIZE)
    var remaining = size
    while (remaining > 0) {
        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
        val read = input.read(buffer, 0, toRead)
        if (read < 0) throw java.io.EOFException("expected $remaining more bytes")
        output.write(buffer, 0, read)
        remaining -= read
    }
}

/** Reads and discards exactly [size] bytes, keeping a framed stream in sync after a skipped item. */
fun skipExactly(input: InputStream, size: Long) {
    val buffer = ByteArray(BUFFER_SIZE)
    var remaining = size
    while (remaining > 0) {
        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
        val read = input.read(buffer, 0, toRead)
        if (read < 0) throw java.io.EOFException("expected $remaining more bytes")
        remaining -= read
    }
}

fun categoryLabel(category: Category): String = when (category) {
    Category.CONTACTS -> "연락처"
    Category.CALL_LOG -> "통화 기록"
    Category.CALENDAR -> "캘린더"
    Category.SMS -> "문자 메시지"
    Category.APP_LIST -> "설치된 앱 목록"
    Category.PHOTO -> "사진"
    Category.MUSIC -> "음악"
    Category.AUDIO -> "음성 파일"
    Category.VIDEO -> "동영상"
}
