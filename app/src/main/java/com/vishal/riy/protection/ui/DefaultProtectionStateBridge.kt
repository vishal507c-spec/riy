package com.vishal.riy.protection.ui

import com.vishal.riy.blocker.BlockerState
import com.vishal.riy.blocker.BlockerStateStore
import com.vishal.riy.blocker.ShieldStatus
import com.vishal.riy.blocker.encryptedDnsBypassPossible
import com.vishal.riy.blocker.shieldStatusOf
import com.vishal.riy.lock.LockEngine
import com.vishal.riy.lock.LockStore
import com.vishal.riy.lock.PrefsLockStore
import com.vishal.riy.protection.enforcement.AllowedApp
import com.vishal.riy.protection.enforcement.AndroidEnforcementEngine
import com.vishal.riy.protection.enforcement.AppPolicyResolver
import com.vishal.riy.protection.enforcement.EnforcementEngine
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.enforcement.PackageManagerAppPolicyResolver
import com.vishal.riy.protection.enforcement.platform.AndroidDevicePolicyBoundary
import com.vishal.riy.protection.enforcement.platform.AndroidPackageDiscoveryBoundary
import com.vishal.riy.protection.events.ProtectionEventStore
import com.vishal.riy.protection.events.PrefsProtectionEventStore
import com.vishal.riy.protection.integrity.DefaultIntegrityEngine
import com.vishal.riy.protection.integrity.IntegrityEngine
import com.vishal.riy.protection.integrity.IntegrityStatus
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.state.ProtectionSession
import com.vishal.riy.protection.state.ProtectionStateStore
import com.vishal.riy.protection.state.PrefsProtectionStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * THE concrete implementation of [ProtectionStateBridge], and the ONLY component
 * that translates the real protection backend into [ProtectionUiState].
 *
 * WHAT IT OWNS: nothing but the translation. It holds no rule, no threshold, no
 * deadline, no allowlist and no Android framework object. Every value it emits
 * is read straight from the existing, authoritative owners:
 *
 *  - session state / persistence  → [ProtectionStateStore]
 *  - the 2-hour deadline           → [LockEngine] / [LockStore] (sole authority)
 *  - actual platform enforcement   → [EnforcementEngine]
 *  - integrity facts               → [IntegrityEngine]
 *  - the resolved allowed apps     → [AppPolicyResolver]
 *  - the event log                 → [ProtectionEventStore]
 *  - the live filtering phase      → [BlockerState]
 *
 * DEPENDENCY DIRECTION (enforced by construction):
 *
 *     Backend (read-only)
 *         →  DefaultProtectionStateBridge
 *             →  ProtectionUiState
 *                 →  Compose UI
 *
 * HARD RULES this class obeys so the UI can never become an authority:
 *  - It NEVER writes. [LockStore], [ProtectionStateStore] and the escalation
 *    store are read, never mutated — unlike [com.vishal.riy.lock.LockController],
 *    which legitimately clears an expired lock, this bridge leaves every
 *    deadline decision to the backend. It computes no `now + duration`, posts
 *    no delayed task and starts no timer.
 *  - It NEVER decides a state transition. The effective [ProtectionState] is
 *    taken from the persisted session; a restricted session is reported as live
 *    only while the authoritative [LockEngine] deadline says it is. That
 *    liveness verdict is [LockEngine]'s own existing rule, re-used — not a
 *    second one.
 *  - It triggers NO enforcement and exposes no method that could: there is no
 *    disable(), pause(), bypass(), unlock() or re-apply() anywhere here.
 *
 * REMAINING TIME. The expiry and the remaining milliseconds are both read from
 * [LockEngine] — the same countdown engine [com.vishal.riy.lock.LockController]
 * uses — so the bridge computes no `now + duration` of its own and creates no
 * second timer. The display ticker that calls [refresh] may re-read it as often
 * as it likes, but it can never arm, extend or shorten it.
 *
 * PROCESS DEATH / REBOOT. The class is stateless apart from its emitted
 * snapshot: a fresh instance reading the same stores reproduces exactly what a
 * recovered process should show, so a UI reconstruction after restart is
 * identical to a [refresh].
 */
