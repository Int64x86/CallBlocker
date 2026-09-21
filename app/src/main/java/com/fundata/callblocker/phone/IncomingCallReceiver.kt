package com.fundata.callblocker.phone

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.fundata.callblocker.data.AppSettings
import com.fundata.callblocker.data.DbHelper
import com.fundata.callblocker.data.DiagnosticsStore
import com.fundata.callblocker.data.RuleMatcher
import com.fundata.callblocker.vibration.CallVibrationController

class IncomingCallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING) {
            DiagnosticsStore.finishIncomingCall(context)
            return
        }

        val phoneNumber = intent
            .getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            .orEmpty()
        DiagnosticsStore.beginIncomingCall(context, phoneNumber)

        if (phoneNumber.isBlank() || !AppSettings.isProtectionEnabled(context)) return

        val normalizedPhone = RuleMatcher.normalizePhone(phoneNumber)
        val blockedPhone = DbHelper(context)
            .valuesByType(DbHelper.TYPE_PHONE)
            .firstOrNull { blocked ->
                val pattern = RuleMatcher.normalizePhonePattern(blocked)
                pattern.isNotEmpty() && RuleMatcher.wildcardMatches(pattern, normalizedPhone)
            } ?: return

        DiagnosticsStore.saveDecision(context, "Заблокирован по телефону: $blockedPhone")
        CallVibrationController.blockCurrentCall(context)
        endCall(context)
    }

    @Suppress("DEPRECATION")
    private fun endCall(context: Context) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            val ended = (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()
            if (!ended) {
                DiagnosticsStore.saveError(
                    "PHONE_STATE: TelecomManager.endCall",
                    IllegalStateException("TelecomManager.endCall() returned false")
                )
            }
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("PHONE_STATE: TelecomManager.endCall", t)
        }
    }
}
