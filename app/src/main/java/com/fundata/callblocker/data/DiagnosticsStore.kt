package com.fundata.callblocker.data

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsStore {
    private const val PREF = "diagnostics"
    private const val KEY_TIME = "time"
    private const val KEY_PACKAGE = "package"
    private const val KEY_CLASS = "class"
    private const val KEY_EVENT_TYPE = "event_type"
    private const val KEY_TEXT = "text"
    private const val KEY_DECISION = "decision"
    private const val KEY_INCOMING_NUMBER = "incoming_number"
    private const val KEY_WINDOW_PACKAGES = "window_packages"
    private const val KEY_CALL_WINDOW_ID = "call_window_id"
    private const val KEY_NOTIFICATION_PACKAGE = "notification_package"
    private const val KEY_NOTIFICATION_OP_PACKAGE = "notification_op_package"
    private const val KEY_NOTIFICATION_CATEGORY = "notification_category"
    private const val KEY_NOTIFICATION_TEXT = "notification_text"
    private const val KEY_CALL_ACTIVE = "call_active"
    private const val KEY_ERRORS = "errors"
    private const val KEY_ERROR_FORMAT = "error_format"
    private const val ERROR_SEPARATOR = "\u001e"
    private const val MAX_ERRORS = 30
    private const val ERROR_FORMAT = 2
    @Volatile private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_ERROR_FORMAT, 0) < ERROR_FORMAT) {
            prefs.edit()
                .remove(KEY_ERRORS)
                .putInt(KEY_ERROR_FORMAT, ERROR_FORMAT)
                .apply()
        }
    }

    @Synchronized
    fun saveError(location: String, error: Throwable, immediate: Boolean = false) {
        try {
            val context = applicationContext ?: return
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val timestamp = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.US
            ).format(Date())
            val stack = error.stackTrace
                .take(8)
                .joinToString("\n") { "  at $it" }
            val entry = buildString {
                append("$timestamp | $location\n")
                append(error.javaClass.name)
                error.message?.takeIf { it.isNotBlank() }?.let { append(": $it") }
                if (stack.isNotBlank()) append("\n$stack")
            }
            val errors = sequenceOf(entry) + prefs.getString(KEY_ERRORS, "")
                .orEmpty()
                .split(ERROR_SEPARATOR)
                .asSequence()
                .filter { it.isNotBlank() }
            val editor = prefs.edit().putString(
                KEY_ERRORS,
                errors.take(MAX_ERRORS).joinToString(ERROR_SEPARATOR)
            )
            if (immediate) editor.commit() else editor.apply()
        } catch (_: Throwable) {
            // Журнал ошибок не должен сам завершать приложение.
        }
    }

    fun beginIncomingCall(context: Context, phoneNumber: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        if (!prefs.getBoolean(KEY_CALL_ACTIVE, false)) {
            edit
                .putLong(KEY_TIME, System.currentTimeMillis())
                .putString(KEY_PACKAGE, "")
                .putString(KEY_CLASS, "")
                .putInt(KEY_EVENT_TYPE, 0)
                .putString(KEY_TEXT, "")
                .putString(KEY_INCOMING_NUMBER, "")
                .putString(KEY_WINDOW_PACKAGES, "")
                .putInt(KEY_CALL_WINDOW_ID, -1)
                .putString(KEY_NOTIFICATION_PACKAGE, "")
                .putString(KEY_NOTIFICATION_OP_PACKAGE, "")
                .putString(KEY_NOTIFICATION_CATEGORY, "")
                .putString(KEY_NOTIFICATION_TEXT, "")
                .putString(KEY_DECISION, "Ожидание данных")
        }
        edit.putBoolean(KEY_CALL_ACTIVE, true)
        if (phoneNumber.isNotBlank()) edit.putString(KEY_INCOMING_NUMBER, phoneNumber)
        edit.apply()
    }

    fun finishIncomingCall(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CALL_ACTIVE, false)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putInt(KEY_ERROR_FORMAT, ERROR_FORMAT)
            .apply()
    }

    fun currentIncomingNumber(context: Context): String {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_CALL_ACTIVE, false)) return ""
        return prefs.getString(KEY_INCOMING_NUMBER, "").orEmpty()
    }

    fun saveCapture(
        context: Context,
        event: AccessibilityEvent,
        texts: Collection<String>,
        windowPackages: Collection<String>,
        callWindowId: Int
    ) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val packages = prefs.getString(KEY_WINDOW_PACKAGES, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toMutableSet()
        packages.addAll(windowPackages.filter { it.isNotBlank() })
        event.packageName?.toString()?.takeIf { it.isNotBlank() }?.let(packages::add)

        prefs.edit()
            .putLong(KEY_TIME, System.currentTimeMillis())
            .putString(KEY_PACKAGE, event.packageName?.toString().orEmpty())
            .putString(KEY_CLASS, event.className?.toString().orEmpty())
            .putInt(KEY_EVENT_TYPE, event.eventType)
            .putString(KEY_TEXT, texts.joinToString("\n"))
            .putString(KEY_WINDOW_PACKAGES, packages.sorted().joinToString("\n"))
            .putInt(KEY_CALL_WINDOW_ID, callWindowId)
            .putString(KEY_DECISION, "Проверяется")
            .apply()
    }

    fun saveDecision(context: Context, decision: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DECISION, decision)
            .apply()
    }

    fun saveNotificationCapture(
        context: Context,
        packageName: String,
        opPackageName: String,
        category: String,
        texts: Collection<String>
    ) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_TIME, System.currentTimeMillis())
            .putString(KEY_NOTIFICATION_PACKAGE, packageName)
            .putString(KEY_NOTIFICATION_OP_PACKAGE, opPackageName)
            .putString(KEY_NOTIFICATION_CATEGORY, category)
            .putString(KEY_NOTIFICATION_TEXT, texts.joinToString("\n"))
            .putString(KEY_DECISION, "Проверяется уведомление")
            .apply()
    }

    fun read(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Snapshot(
            time = prefs.getLong(KEY_TIME, 0L),
            packageName = prefs.getString(KEY_PACKAGE, "").orEmpty(),
            className = prefs.getString(KEY_CLASS, "").orEmpty(),
            eventType = prefs.getInt(KEY_EVENT_TYPE, 0),
            text = prefs.getString(KEY_TEXT, "").orEmpty(),
            decision = prefs.getString(KEY_DECISION, "Нет данных").orEmpty(),
            incomingNumber = prefs.getString(KEY_INCOMING_NUMBER, "").orEmpty(),
            windowPackages = prefs.getString(KEY_WINDOW_PACKAGES, "").orEmpty(),
            callWindowId = prefs.getInt(KEY_CALL_WINDOW_ID, -1),
            notificationPackage = prefs.getString(KEY_NOTIFICATION_PACKAGE, "").orEmpty(),
            notificationOpPackage = prefs.getString(KEY_NOTIFICATION_OP_PACKAGE, "").orEmpty(),
            notificationCategory = prefs.getString(KEY_NOTIFICATION_CATEGORY, "").orEmpty(),
            notificationText = prefs.getString(KEY_NOTIFICATION_TEXT, "").orEmpty(),
            errors = prefs.getString(KEY_ERRORS, "")
                .orEmpty()
                .split(ERROR_SEPARATOR)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        )
    }

    data class Snapshot(
        val time: Long,
        val packageName: String,
        val className: String,
        val eventType: Int,
        val text: String,
        val decision: String,
        val incomingNumber: String,
        val windowPackages: String,
        val callWindowId: Int,
        val notificationPackage: String,
        val notificationOpPackage: String,
        val notificationCategory: String,
        val notificationText: String,
        val errors: String
    )
}
