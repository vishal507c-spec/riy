package com.vishal.riy.protection.ui

import com.vishal.riy.blocker.ShieldStatus
import com.vishal.riy.protection.enforcement.AllowedApp
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.events.ProtectionLogEvent
import com.vishal.riy.protection.policy.ProtectionState

/**
 * The single authoritative, READ-ONLY view the Compose UI renders. Pure
 * Kotlin — no Compose import, so it can be constructed and asserted in plain
 * JVM unit tests.
 *
 * SECURITY RULE: this model exposes only facts. It deliberately contains NO
 * method such as disable(), pause(), bypass(), unlock() or removeRestriction()
 * — the UI cannot mutate security state, because it is never handed the means
 * to. Every field is derived from the real backend (state store, lock engine
 * deadline, integrity engine, event store, app resolver).
 *
 * @param protectionState           current [ProtectionState].
 * @param protectionActive          true when content filtering is actually running.
 * @param remainingTime             ms left in the live session (0 when none).
 * @param startedAt                 epoch ms the current session began (0 when none).
 * @param expiresAt                 epoch ms the session ends (0 when none).
 * @param deviceOwnerActive         RIY is Device Owner.
 * @param uninstallProtectionActive RIY's package is uninstall-blocked.
 * @param integrityVerified         the last integrity pass verified everything.
 * @param enforcementStatus         what the platform is ACTUALLY enforcing right
 *                                   now, read live from the enforcement engine.
 * @param allowedApps               apps policy keeps available during restriction.
 * @param blockedAppCount           apps restricted during the current session.
 * @param recentEvents              latest log events (oldest first).
 * @param restrictedReason          why the restriction exists, or null.
 */
data class ProtectionUiState(

    val protectionState: ProtectionState = ProtectionState.NORMAL,

    val protectionActive: Boolean = false,

    val remainingTime: Long = 0L,

    val startedAt: Long = 0L,

    val expiresAt: Long = 0L,

    val deviceOwnerActive: Boolean = false,

    val uninstallProtectionActive: Boolean = false,

    val integrityVerified: Boolean = false,

    val enforcementStatus: EnforcementStatus = EnforcementStatus.NORMAL,

    val allowedApps: List<AllowedApp> = emptyList(),

    val blockedAppCount: Int = 0,

    val recentEvents: List<ProtectionLogEvent> = emptyList(),

    val restrictedReason: String? = null,

    /**
     * The network filter's real status. Carried through so every surface
     * (dashboard, details card, lock screen) reports the SAME evidence instead
     * of each re-deriving "the VPN started, so we must be protected".
     */
    val shieldStatus: ShieldStatus = ShieldStatus.DISABLED,

    /** True when an encrypted-DNS route may still bypass the filter. */
    val encryptedDnsBypassPossible: Boolean = true,

) {

    /**
     * True when the filter has been PROVEN to block, not merely started.
     *
     * This is deliberately stricter than [protectionActive]: a running filter
     * that has not passed its self-test is protecting nothing that has been
     * demonstrated, and the UI must not paint a verified state off it.
     */
    val filteringVerified: Boolean
        get() = shieldStatus == ShieldStatus.VERIFIED

    /** True when the device is under a live restriction. */
    val isRestricted: Boolean
        get() = protectionState == ProtectionState.RESTRICTED ||
            protectionState == ProtectionState.HARDENED

    /**
     * True while the backend is restoring or re-verifying a session — either the
     * persisted session is explicitly [ProtectionState.RECOVERY], or the
     * enforcement engine is mid-reconciliation of a required restriction.
     */
    val isRecovering: Boolean
        get() = protectionState == ProtectionState.RECOVERY ||
            (isRestricted && enforcementStatus == EnforcementStatus.RECONCILING)

    /**
     * True only when the live platform read-back CONFIRMS the policy the backend
     * requires. For a restricted session that means the platform really is
     * holding a restrictive allowlist; for an unrestricted one it means the
     * platform really is back to normal. A reconciling state is deliberately
     * NOT verified — the UI may never paint "protected" off an assumption.
     */
    val enforcementVerified: Boolean
        get() = if (isRestricted) {
            enforcementStatus == EnforcementStatus.ACTIVE_RESTRICTED
        } else {
            enforcementStatus == EnforcementStatus.NORMAL
        }

    /**
     * Honest mismatch flag: the backend REQUIRES a restriction but the live
     * platform read-back says the device is NOT restricted. This is a real
     * protection warning — it must never be dressed up as "protected". A
     * reconciling engine is reported as recovery ([isRecovering]) instead.
     */
    val enforcementMismatch: Boolean
        get() = isRestricted && enforcementStatus == EnforcementStatus.NORMAL

    companion object {

        /**
         * Safe pre-load state, used until the bridge has read the real backend.
         * Every protective flag defaults to the honest "unknown/not verified"
         * value rather than to a reassuring one — the UI must never paint a
         * green status it has not confirmed. The enforcement status is
         * RECONCILING (not NORMAL) precisely so that "verified" cannot be
         * asserted off an unread backend.
         */
        val LOADING: ProtectionUiState = ProtectionUiState(
            protectionState = ProtectionState.NORMAL,
            protectionActive = false,
            remainingTime = 0L,
            startedAt = 0L,
            expiresAt = 0L,
            deviceOwnerActive = false,
            uninstallProtectionActive = false,
            integrityVerified = false,
            enforcementStatus = EnforcementStatus.RECONCILING,
            allowedApps = emptyList(),
            blockedAppCount = 0,
            recentEvents = emptyList(),
            restrictedReason = null,
            // The honest pre-load answer is "not known yet" — never a
            // reassuring default the UI could paint before the backend reads.
            shieldStatus = ShieldStatus.UNKNOWN,
            encryptedDnsBypassPossible = true,
        )
    }
}
