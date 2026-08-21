package com.hpremote.clone.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Only the list of user-installed apps travels across - the app's own data
 * (saves, logins, settings) lives in a sandbox Android won't let another app
 * read without root, so there's nothing to export beyond "what to reinstall".
 */
object AppListExporter {

    private fun userApps(context: Context): List<ApplicationInfo> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
    }

    fun count(context: Context): Int = userApps(context).size

    fun export(context: Context): JSONArray {
        val pm = context.packageManager
        val result = JSONArray()
        for (app in userApps(context)) {
            result.put(JSONObject().apply {
                put("label", pm.getApplicationLabel(app).toString())
                put("packageName", app.packageName)
            })
        }
        return result
    }
}
