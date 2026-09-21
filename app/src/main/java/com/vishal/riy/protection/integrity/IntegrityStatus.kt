package com.vishal.riy.protection.integrity

/**
 * Immutable result of one integrity verification pass. Pure Kotlin.
 *
 * Every boolean is a REAL check against the live system, never a constant.
 * [overallVerified] is derived from the others so it can never be asserted
 * independently of them.
 *
 * @param deviceOwnerActive        RIY is the Device Owner.
 * @param uninstallProtectionActive RIY's package is uninstall-blocked by policy.
 * @param policyConsistent         the persisted session's policy version matches
 *                                 the current policy.
 * @param sessionConsistent        the persisted session agrees with the lock
 *                                 engine's authoritative deadline.
 * @param lockTaskConsistent       the live lock-task configuration matches what
 *                                 the current session requires.
 * @param issues                   diagnostics for anything that was off.
 */
data class IntegrityStatus(

    val deviceOwnerActive: Boolean,

    val uninstallProtectionActive: Boolean,

    val policyConsistent: Boolean,

    val sessionConsistent: Boolean,

    val lockTaskConsistent: Boolean,

    val issues: List<IntegrityIssue> = emptyList(),
) {

    /** True only when every individual check passed and no ERROR issue exists. */
    val overallVerified: Boolean
        get() = deviceOwnerActive &&
            uninstallProtectionActive &&
            policyConsistent &&
            sessionConsistent &&
            lockTaskConsistent &&
            issues.none { it.severity == IntegrityIssue.Severity.ERROR }

    companion object {

        /** Sentinel used before the first check has run. */
        val UNKNOWN: IntegrityStatus = IntegrityStatus(
            deviceOwnerActive = false,
            uninstallProtectionActive = false,
            policyConsistent = false,
            sessionConsistent = false,
            lockTaskConsistent = false,
            issues = emptyList(),
        )
    }
}
