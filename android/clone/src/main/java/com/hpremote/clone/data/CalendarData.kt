package com.hpremote.clone.data

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

object CalendarExporter {

    fun count(context: Context): Int {
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID), null, null, null
        )?.use { return it.count }
        return 0
    }

    fun export(context: Context): JSONArray {
        val result = JSONArray()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_TIMEZONE
            ),
            null, null, null
        )?.use { cursor ->
            val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val descIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
            val locIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
            val startIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
            val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
            val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
            val tzIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_TIMEZONE)
            while (cursor.moveToNext()) {
                // DTSTART/DTEND can be null for recurring events defined only via
                // RRULE/DURATION; skip those rather than exporting a broken event.
                if (cursor.isNull(startIdx)) continue
                result.put(JSONObject().apply {
                    put("title", cursor.getString(titleIdx) ?: "")
                    put("description", cursor.getString(descIdx) ?: "")
                    put("location", cursor.getString(locIdx) ?: "")
                    put("start", cursor.getLong(startIdx))
                    put("end", if (cursor.isNull(endIdx)) cursor.getLong(startIdx) else cursor.getLong(endIdx))
                    put("allDay", cursor.getInt(allDayIdx) != 0)
                    put("timezone", cursor.getString(tzIdx) ?: TimeZone.getDefault().id)
                })
            }
        }
        return result
    }
}

object CalendarImporter {

    private const val LOCAL_CALENDAR_NAME = "hp_remote 복제"

    private fun findOrCreateLocalCalendar(context: Context): Long {
        val resolver = context.contentResolver
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.NAME} = ?",
            arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, LOCAL_CALENDAR_NAME), null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_CALENDAR_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, LOCAL_CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, LOCAL_CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF2196F3.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_CALENDAR_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri = resolver.insert(
            CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_CALENDAR_NAME)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build(),
            values
        ) ?: throw IllegalStateException("failed to create local calendar")
        return uri.lastPathSegment!!.toLong()
    }

    fun import(context: Context, records: JSONArray): Int {
        val resolver = context.contentResolver
        val calendarId = findOrCreateLocalCalendar(context)
        var imported = 0
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, record.optString("title"))
                put(CalendarContract.Events.DESCRIPTION, record.optString("description"))
                put(CalendarContract.Events.EVENT_LOCATION, record.optString("location"))
                put(CalendarContract.Events.DTSTART, record.optLong("start"))
                put(CalendarContract.Events.DTEND, record.optLong("end"))
                put(CalendarContract.Events.ALL_DAY, if (record.optBoolean("allDay")) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, record.optString("timezone", TimeZone.getDefault().id))
            }
            try {
                resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                imported++
            } catch (e: Exception) {
                // Skip this one record and keep going.
            }
        }
        return imported
    }
}
