package com.hpremote.clone.data

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

/** Reads every contact into `{"name":.., "phones":[..], "emails":[..]}` records. */
object ContactsExporter {

    fun count(context: Context): Int {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID),
            null, null, null
        )?.use { return it.count }
        return 0
    }

    fun export(context: Context): JSONArray {
        val resolver = context.contentResolver
        val result = JSONArray()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (cursor.moveToNext()) {
                val contactId = cursor.getString(idIdx)
                val name = cursor.getString(nameIdx) ?: ""

                val phones = JSONArray()
                resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(contactId), null
                )?.use { pc ->
                    val numIdx = pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val typeIdx = pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                    while (pc.moveToNext()) {
                        phones.put(JSONObject().apply {
                            put("number", pc.getString(numIdx) ?: "")
                            put("type", pc.getInt(typeIdx))
                        })
                    }
                }

                val emails = JSONArray()
                resolver.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.CommonDataKinds.Email.TYPE),
                    "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?", arrayOf(contactId), null
                )?.use { ec ->
                    val addrIdx = ec.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
                    val typeIdx = ec.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.TYPE)
                    while (ec.moveToNext()) {
                        emails.put(JSONObject().apply {
                            put("address", ec.getString(addrIdx) ?: "")
                            put("type", ec.getInt(typeIdx))
                        })
                    }
                }

                result.put(JSONObject().apply {
                    put("name", name)
                    put("phones", phones)
                    put("emails", emails)
                })
            }
        }
        return result
    }
}

object ContactsImporter {

    fun import(context: Context, records: JSONArray): Int {
        val resolver = context.contentResolver
        var imported = 0
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            val ops = ArrayList<ContentProviderOperation>()

            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )

            val name = record.optString("name")
            if (name.isNotBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                        .build()
                )
            }

            val phones = record.optJSONArray("phones") ?: JSONArray()
            for (p in 0 until phones.length()) {
                val phone = phones.getJSONObject(p)
                val number = phone.optString("number")
                if (number.isBlank()) continue
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, phone.optInt("type", ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE))
                        .build()
                )
            }

            val emails = record.optJSONArray("emails") ?: JSONArray()
            for (e in 0 until emails.length()) {
                val email = emails.getJSONObject(e)
                val address = email.optString("address")
                if (address.isBlank()) continue
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, address)
                        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, email.optInt("type", ContactsContract.CommonDataKinds.Email.TYPE_HOME))
                        .build()
                )
            }

            try {
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
                imported++
            } catch (e: Exception) {
                // Skip this one contact and keep going rather than aborting the whole import.
            }
        }
        return imported
    }
}
