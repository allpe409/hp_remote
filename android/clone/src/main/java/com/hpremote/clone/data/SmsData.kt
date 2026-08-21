package com.hpremote.clone.data

import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

object SmsExporter {

    fun count(context: Context): Int {
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, null
        )?.use { return it.count }
        return 0
    }

    fun export(context: Context): JSONArray {
        val result = JSONArray()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
            null, null, "${Telephony.Sms.DATE} ASC"
        )?.use { cursor ->
            val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val readIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            while (cursor.moveToNext()) {
                result.put(JSONObject().apply {
                    put("address", cursor.getString(addrIdx) ?: "")
                    put("body", cursor.getString(bodyIdx) ?: "")
                    put("date", cursor.getLong(dateIdx))
                    put("type", cursor.getInt(typeIdx))
                    put("read", cursor.getInt(readIdx))
                })
            }
        }
        return result
    }
}

object SmsImporter {

    /** True once this app is allowed to write into the SMS provider. */
    fun isDefaultSmsApp(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }
    }

    fun import(context: Context, records: JSONArray): Int {
        val resolver = context.contentResolver
        var imported = 0
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, record.optString("address"))
                put(Telephony.Sms.BODY, record.optString("body"))
                put(Telephony.Sms.DATE, record.optLong("date"))
                put(Telephony.Sms.TYPE, record.optInt("type", Telephony.Sms.MESSAGE_TYPE_INBOX))
                put(Telephony.Sms.READ, record.optInt("read", 1))
            }
            try {
                resolver.insert(Telephony.Sms.CONTENT_URI, values)
                imported++
            } catch (e: Exception) {
                // Skip this one record (e.g. the default-SMS role isn't held) and keep going.
            }
        }
        return imported
    }
}
