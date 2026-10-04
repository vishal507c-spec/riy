package com.vishal.riy.guard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import com.vishal.riy.R
import com.vishal.riy.guard.rules.WhatsAppStatusRule

/**
 * The single accessibility entry point of the app.
 *
 * It contains NO detection knowledge: it captures a reduced [ScreenSnapshot],
 * hands it to the generic [UiGuardEngine], and performs whatever single action
 * the engine's pure [UiGuardEngine.step] policy returned. Every WhatsApp detail
 * lives in [WhatsAppStatusRule], so adapting to a UI change never touches this
 * class, and adding another blocked surface means adding a rule.
 *
 * SCOPE — deliberately minimal, by construction:
 *  - the service configuration subscribes to `com.whatsapp` ONLY, so the system
 *    never even delivers another app's events here;
 *  - it subscribes to window-state, window-content and view-clicked events only;
 *  - it never types, never reads, never records and never transmits anything.
 *
 * WHAT IT DOES:
 *  1. a confirmed Status/Updates destination → one back-out to the previous safe
 *     WhatsApp screen, plus a short local notice;
 *  2. a tapped Status/Updates entry → arm, then act as soon as the navigation
 *     lands, so the content never becomes usable;
 *  3. anything unrecognised → nothing at all. Normal WhatsApp behaviour, and
 *     never a force-close of the app.
 */
class StatusBlockerService : AccessibilityService() {

    private val engine = UiGuardEngine()
    private var store: UiGuardStore? = null
    private var state = GuardState()
    private var lastAnnouncedPackage = ""
    private var lastNoticeAtMs = 0L

    override fun onServiceConnected() {
        store = UiGuardStore(this)
        state = GuardState()
        UiGuardLog.d("UI guard connected (${WhatsAppStatusRule.WHATSAPP_PACKAGE} only)")
    }

    override fun onInterrupt() {
        // Nothing to interrupt: no long-running work, no held wake lock.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        reset()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        reset()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val guardStore = store ?: UiGuardStore(this).also { store = it }
        if (!guardStore.isEnabled(WhatsAppStatusRule.TARGET_ID)) return

        val packageName = event?.packageName?.toString().orEmpty()
        if (packageName != WhatsAppStatusRule.WHATSAPP_PACKAGE) {
            state = GuardState()
            lastAnnouncedPackage = ""
            return
        }
        if (lastAnnouncedPackage != packageName) {
            lastAnnouncedPackage = packageName
            UiGuardLog.d(UiGuardLog.WHATSAPP_DETECTED)
        }

        val snapshot = UiGuardCapture.capture(
            event = event,
            root = runCatching { rootInActiveWindow }.getOrNull(),
            packageName = packageName,
        )
        val verdict = engine.evaluate(snapshot) { target -> guardStore.isEnabled(target) }
        val result = engine.step(state, verdict, nowMs())
        state = result.state

        UiGuardLog.decision(logLineFor(verdict), verdict, result.step)

        when (result.step) {
            GuardStep.IDLE -> Unit
            GuardStep.ARMED -> Unit
            GuardStep.BACK_NOW -> backOut(result.shouldNotify)
        }
    }

    /**
     * The one action the guard ever takes: navigate back out of the blocked
     * destination. WhatsApp stays open on the previous safe screen, so chats,
     * calls and groups keep working exactly as before.
     */
    private fun backOut(shouldNotify: Boolean) {
        UiGuardLog.d(UiGuardLog.STATUS_BLOCKED)
        val wentBack = runCatching {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }.getOrDefault(false)
        if (wentBack && shouldNotify) notifyBlocked()
    }

    /** A brief local notice. No overlay window, no extra permission, no data. */
    private fun notifyBlocked() {
        val now = nowMs()
        if (now - lastNoticeAtMs < NOTICE_THROTTLE_MS) return
        lastNoticeAtMs = now
        runCatching {
            Toast.makeText(this, R.string.status_blocker_blocked_notice, Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun logLineFor(verdict: GuardVerdict): String = when {
        verdict.isConfirmed && verdict.destination != GuardDestination.ENTRY_POINT ->
            UiGuardLog.STATUS_BLOCKED
        verdict.isCandidate -> UiGuardLog.STATUS_CANDIDATE
        else -> UiGuardLog.SCREEN_ALLOWED
    }

    private fun reset() {
        state = GuardState()
        lastAnnouncedPackage = ""
    }

    private fun nowMs() = SystemClock.elapsedRealtime()

    companion object {
        /** Keep repeated attempts quiet without ever hiding the blocking. */
        private const val NOTICE_THROTTLE_MS = 3_000L

        /**
         * True when the system reports THIS service as enabled. The single
         * honest source for the onboarding UI — never an assumption.
         */
        fun isAccessibilityEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager ?: return false
            val expectedPackage = context.packageName
            val expectedClass = StatusBlockerService::class.java.name
            return runCatching {
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfoFeedback.ALL_MASK)
                    .any { info ->
                        val serviceInfo = info.resolveInfo.serviceInfo
                        serviceInfo.packageName == expectedPackage &&
                            serviceInfo.name == expectedClass
                    }
            }.getOrDefault(false)
        }

        /** Whether official WhatsApp is installed. Never blocks, only informs. */
        fun isWhatsAppInstalled(context: Context): Boolean = runCatching {
            context.packageManager.getPackageInfo(WhatsAppStatusRule.WHATSAPP_PACKAGE, 0)
            true
        }.getOrDefault(false)

        /** Kept separate so the accessibility feedback mask lives in one place. */
        private object AccessibilityServiceInfoFeedback {
            const val ALL_MASK =
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        }
    }
}
