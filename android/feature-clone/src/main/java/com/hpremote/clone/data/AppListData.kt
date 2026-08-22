package com.hpremote.clone.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Only the list of user-installed apps travels across - the app's own data
 * (saves, logins, settings) lives in a sandbox Android won't let another app
 * read without root, so there's nothing to export beyond "what to reinstall".
 * Each app's backup method is different (and risky to automate), so this stays
 * a name list rather than an attempt at a real per-app data copy.
 */
data class AppEntry(val label: String, val packageName: String, val apkSizeBytes: Long)

object AppListExporter {

    /** Installed non-system apps, label-sorted - for the "앱목록" checklist. */
    fun installedApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map {
                AppEntry(
                    label = pm.getApplicationLabel(it).toString(),
                    packageName = it.packageName,
                    apkSizeBytes = it.sourceDir?.let { path -> File(path).length() } ?: 0L
                )
            }
            .sortedBy { it.label }
    }

    /** Builds the wire record straight from what's already on screen - no re-querying PackageManager. */
    fun exportSelected(selected: List<AppEntry>, onRecord: (Int, Int) -> Unit = { _, _ -> }): JSONArray {
        val result = JSONArray()
        for (app in selected) {
            result.put(JSONObject().apply {
                put("label", app.label)
                put("packageName", app.packageName)
            })
            onRecord(result.length(), selected.size)
        }
        return result
    }
}
