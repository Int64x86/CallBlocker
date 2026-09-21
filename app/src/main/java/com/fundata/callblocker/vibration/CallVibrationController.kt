package com.fundata.callblocker.vibration

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.TelephonyManager
import com.fundata.callblocker.data.AppSettings
import com.fundata.callblocker.data.DiagnosticsStore

object CallVibrationController {
    private const val DELAY_MS = 1_000L
    private const val CALL_STATE_CHECK_MS = 250L

    private val handler = Handler(Looper.getMainLooper())
    private var vibrationScheduled = false
    private var vibrationStarted = false
    private var currentCallAllowed = false
    private var currentCallBlocked = false
    private var lastCallState = TelephonyManager.CALL_STATE_IDLE
    private var applicationContext: Context? = null

    private val startVibration = Runnable { startVibrationNow() }
    private val stopWhenCallEnds = Runnable { cancelIfCallEnded() }

    @Synchronized
    private fun startVibrationNow() {
        vibrationScheduled = false
        val context = applicationContext ?: return
        if (!currentCallAllowed || currentCallBlocked || !isActive(context) || !isRinging(context)) {
            return
        }

        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return

        val effect = VibrationEffect.createWaveform(longArrayOf(0L, 1_000L, 1_000L), 0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE)
                )
            } else {
                @Suppress("DEPRECATION")
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, attributes)
            }
            vibrationStarted = true
            handler.postDelayed(stopWhenCallEnds, CALL_STATE_CHECK_MS)
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Vibration: start custom vibration", t)
        }
    }

    @Synchronized
    private fun cancelIfCallEnded() {
        val context = applicationContext ?: return
        if (!isRinging(context)) {
            currentCallAllowed = false
            cancelOwnVibration(context)
            return
        }
        if (vibrationStarted) handler.postDelayed(stopWhenCallEnds, CALL_STATE_CHECK_MS)
    }

    @Synchronized
    fun cancelIfCallEnded(context: Context) {
        applicationContext = context.applicationContext
        if (!isRinging(context)) cancelIfCallEnded()
    }

    @Synchronized
    fun syncPersistentState(context: Context) {
        applicationContext = context.applicationContext
        if (!isActive(context)) cancelOwnVibration(context)
    }

    @Synchronized
    fun onCallStateChanged(context: Context, state: Int) {
        applicationContext = context.applicationContext
        if (!isActive(context)) {
            cancelOwnVibration(context)
            currentCallAllowed = false
            currentCallBlocked = false
            lastCallState = state
            return
        }

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (lastCallState != TelephonyManager.CALL_STATE_RINGING) {
                    cancelOwnVibration(context)
                    currentCallAllowed = false
                    currentCallBlocked = false
                }
            }
            TelephonyManager.CALL_STATE_IDLE,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                cancelOwnVibration(context)
                currentCallAllowed = false
                currentCallBlocked = false
            }
        }
        lastCallState = state
    }

    @Synchronized
    fun allowCurrentCall(context: Context) {
        if (!isActive(context) || currentCallBlocked || vibrationScheduled || vibrationStarted) return
        applicationContext = context.applicationContext
        currentCallAllowed = true
        vibrationScheduled = true
        handler.postDelayed(startVibration, DELAY_MS)
    }

    @Synchronized
    fun blockCurrentCall(context: Context) {
        currentCallBlocked = true
        currentCallAllowed = false
        cancelOwnVibration(context)
    }

    @Synchronized
    fun shutdown(context: Context) {
        cancelOwnVibration(context)
        currentCallAllowed = false
        currentCallBlocked = false
        lastCallState = TelephonyManager.CALL_STATE_IDLE
    }

    private fun isActive(context: Context): Boolean =
        AppSettings.isProtectionEnabled(context) && AppSettings.isVibrationDelayEnabled(context)

    @Suppress("DEPRECATION")
    private fun isRinging(context: Context): Boolean = try {
        (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).callState ==
            TelephonyManager.CALL_STATE_RINGING
    } catch (t: Throwable) {
        DiagnosticsStore.saveError("Vibration: read call state", t)
        false
    }

    private fun cancelOwnVibration(context: Context) {
        handler.removeCallbacks(startVibration)
        handler.removeCallbacks(stopWhenCallEnds)
        vibrationScheduled = false
        try {
            context.getSystemService(Vibrator::class.java)?.cancel()
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Vibration: cancel custom vibration", t)
        }
        vibrationStarted = false
    }
}
