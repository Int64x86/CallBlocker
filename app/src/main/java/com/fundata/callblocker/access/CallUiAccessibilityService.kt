package com.fundata.callblocker.access

import android.Manifest
import android.app.Notification
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.telephony.PhoneStateListener
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.fundata.callblocker.data.AppSettings
import com.fundata.callblocker.data.DbHelper
import com.fundata.callblocker.data.DefaultDialerResolver
import com.fundata.callblocker.data.DiagnosticsStore
import com.fundata.callblocker.data.RuleMatcher
import com.fundata.callblocker.vibration.CallVibrationController

class CallUiAccessibilityService : AccessibilityService() {
    private val idleCallUiWindowIds = HashSet<Int>()
    private var incomingCallWindowId = INVALID_WINDOW_ID
    private var ringingStartedAt = 0L
    private var lastPhoneState = TelephonyManager.CALL_STATE_IDLE

    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            if (state == TelephonyManager.CALL_STATE_RINGING &&
                lastPhoneState != TelephonyManager.CALL_STATE_RINGING
            ) {
                beginIncomingCallWindowSelection()
            } else if (state != TelephonyManager.CALL_STATE_RINGING) {
                incomingCallWindowId = INVALID_WINDOW_ID
            }
            lastPhoneState = state
            CallVibrationController.onCallStateChanged(
                this@CallUiAccessibilityService,
                state
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun onServiceConnected() {
        super.onServiceConnected()
        CallVibrationController.syncPersistentState(this)
        captureIdleCallUiWindows()
        try {
            (getSystemService(TELEPHONY_SERVICE) as TelephonyManager).listen(
                phoneStateListener,
                PhoneStateListener.LISTEN_CALL_STATE
            )
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Accessibility: register PhoneStateListener", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AppSettings.isProtectionEnabled(this)) {
            CallVibrationController.syncPersistentState(this)
            return
        }
        if (event == null) return
        if (!isIncomingCallRinging()) {
            captureIdleCallUiWindows()
            incomingCallWindowId = INVALID_WINDOW_ID
            lastPhoneState = TelephonyManager.CALL_STATE_IDLE
            CallVibrationController.cancelIfCallEnded(this)
            return
        }
        if (lastPhoneState != TelephonyManager.CALL_STATE_RINGING) {
            beginIncomingCallWindowSelection()
            lastPhoneState = TelephonyManager.CALL_STATE_RINGING
        }

        val texts = LinkedHashSet<String>()
        val windowPackages = LinkedHashSet<String>()
        collectEventTexts(event, texts, windowPackages)
        DiagnosticsStore.currentIncomingNumber(this)
            .takeIf { it.isNotBlank() }
            ?.let(texts::add)
        if (texts.isEmpty() && windowPackages.isEmpty()) return
        DiagnosticsStore.saveCapture(
            this,
            event,
            texts,
            windowPackages,
            incomingCallWindowId
        )
        if (texts.isEmpty()) return

        val normalizedText = texts
            .map(RuleMatcher::normalizeText)
            .filter { it.isNotEmpty() }
            .toSet()

        val normalizedPhones = texts
            .map(RuleMatcher::normalizePhone)
            .filter { it.length >= 5 }
            .toSet()

        val db = DbHelper(this)

        val blockedText = db.valuesByType(DbHelper.TYPE_TEXT).firstOrNull { blocked ->
            val pattern = RuleMatcher.normalizeText(blocked)
            normalizedText.any { value -> RuleMatcher.wildcardMatches(pattern, value) }
        }
        if (blockedText != null) {
            DiagnosticsStore.saveDecision(this, "Заблокирован по тексту: $blockedText")
            CallVibrationController.blockCurrentCall(this)
            endCall()
            return
        }

        val blockedPhone = db.valuesByType(DbHelper.TYPE_PHONE).firstOrNull { blocked ->
            val pattern = RuleMatcher.normalizePhonePattern(blocked)
            pattern.isNotEmpty() && normalizedPhones.any { value ->
                RuleMatcher.wildcardMatches(pattern, value)
            }
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

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        try {
            (getSystemService(TELEPHONY_SERVICE) as TelephonyManager).listen(
                phoneStateListener,
                PhoneStateListener.LISTEN_NONE
            )
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Accessibility: unregister PhoneStateListener", t)
        }
        CallVibrationController.shutdown(this)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    @Suppress("DEPRECATION")
    private fun isIncomingCallRinging(): Boolean {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        return try {
            val telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            telephony.callState == TelephonyManager.CALL_STATE_RINGING
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Accessibility: read call state", t)
            false
        }
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableSet<String>) {
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, out)
            child.recycle()
        }
    }

    private fun beginIncomingCallWindowSelection() {
        incomingCallWindowId = INVALID_WINDOW_ID
        ringingStartedAt = SystemClock.uptimeMillis()
    }

    private fun captureIdleCallUiWindows() {
        val defaultDialerPackage = DefaultDialerResolver.packageName(this).orEmpty()
        if (defaultDialerPackage.isBlank()) return

        val ids = HashSet<Int>()
        val interactiveWindows = try {
            windows
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Accessibility: snapshot idle windows", t)
            return
        }
        interactiveWindows.forEach { window ->
            val root = try {
                window.root
            } catch (t: Throwable) {
                DiagnosticsStore.saveError(
                    "Accessibility: snapshot root for window ${window.id}",
                    t
                )
                null
            } ?: return@forEach
            try {
                if (root.packageName?.toString() == defaultDialerPackage) {
                    ids.add(window.id)
                }
            } finally {
                root.recycle()
            }
        }
        idleCallUiWindowIds.clear()
        idleCallUiWindowIds.addAll(ids)
    }

    private fun collectEventTexts(
        event: AccessibilityEvent,
        out: MutableSet<String>,
        windowPackages: MutableSet<String>
    ) {
        val notification = event.parcelableData as? Notification
        if (notification?.category == Notification.CATEGORY_CALL) {
            val extras = notification.extras
            listOf(
                Notification.EXTRA_TITLE,
                Notification.EXTRA_TEXT,
                Notification.EXTRA_SUB_TEXT,
                Notification.EXTRA_BIG_TEXT
            ).forEach { key ->
                extras.getCharSequence(key)?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(out::add)
            }
        }

        val defaultDialerPackage = DefaultDialerResolver.packageName(this).orEmpty()
        val configuredCallUiPackage = AppSettings.callUiPackage(this)
        val interactiveWindows = try {
            windows
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Accessibility: get interactive windows", t)
            emptyList()
        }
        data class WindowCandidate(
            val id: Int,
            val layer: Int,
            val packageName: String,
            val texts: Set<String>
        )

        val candidates = ArrayList<WindowCandidate>()
        val seenSelectedWindowIds = HashSet<Int>()
        var trustedWindowTexts: Set<String>? = null
        var bestCandidateId = INVALID_WINDOW_ID
        var bestCandidateLayer = Int.MIN_VALUE
        var bestCandidateTexts: Set<String> = emptySet()

        interactiveWindows.forEach { window ->
            val root = try {
                window.root
            } catch (t: Throwable) {
                DiagnosticsStore.saveError("Accessibility: get root for window ${window.id}", t)
                null
            } ?: return@forEach

            try {
                val packageName = root.packageName?.toString().orEmpty()
                if (packageName.isNotBlank()) windowPackages.add(packageName)

                val isAutomaticCandidate = packageName.contains("incallui", ignoreCase = true) ||
                    packageName == defaultDialerPackage
                val isManualFallback = packageName == configuredCallUiPackage
                if (!isAutomaticCandidate && !isManualFallback) return@forEach

                val windowTexts = LinkedHashSet<String>()
                collectTexts(root, windowTexts)
                candidates += WindowCandidate(window.id, window.layer, packageName, windowTexts)
            } finally {
                root.recycle()
            }
        }

        val selectedPackage = candidates
            .filter { it.packageName.contains("incallui", ignoreCase = true) }
            .maxByOrNull { it.layer }
            ?.packageName
            ?: defaultDialerPackage.takeIf { dialer -> candidates.any { it.packageName == dialer } }
            ?: configuredCallUiPackage.takeIf { configured ->
                candidates.any { it.packageName == configured }
            }
            ?: return

        if (selectedPackage != configuredCallUiPackage) {
            AppSettings.setCallUiPackage(this, selectedPackage)
        }

        candidates.filter { it.packageName == selectedPackage }.forEach { candidate ->
            seenSelectedWindowIds.add(candidate.id)
            val isCurrentCallWindow = candidate.id == incomingCallWindowId
            val activationDelay = event.eventTime - ringingStartedAt
            val isFreshActivation =
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    candidate.id == event.windowId &&
                    activationDelay in 0..CALL_WINDOW_ACTIVATION_MS
            val isNewWindow = candidate.id !in idleCallUiWindowIds

            if (isCurrentCallWindow) {
                trustedWindowTexts = candidate.texts
            } else if ((isNewWindow || isFreshActivation) && candidate.layer >= bestCandidateLayer) {
                bestCandidateId = candidate.id
                bestCandidateLayer = candidate.layer
                bestCandidateTexts = candidate.texts
            }
        }

        if (incomingCallWindowId !in seenSelectedWindowIds) {
            incomingCallWindowId = INVALID_WINDOW_ID
        }
        if (incomingCallWindowId != INVALID_WINDOW_ID && trustedWindowTexts != null) {
            out.addAll(trustedWindowTexts.orEmpty())
        } else if (bestCandidateId != INVALID_WINDOW_ID) {
            incomingCallWindowId = bestCandidateId
            out.addAll(bestCandidateTexts)
        }

    }

    @Suppress("DEPRECATION")
    private fun endCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ANSWER_PHONE_CALLS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val ended = (getSystemService(TELECOM_SERVICE) as TelecomManager).endCall()
                if (!ended) {
                    DiagnosticsStore.saveError(
                        "Accessibility: TelecomManager.endCall",
                        IllegalStateException("TelecomManager.endCall() returned false")
                    )
                }
            }
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Accessibility: TelecomManager.endCall", t)
        }
    }

    companion object {
        private const val INVALID_WINDOW_ID = -1
        private const val CALL_WINDOW_ACTIVATION_MS = 4_000L
    }
}
