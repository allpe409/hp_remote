package com.hpremote.clone.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.hpremote.clone.transfer.Category

data class MediaFile(val uri: Uri, val displayName: String, val mimeType: String, val size: Long)

object MediaExporter {

    private fun collectionFor(category: Category): Uri =
        if (category == Category.PHOTO) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    fun count(context: Context, category: Category): Int {
        context.contentResolver.query(
            collectionFor(category), arrayOf(MediaStore.MediaColumns._ID), null, null, null
        )?.use { return it.count }
        return 0
    }

    fun list(context: Context, category: Category): List<MediaFile> {
        val collection = collectionFor(category)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE
        )
        val result = mutableListOf<MediaFile>()
        context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                result.add(
                    MediaFile(
                        uri = ContentUris.withAppendedId(collection, id),
                        displayName = cursor.getString(nameIdx) ?: "file_$id",
                        mimeType = cursor.getString(mimeIdx) ?: "application/octet-stream",
                        size = cursor.getLong(sizeIdx)
                    )
                )
            }
        }
        return result
    }
}

object MediaImporter {

    private fun collectionFor(category: Category): Uri =
        if (category == Category.PHOTO) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    /** Reserves a MediaStore entry; write the raw bytes into its output stream, then call [finish]. */
    fun begin(context: Context, category: Category, displayName: String, mimeType: String): Uri? {
        val collection = collectionFor(category)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val subDir = if (category == Category.PHOTO) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
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
