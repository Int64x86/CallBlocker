package com.fundata.callblocker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DbHelper(context: Context) : SQLiteOpenHelper(context, "blocker.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE blocked_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                rule_type TEXT NOT NULL,
                rule_key TEXT NOT NULL,
                display_value TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(rule_type, rule_key)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS calls")
        db.execSQL("DROP TABLE IF EXISTS blocked_labels")
        db.execSQL("DROP TABLE IF EXISTS blocked_rules")
        onCreate(db)
    }

    fun addRule(type: String, value: String) {
        val key = normalizeRule(type, value)
        val values = ContentValues().apply {
            put("rule_type", type)
            put("rule_key", key)
            put("display_value", value.trim())
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "blocked_rules",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun removeRule(id: Long) {
        writableDatabase.delete(
            "blocked_rules",
            "id=?",
            arrayOf(id.toString())
        )
    }

    fun isBlocked(type: String, value: String?): Boolean {
        if (value.isNullOrBlank()) return false

        val normalizedValue = normalizeValue(type, value)
        return valuesByType(type).any { rule ->
            wildcardMatches(normalizeRule(type, rule), normalizedValue)
        }
    }

    fun isAnyBlocked(primaryText: String?, secondaryText: String?, number: String?): Boolean {
        return isBlocked(TYPE_TEXT, primaryText) ||
               isBlocked(TYPE_TEXT, secondaryText) ||
               isBlocked(TYPE_PHONE, number)
    }

    data class Rule(
        val id: Long,
        val type: String,
        val value: String
    )


    fun valuesByType(type: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT display_value FROM blocked_rules WHERE rule_type=? ORDER BY created_at DESC",
            arrayOf(type)
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun allRules(): List<Rule> {
        val out = ArrayList<Rule>()
        readableDatabase.rawQuery(
            "SELECT id, rule_type, display_value FROM blocked_rules ORDER BY created_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += Rule(
                    id = c.getLong(0),
                    type = c.getString(1),
                    value = c.getString(2)
                )
            }
        }
        return out
    }

    private fun normalizeRule(type: String, value: String): String {
        return when (type) {
            TYPE_PHONE -> value.filter { it.isDigit() || it == '+' || it == '*' }
            else -> value.trim().lowercase().replace(Regex("\\s+"), " ")
        }
    }

    private fun normalizeValue(type: String, value: String): String {
        return when (type) {
            TYPE_PHONE -> value.filter { it.isDigit() || it == '+' }
            else -> value.trim().lowercase().replace(Regex("\\s+"), " ")
        }
    }

    private fun wildcardMatches(pattern: String, value: String): Boolean {
        if (!pattern.contains('*')) return pattern == value

        val regex = pattern
            .split('*')
            .joinToString(".*") { Regex.escape(it) }

        return Regex("^$regex$").matches(value)
    }

    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_PHONE = "phone"
    }
}
