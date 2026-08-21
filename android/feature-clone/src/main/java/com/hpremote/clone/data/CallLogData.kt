package com.hpremote.clone.data

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog
import org.json.JSONArray
import org.json.JSONObject

object CallLogExporter {

    fun count(context: Context): Int {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), null, null, null
        )?.use { return it.count }
        return 0
    }

    fun export(context: Context, onRecord: (Int, Int) -> Unit = { _, _ -> }): JSONArray {
        val result = JSONArray()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME),
            null, null, "${CallLog.Calls.DATE} ASC"
        )?.use { cursor ->
            val numIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val total = cursor.count
            while (cursor.moveToNext()) {
                result.put(JSONObject().apply {
                    put("number", cursor.getString(numIdx) ?: "")
                    put("type", cursor.getInt(typeIdx))
                    put("date", cursor.getLong(dateIdx))
                    put("duration", cursor.getLong(durIdx))
                    put("name", cursor.getString(nameIdx) ?: "")
                })
                onRecord(result.length(), total)
            }
        }
        return result
    }
}

object CallLogImporter {

    fun import(context: Context, records: JSONArray, onRecord: (Int, Int) -> Unit = { _, _ -> }): Int {
        val resolver = context.contentResolver
        var imported = 0
        val total = records.length()
        for (i in 0 until total) {
            val record = records.getJSONObject(i)
            val values = ContentValues().apply {
                put(CallLog.Calls.NUMBER, record.optString("number"))
                put(CallLog.Calls.TYPE, record.optInt("type", CallLog.Calls.INCOMING_TYPE))
                put(CallLog.Calls.DATE, record.optLong("date"))
                put(CallLog.Calls.DURATION, record.optLong("duration"))
                val name = record.optString("name")
                if (name.isNotBlank()) put(CallLog.Calls.CACHED_NAME, name)
                put(CallLog.Calls.NEW, 0)
            }
            try {
                resolver.insert(CallLog.Calls.CONTENT_URI, values)
                imported++
            } catch (e: Exception) {
                // Skip this one record and keep going.
            }
            onRecord(i + 1, total)
        }
        return imported
    }
}
