package com.fundata.callblocker.data

import android.content.Context

object AppSettings {
    private const val PREF = "settings"
    private const val KEY_ENABLED = "protection_enabled"
    private const val KEY_VIBRATION_DELAY = "vibration_delay_enabled"
    private const val KEY_CALL_UI_PACKAGE = "call_ui_package"

    fun isProtectionEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setProtectionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun isVibrationDelayEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_VIBRATION_DELAY, false)

    fun setVibrationDelayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VIBRATION_DELAY, enabled)
            .apply()
    }

    fun callUiPackage(context: Context): String {
        val saved = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_CALL_UI_PACKAGE, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val defaultDialer = DefaultDialerResolver.packageName(context)
        val detected = DefaultDialerResolver.callUiPackageName(context)
        return when {
            saved == null -> detected.orEmpty()
            saved == defaultDialer && detected != null -> detected
            else -> saved
        }
    }

    fun setCallUiPackage(context: Context, packageName: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CALL_UI_PACKAGE, packageName.trim())
            .apply()
    }
}
