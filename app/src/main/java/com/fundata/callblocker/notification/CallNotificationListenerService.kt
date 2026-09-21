package com.fundata.callblocker.notification

import android.Manifest
import android.app.Notification
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.fundata.callblocker.data.AppSettings
import com.fundata.callblocker.data.DbHelper
import com.fundata.callblocker.data.DefaultDialerResolver
import com.fundata.callblocker.data.DiagnosticsStore
import com.fundata.callblocker.data.RuleMatcher
import com.fundata.callblocker.vibration.CallVibrationController

class CallNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            activeNotifications?.forEach(::handleNotification)
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Notification: read active notifications", t)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) handleNotification(sbn)
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        if (!AppSettings.isProtectionEnabled(this)) return
        val notification = sbn.notification ?: return
        if (notification.category != Notification.CATEGORY_CALL) return
        if (!isIncomingCallRinging()) return

        val ownerPackage = sbn.packageName.orEmpty()
        val postingPackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sbn.opPkg.orEmpty()
        } else {
            ownerPackage
        }
        if (!isTrustedCallPackage(ownerPackage, postingPackage)) return

        DiagnosticsStore.beginIncomingCall(
            this,
            DiagnosticsStore.currentIncomingNumber(this)
        )
        val texts = extractTexts(notification)
        DiagnosticsStore.saveNotificationCapture(
            this,
            ownerPackage,
            postingPackage,
            notification.category.orEmpty(),
            texts
        )

        val normalizedText = texts
            .map(RuleMatcher::normalizeText)
            .filter { it.isNotEmpty() }
            .toSet()
        val incomingNumber = DiagnosticsStore.currentIncomingNumber(this)
        val normalizedPhone = RuleMatcher.normalizePhone(incomingNumber)
        val db = DbHelper(this)

        val blockedText = db.valuesByType(DbHelper.TYPE_TEXT).firstOrNull { blocked ->
            val pattern = RuleMatcher.normalizeText(blocked)
            normalizedText.any { value -> RuleMatcher.wildcardMatches(pattern, value) }
        }
        if (blockedText != null) {
            DiagnosticsStore.saveDecision(this, "Заблокирован по уведомлению: $blockedText")
            CallVibrationController.blockCurrentCall(this)
            endCall()
            return
        }

        val blockedPhone = if (normalizedPhone.length >= 5) {
            db.valuesByType(DbHelper.TYPE_PHONE).firstOrNull { blocked ->
                val pattern = RuleMatcher.normalizePhonePattern(blocked)
                pattern.isNotEmpty() && RuleMatcher.wildcardMatches(pattern, normalizedPhone)
            }
        } else {
            null
        }
        if (blockedPhone != null) {
            DiagnosticsStore.saveDecision(this, "Заблокирован по телефону: $blockedPhone")
            CallVibrationController.blockCurrentCall(this)
            endCall()
            return
        }

        DiagnosticsStore.saveDecision(this, "Разрешён")
        CallVibrationController.allowCurrentCall(this)
    }

    private fun isTrustedCallPackage(ownerPackage: String, postingPackage: String): Boolean {
        val configured = AppSettings.callUiPackage(this)
        val defaultDialer = DefaultDialerResolver.packageName(this).orEmpty()
        return sequenceOf(ownerPackage, postingPackage).any { packageName ->
            packageName == configured ||
                packageName == defaultDialer ||
                packageName.contains("incallui", ignoreCase = true) ||
                packageName == SYSTEM_UI_PACKAGE
        }
    }

    private fun extractTexts(notification: Notification): Set<String> {
        val result = LinkedHashSet<String>()
        val extras = notification.extras
        listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
            Notification.EXTRA_VERIFICATION_TEXT
        ).forEach { key ->
            extras.getCharSequence(key)?.toString()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(result::add)
        }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            ?.let(result::addAll)
        notification.tickerText?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(result::add)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            (extras.getParcelable(Notification.EXTRA_CALL_PERSON) as? Person)
                ?.name
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(result::add)
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun isIncomingCallRinging(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        return try {
            (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).callState ==
                TelephonyManager.CALL_STATE_RINGING
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Notification: read call state", t)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun endCall() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        try {
            val ended = (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()
            if (!ended) {
                DiagnosticsStore.saveError(
                    "Notification: TelecomManager.endCall",
                    IllegalStateException("TelecomManager.endCall() returned false")
                )
            }
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Notification: TelecomManager.endCall", t)
        }
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