class DefaultProtectionStateBridge(

    private val stateStore: ProtectionStateStore,

    private val lockStore: LockStore,

    private val enforcement: EnforcementEngine,

    private val integrity: IntegrityEngine,

    private val appPolicyResolver: AppPolicyResolver,

    private val eventStore: ProtectionEventStore,

    /**
     * The user's persisted protection ON/OFF choice. Supplied by the production
     * graph so the bridge can tell "switched off" from "switched on but not
     * working" — two states that look identical from the VPN phase alone.
     */
    private val protectionWanted: () -> Boolean = { false },

    private val clock: () -> Long = System::currentTimeMillis,

    ) : ProtectionStateBridge {

    /**
     * The current clock reading, re-read on every [refresh]. The clock is
     * injectable (as it is in [com.vishal.riy.lock.LockController]) so a test can
     * advance time to drive the countdown WITHOUT touching the authoritative
     * deadline.
     */
    @Volatile
    private var now: Long = clock()

    private val _state = MutableStateFlow(compute())

    override val state: StateFlow<ProtectionUiState> = _state.asStateFlow()

    /**
     * Re-reads every authoritative source and republishes [state]. Called by the
     * UI's display ticker (and by a freshly reconstructed ViewModel). Read-only;
     * it changes no backend state.
     */
    fun refresh() {
        now = clock()
        _state.value = compute()
    }

    // --------------------------------------------------------------- the map

    /**
     * One pure, side-effect-free translation of the whole backend into a single
     * [ProtectionUiState]. Grouped so each fact stays traceable to its owner.
     */
    private fun compute(): ProtectionUiState {
        val session = stateStore.current()
        val lockState = lockStore.loadState()

        // The deadline authority decides whether a time-boxed session is live.
        // LockEngine IS the countdown engine; remainingMillis is its own rule,
        // re-used here so the UI can never compute a competing expiry.
        val deadline = lockState.lockEndEpochMillis
        val deadlineLive = LockEngine.isLocked(lockState, now)
        val remaining = LockEngine.remainingMillis(lockState, now)

        // The session's own state is authoritative; a restricted session is
        // reported only while its deadline still runs (LockEngine's verdict).
        val protectionState = effectiveState(session, deadlineLive)

        val status = enforcementStatus()
        val integrityStatus = integrityStatus()

        return ProtectionUiState(
            protectionState = protectionState,
            protectionActive = isFilteringActive(),
            remainingTime = remaining.coerceAtLeast(0L),
            startedAt = session?.startTime ?: 0L,
            expiresAt = if (deadlineLive) deadline else 0L,
            deviceOwnerActive = integrityStatus.deviceOwnerActive,
            uninstallProtectionActive = integrityStatus.uninstallProtectionActive,
            integrityVerified = integrityStatus.overallVerified,
            enforcementStatus = status,
            allowedApps = allowedAppsFor(protectionState),
            blockedAppCount = session?.blockedCount ?: 0,
            recentEvents = eventStore.recent(),
            restrictedReason = session?.takeIf { it.state.isRestrictedSession() }?.reason,
            shieldStatus = shieldStatus(),
            encryptedDnsBypassPossible = encryptedDnsBypassPossible(BlockerState.current()),
        )
    }

    /**
     * The state the UI renders. NON-AUTHORITATIVE by construction: LockEngine's
     * deadline is the liveness authority, and the persisted session only refines
     * WHICH restriction is live.
     *
     *  - an explicit RECOVERY session is reported as recovery (the backend is
     *    mid-restore; the UI must not claim a resolved state);
     *  - with no live deadline the device is NOT restricted right now, even if a
     *    stale session lingers — LockEngine's verdict, not the UI's;
     *  - with a live deadline the device IS restricted: a HARDENED session is
     *    shown as HARDENED, anything else as RESTRICTED. This is exactly the
     *    "one deadline, one session" rule the pipeline enforces, so RESTRICTED
     *    and HARDENED can never imply two separate timers.
     */
    private fun effectiveState(
        session: ProtectionSession?,
        deadlineLive: Boolean,
    ): ProtectionState {
        if (session?.state == ProtectionState.RECOVERY) return ProtectionState.RECOVERY
        if (!deadlineLive) return ProtectionState.NORMAL
        return if (session?.state == ProtectionState.HARDENED) ProtectionState.HARDENED
        else ProtectionState.RESTRICTED
    }

    /**
     * What the platform is ACTUALLY enforcing right now. Read live from the
     * enforcement engine, so a restriction that was decided but not applied is
     * never reported as successfully protected.
     */
    private fun enforcementStatus(): EnforcementStatus = try {
        enforcement.currentStatus()
    } catch (_: Throwable) {
        // Never let a platform read failure become a reassuring default.
        EnforcementStatus.RECONCILING
    }

    /**
     * The integrity facts, run once per refresh. Every field is a REAL read of
     * the live system, never a constant; a failure to read is reported as the
     * honest unverified [IntegrityStatus.UNKNOWN] value rather than a
     * reassuring one.
     */
    private fun integrityStatus(): IntegrityStatus = try {
        integrity.check()
    } catch (_: Throwable) {
        IntegrityStatus.UNKNOWN
    }

    /**
     * The filter's real status, derived from evidence rather than from "the VPN
     * service started". [protectionActive] is true only while the filter is
     * genuinely running AND still able to block.
     */
    private fun shieldStatus(): ShieldStatus {
        val snapshot = BlockerState.current()
        return shieldStatusOf(snapshot, protectionWanted())
    }

    private fun isFilteringActive(): Boolean = shieldStatus().isFiltering

    /**
     * The allowed apps are resolved only when a restriction is actually live —
     * reading the PackageManager the rest of the time would be pure overhead.
     */
    private fun allowedAppsFor(state: ProtectionState): List<AllowedApp> =
        if (state.isRestrictedSession()) {
            try {
                appPolicyResolver.resolveAllowedApps()
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }

    private fun ProtectionState.isRestrictedSession(): Boolean =
        this == ProtectionState.RESTRICTED || this == ProtectionState.HARDENED

    companion object {

        /**
         * The production graph for the UI bridge: builds each existing backend
         * component exactly once and wires it behind this bridge. Sharing one
         * state store, one lock store and one enforcement engine is what keeps a
         * single authority for each concern — the bridge only READS them; it
         * never duplicates them, and it never becomes a second owner of any rule.
         */
        fun forContext(context: android.content.Context): DefaultProtectionStateBridge {
            val app = context.applicationContext
            val riyPackageName = app.packageName
            val discovery = AndroidPackageDiscoveryBoundary(app)
            val enforcement: EnforcementEngine = AndroidEnforcementEngine(
                devicePolicy = AndroidDevicePolicyBoundary(app),
                discovery = discovery,
                appPolicyResolver = PackageManagerAppPolicyResolver(discovery, riyPackageName),
                riyPackageName = riyPackageName,
            )
            val stateStore: ProtectionStateStore = PrefsProtectionStateStore(app)
            val lockStore: LockStore = PrefsLockStore(app)
            val eventStore: ProtectionEventStore = PrefsProtectionEventStore(app)

            return DefaultProtectionStateBridge(
                stateStore = stateStore,
                lockStore = lockStore,
                enforcement = enforcement,
                integrity = DefaultIntegrityEngine(app),
                appPolicyResolver = PackageManagerAppPolicyResolver(discovery, riyPackageName),
                eventStore = eventStore,
                protectionWanted = { BlockerStateStore(app).isProtectionWanted() },
            )
        }
    }
}
