package com.fundata.callblocker.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

class SystemCallLogReader(private val context: Context) {

    fun read(limit: Int = 300): List<CallRecord> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val calls = ArrayList<CallRecord>()
        val contactNames = HashMap<String, String?>()
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            null,
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        ) ?: return emptyList()

        cursor.use { c ->
            val idIndex = c.getColumnIndex(CallLog.Calls._ID)
            val numberIndex = c.getColumnIndex(CallLog.Calls.NUMBER)
            val nameIndex = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val dateIndex = c.getColumnIndex(CallLog.Calls.DATE)
            val typeIndex = c.getColumnIndex(CallLog.Calls.TYPE)
            val preferredIndex = c.getColumnIndex("preferred_display_name")

            var count = 0
            while (c.moveToNext() && count < limit) {
                val number = if (numberIndex >= 0 && !c.isNull(numberIndex)) {
                    c.getString(numberIndex)
                } else {
                    "Скрытый номер"
                }
                val contactName = lookupContactName(number, contactNames)
                calls += CallRecord(
                    id = if (idIndex >= 0) c.getLong(idIndex) else count.toLong(),
                    number = number,
                    preferredDisplayName = stringOrNull(c, preferredIndex),
                    cachedName = contactName ?: stringOrNull(c, nameIndex),
                    timestamp = if (dateIndex >= 0) c.getLong(dateIndex) else 0L,
                    type = if (typeIndex >= 0) c.getInt(typeIndex) else 0
                )
                count++
            }
        }
        return calls
    }

    private fun stringOrNull(c: android.database.Cursor, index: Int): String? {
        if (index < 0 || c.isNull(index)) return null
        return c.getString(index)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun lookupContactName(
        number: String,
        cache: MutableMap<String, String?>
    ): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED || number == "Скрытый номер"
        ) return null
        if (cache.containsKey(number)) return cache[number]

        val name = try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() }
                else null
            }
        } catch (_: Throwable) {
            null
        }
        cache[number] = name
        return name
    }

    fun findRecentByNumber(number: String, maxAgeMs: Long = 15_000): CallRecord? {
        val normalized = number.filter(Char::isDigit)
        return read(20).firstOrNull {
            System.currentTimeMillis() - it.timestamp <= maxAgeMs &&
                it.number.filter(Char::isDigit).endsWith(normalized.takeLast(7))
        }
    }
}
