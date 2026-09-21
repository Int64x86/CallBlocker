package com.fundata.callblocker.data

object RuleMatcher {
    fun normalizeText(value: String): String =
        value.trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")

    fun normalizePhone(value: String): String =
        value.filter { it.isDigit() || it == '+' }
            .let { raw ->
                if (raw.startsWith("+")) "+" + raw.drop(1).filter(Char::isDigit)
                else raw.filter(Char::isDigit)
            }

    fun normalizePhonePattern(value: String): String =
        value.filter { it.isDigit() || it == '+' || it == '*' }

    fun wildcardMatches(pattern: String, value: String): Boolean {
        if (!pattern.contains('*')) return pattern == value

        val regex = pattern
            .split('*')
            .joinToString(".*") { Regex.escape(it) }

        return Regex("^$regex$").matches(value)
    }
}
