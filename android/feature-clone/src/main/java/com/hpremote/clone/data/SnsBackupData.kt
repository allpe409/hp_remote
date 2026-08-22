package com.hpremote.clone.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.hpremote.clone.transfer.SortOrder

private const val CUSTOM_BACKUP_RELATIVE_DIR = "hp_control_clone/custom"

/**
 * Backs the "파일분류" folder-picker items (압축 파일/다운로드 폴더/설치 파일/기타, and
 * previously KakaoTalk/LINE-style backups) - a user-picked folder (Storage Access
 * Framework tree) treated as a bag of opaque files and copied as-is, the same way
 * MediaExporter/MediaImporter handle photos. One instance of this per selected item,
 * distinguished on the receiving side by its group label.
 */
object SnsBackupExporter {

    fun list(context: Context, treeUri: Uri, sortOrder: SortOrder = SortOrder.NEWEST_FIRST): List<MediaFile> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val result = mutableListOf<MediaFile>()
        collect(root, "", result)
        // SAF has no query-level ordering, unlike MediaStore - sort by lastModified() here.
        return if (sortOrder == SortOrder.NEWEST_FIRST) {
            result.sortedByDescending { it.dateMillis }
        } else {
            result.sortedBy { it.dateMillis }
        }
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
                        size = entry.length(),
                        dateMillis = entry.lastModified()
                    )
                )
            }
        }
    }
}

object SnsBackupImporter {

    /** Reserves a Downloads entry under hp_control_clone/custom/<groupLabel>/; write bytes, then call [finish]. */
    fun begin(context: Context, groupLabel: String, displayName: String, mimeType: String): Uri? {
        val safeGroup = groupLabel.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "기타" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName.substringAfterLast('/'))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$CUSTOM_BACKUP_RELATIVE_DIR/$safeGroup")
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
