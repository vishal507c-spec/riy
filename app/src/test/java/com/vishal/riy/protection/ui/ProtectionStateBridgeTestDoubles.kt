package com.vishal.riy.protection.ui

import com.vishal.riy.protection.enforcement.AllowedApp
import com.vishal.riy.protection.enforcement.AllowedAppCategory
import com.vishal.riy.protection.enforcement.AppPolicyResolver
import com.vishal.riy.protection.enforcement.EnforcementEngine
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.enforcement.LockTaskPolicy
import com.vishal.riy.protection.integrity.IntegrityEngine
import com.vishal.riy.protection.integrity.IntegrityStatus
import com.vishal.riy.protection.state.ProtectionSession

/**
 * Test doubles for the [DefaultProtectionStateBridge] seams.
 *
 * These exist ONLY in the unit-test source set. They let the bridge's read-only
 * translation be asserted against a real [InMemoryProtectionStateStore],
 * [com.vishal.riy.lock.InMemoryLockStore] and
 * [com.vishal.riy.protection.events.InMemoryProtectionEventStore] — exactly the
 * pattern every other engine in this project uses. None of them writes any real
 * security state, and none of them is reachable from production code.
 */

/** A scriptable [EnforcementEngine] that records what it was asked and never writes. */
class FakeEnforcementEngine(
    var status: EnforcementStatus = EnforcementStatus.NORMAL,
) : EnforcementEngine {

    var applyCount = 0
        private set

    var restoreCount = 0
        private set

    var reconcileCount = 0
        private set

    var currentStatusCount = 0
        private set

    override fun applyRestrictedPolicy(session: ProtectionSession, lockTaskPolicy: LockTaskPolicy) {
        applyCount++
    }

    override fun restoreNormalPolicy() {
        restoreCount++
    }

    override fun reconcile(): EnforcementStatus {
        reconcileCount++
        return status
    }

    override fun currentStatus(): EnforcementStatus {
        currentStatusCount++
        return status
    }
}

/** A scriptable [IntegrityEngine] returning whatever status it was handed. */
class FakeIntegrityEngine(
    var status: IntegrityStatus = IntegrityStatus.UNKNOWN,
) : IntegrityEngine {
    override fun check(): IntegrityStatus = status
}

/** A scriptable [AppPolicyResolver] returning a fixed, verifiable allowlist. */
class FakeAppPolicyResolver(
    val allowed: List<AllowedApp> = listOf(
        AllowedApp("com.vishal.riy", "Riy", AllowedAppCategory.RIY),
        AllowedApp("com.android.dialer", "Phone", AllowedAppCategory.PHONE),
    ),
) : AppPolicyResolver {
    override fun resolveAllowedApps(): List<AllowedApp> = allowed
    override fun resolveEssentialApps(): List<AllowedApp> = allowed
    override fun resolveEmergencyApps(): List<AllowedApp> = allowed.filter { it.category == AllowedAppCategory.EMERGENCY }
}

/** A throwing seam, used to prove a read failure never becomes a green light. */
class FailingIntegrityEngine : IntegrityEngine {
    override fun check(): IntegrityStatus = throw IllegalStateException("integrity read failed")
}

class FailingEnforcementEngine : EnforcementEngine {
    override fun applyRestrictedPolicy(session: ProtectionSession, lockTaskPolicy: LockTaskPolicy) =
        throw IllegalStateException()

    override fun restoreNormalPolicy() = throw IllegalStateException()

    override fun reconcile(): EnforcementStatus = throw IllegalStateException()

    override fun currentStatus(): EnforcementStatus = throw IllegalStateException()
}

class FailingAppPolicyResolver : AppPolicyResolver {
    override fun resolveAllowedApps(): List<AllowedApp> = throw IllegalStateException()
    override fun resolveEssentialApps(): List<AllowedApp> = throw IllegalStateException()
    override fun resolveEmergencyApps(): List<AllowedApp> = throw IllegalStateException()
}
