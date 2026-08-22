package com.hpremote.clone.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile

private const val SNS_BACKUP_RELATIVE_DIR = "hp_control_clone/sns_backup"

/**
 * KakaoTalk/LINE/etc. keep their chat databases in app-private storage that no other
 * app can read without root. The only thing reachable without root is whatever backup
 * file the user already exported via that app's own "대화 백업/내보내기" feature - so
 * this treats a user-picked folder (Storage Access Framework tree) as a bag of opaque
 * files and copies them as-is, the same way MediaExporter/MediaImporter handle photos.
 */
object SnsBackupExporter {

    fun list(context: Context, treeUri: Uri): List<MediaFile> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val result = mutableListOf<MediaFile>()
        collect(root, "", result)
        return result
    }

    private fun collect(dir: DocumentFile, prefix: String, out: MutableList<MediaFile>) {
        for (entry in dir.listFiles()) {
            val name = entry.name ?: continue
            if (entry.isDirectory) {
                collect(entry, "$prefix$name/", out)
            } else {
                out.add(
                    MediaFile(
                        uri = entry.uri,
                        displayName = "$prefix$name",
                        mimeType = entry.type ?: "application/octet-stream",
                        size = entry.length()
                    )
                )
            }
        }
    }
}

object SnsBackupImporter {

    /** Reserves a Downloads entry under hp_control_clone/sns_backup/; write bytes, then call [finish]. */
    fun begin(context: Context, displayName: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName.substringAfterLast('/'))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SNS_BACKUP_RELATIVE_DIR")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    fun finish(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
    }
}
