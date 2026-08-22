package com.hpremote.clone.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.hpremote.clone.transfer.Category
import com.hpremote.clone.transfer.SortOrder

data class MediaFile(val uri: Uri, val displayName: String, val mimeType: String, val size: Long, val dateMillis: Long = 0L)

object MediaExporter {

    private fun collectionFor(category: Category): Uri = when (category) {
        Category.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        Category.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    fun count(context: Context, category: Category): Int {
        context.contentResolver.query(
            collectionFor(category), arrayOf(MediaStore.MediaColumns._ID), null, null, null
        )?.use { return it.count }
        return 0
    }

    fun list(context: Context, category: Category, sortOrder: SortOrder = SortOrder.NEWEST_FIRST): List<MediaFile> {
        val collection = collectionFor(category)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val sql = "${MediaStore.MediaColumns.DATE_ADDED} ${if (sortOrder == SortOrder.NEWEST_FIRST) "DESC" else "ASC"}"
        val result = mutableListOf<MediaFile>()
        context.contentResolver.query(collection, projection, null, null, sql)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                result.add(
                    MediaFile(
                        uri = ContentUris.withAppendedId(collection, id),
                        displayName = cursor.getString(nameIdx) ?: "file_$id",
                        mimeType = cursor.getString(mimeIdx) ?: "application/octet-stream",
                        size = cursor.getLong(sizeIdx),
                        dateMillis = cursor.getLong(dateIdx) * 1000
                    )
                )
            }
        }
        return result
    }
}

object MediaImporter {

    private fun collectionFor(category: Category): Uri = when (category) {
        Category.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        Category.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    /** Reserves a MediaStore entry; write the raw bytes into its output stream, then call [finish]. */
    fun begin(context: Context, category: Category, displayName: String, mimeType: String): Uri? {
        val collection = collectionFor(category)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val subDir = when (category) {
                    Category.PHOTO -> Environment.DIRECTORY_PICTURES
                    Category.AUDIO -> Environment.DIRECTORY_MUSIC
                    else -> Environment.DIRECTORY_MOVIES
                }
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$subDir/hp_remote_clone")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return context.contentResolver.insert(collection, values)
    }

    fun finish(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
    }
}
