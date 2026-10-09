package com.vishal.riy.protection.enforcement

import com.vishal.riy.admin.RiyDeviceAdminReceiver
import com.vishal.riy.protection.enforcement.platform.AdminComponent
import com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary
import com.vishal.riy.protection.enforcement.platform.LockTaskFeatures
import com.vishal.riy.protection.enforcement.platform.PackageDiscoveryBoundary
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.state.ProtectionSession
import java.util.concurrent.atomic.AtomicReference

/**
 * THE real implementation of [EnforcementEngine], and the only component in the
 * protection pipeline that applies privileged Device Owner policy.
 *
 * ARCHITECTURE — the Android boundary is drawn exactly here and nowhere else:
 *
 *     PolicyEngine → ProtectionDecision
 *         → RestrictedModeController
 *             → EnforcementEngine   (this class)
 *                 → DevicePolicyBoundary / PackageDiscoveryBoundary
 *                     → Android DevicePolicyManager / PackageManager
 *
 * This class holds NO `DevicePolicyManager`, NO `Context` and NO
 * `PackageManager`; it speaks only to the two boundary interfaces above, which
 * is what makes its full behaviour unit-testable on the JVM. Only the two
 * `Android*Boundary` adapters may touch the framework.
 *
 * HARD RULES implemented here
 *  - Device Owner is verified BEFORE any privileged operation; a non-owner
 *    device gets an explicit [PolicyApplicationResult.NOT_DEVICE_OWNER] and
 *    nothing is applied. The app never crashes and never pretends success.
 *  - The allowlist is computed from the REAL device via [AppPolicyResolver],
 *    then must pass a pre-flight safety check or nothing is applied. This is
 *    the guard against an accidental self-lockout.
 *  - Every apply is followed by a READ-BACK: the platform's actual allowlist
 *    and feature set are fetched again and compared before success is claimed.
 *  - [restoreNormalPolicy] removes only the restriction. It never calls
 *    `clearDeviceOwnerApp`, never touches uninstall protection, and never
 *    leaves RIY unable to run.
 *  - `lockNow()` is never used: this is a RESTRICTED ENVIRONMENT, not a
 *    screen/power lock. The device stays usable for the allowed apps.
 */
class AndroidEnforcementEngine(

    private val devicePolicy: DevicePolicyBoundary,

    private val discovery: PackageDiscoveryBoundary,

    private val appPolicyResolver: AppPolicyResolver,

    private val riyPackageName: String,

    private val clock: () -> Long = System::currentTimeMillis,

) : EnforcementEngine {

    /** The last session/policy this engine expected the platform to hold. */
    private val expected = AtomicReference<ExpectedState?>(null)

    @Volatile
    private var lastApplicationResult: PolicyApplicationResult =
        PolicyApplicationResult.NOT_DEVICE_OWNER

    @Volatile
    private var lastReconciliation: ReconciliationResult =
        ReconciliationResult.NOT_DEVICE_OWNER

    /** Enforcement/integrity notes worth surfacing in diagnostics. */
    private val issues = mutableListOf<String>()

    // ==================================================== EnforcementEngine

    /**
     * Applies the restrictive policy for [session]. Result is available via
     * [lastApplicationResultSnapshot]; the interface method deliberately keeps
     * its existing Unit signature.
     */
    override fun applyRestrictedPolicy(session: ProtectionSession, lockTaskPolicy: LockTaskPolicy) {
        applyRestrictedPolicyWithResult(session, lockTaskPolicy)
    }

    /** Idempotent: removes the restriction and restores normal device policy. */
    override fun restoreNormalPolicy() {
        restoreNormalPolicyWithResult()
    }

    /**
     * Re-checks the live platform state and corrects it to match the expected
     * policy. Reports [EnforcementStatus] per the existing contract; the richer
     * [ReconciliationResult] is available from [reconcileWithResult].
     */
    override fun reconcile(): EnforcementStatus = when (reconcileWithResult()) {
        ReconciliationResult.VERIFIED ->
            if (expected.get()?.session?.state.isRestrictedSession()) EnforcementStatus.ACTIVE_RESTRICTED
            else EnforcementStatus.NORMAL
        ReconciliationResult.MISMATCH -> EnforcementStatus.RECONCILING
        ReconciliationResult.ERROR -> EnforcementStatus.RECONCILING

        // Without Device Owner privileges nothing can be enforced, so from the
        // platform's point of view the device is simply unrestricted.
        ReconciliationResult.NOT_DEVICE_OWNER -> EnforcementStatus.NORMAL
    }

    /** What the platform is ACTUALLY enforcing right now, read live. */
    override fun currentStatus(): EnforcementStatus {
        if (!isDeviceOwner()) return EnforcementStatus.NORMAL
        val allowlist = devicePolicy.getLockTaskPackages(adminComponent())
        // A restrictive allowlist exists only while a restriction is applied;
        // the normal policy keeps just RIY (see [normalPolicy]).
        return if (isRestrictiveAllowlist(allowlist)) EnforcementStatus.ACTIVE_RESTRICTED
        else EnforcementStatus.NORMAL
    }

    // ===================================================== richer result API

    /**
     * Full apply with an explicit result. Sequence:
     *
     *     1. verify Device Owner
     *     2. resolve the REAL allowlist from the device
     *     3. pre-flight safety check  → abort if unsafe (no self-lockout)
     *     4. apply the allowlist + lock-task features
     *     5. READ THE STATE BACK and compare
     *     6. report the actual outcome
     */
    @Synchronized
    fun applyRestrictedPolicyWithResult(
        session: ProtectionSession,
        lockTaskPolicy: LockTaskPolicy,
    ): PolicyApplicationResult {

        // 0. Idempotency: the same session and policy, already verified, is a
        //    genuine no-op rather than a redundant platform round-trip. The
        //    comparison uses the REQUESTED policy (the caller's input), since
        //    the stored applied policy additionally carries the merged packages.
        val current = expected.get()
        if (current != null &&
            current.session.sessionId == session.sessionId &&
            current.requestedPolicy == lockTaskPolicy &&
            lastApplicationResult == PolicyApplicationResult.APPLIED
        ) {
            return PolicyApplicationResult.APPLIED
        }

        // 1. Device Owner gate — before ANY privileged operation.
        if (!isDeviceOwner()) {
            recordIssue("Not Device Owner; restricted policy not applied")
            lastApplicationResult = PolicyApplicationResult.NOT_DEVICE_OWNER
            lastReconciliation = ReconciliationResult.NOT_DEVICE_OWNER
            return lastApplicationResult
        }

        // 2. Resolve what is genuinely installed and policy-approved.
        val allowed: List<AllowedApp> = appPolicyResolver.resolveAllowedApps()

        // 3. Pre-flight safety check.
        val safety = verifyAllowlistSafety(allowed)
        if (safety is AllowlistSafety.Unsafe) {
            recordIssue("Allowlist rejected as unsafe: ${safety.reason}")
            lastApplicationResult = PolicyApplicationResult.REJECTED_UNSAFE_ALLOWLIST
            // Deliberately DO NOT touch the platform: no apply, no lock task.
            return lastApplicationResult
        }

        // 4. Apply. The resolved packages are authoritative; anything the caller
        //    explicitly listed in the policy is merged in (a trusted policy-engine
        //    decision), but it can never displace the verified essentials.
        val packages = mergePackages(allowed, lockTaskPolicy)
        val effectivePolicy = lockTaskPolicy.copy(allowedPackages = packages)
        // Store the CONFORMING policy (platformConforming features) so that
        // reconciliation reads back exactly what was applied, avoiding an
        // unnecessary correction write when features are compared.
        val conformingPolicy = LockTaskFeatures.from(effectivePolicy).platformConforming()
            .let { features ->
                effectivePolicy.copy(
                    allowHome = features.allowHome,
                    allowOverview = features.allowOverview,
                    allowNotifications = features.allowNotifications,
                    allowSystemInfo = features.allowSystemInfo,
                    allowGlobalActions = features.allowGlobalActions,
                )
            }
        val admin = adminComponent()

        if (!devicePolicy.setLockTaskPackages(admin, packages)) {
            recordIssue("Platform rejected setLockTaskPackages")
            lastApplicationResult = PolicyApplicationResult.FAILED
            return lastApplicationResult
        }

        // The platform-valid form of the policy's feature set. Android requires
        // the system-info -> notifications -> home hierarchy, so this is the
        // combination that can actually be applied — and the one the read-back
        // below must match. Computed centrally via platformConforming() so the
        // rule is never duplicated at a call site.
        val features = LockTaskFeatures.from(effectivePolicy).platformConforming()
        if (devicePolicy.lockTaskFeaturesSupported &&
            !devicePolicy.setLockTaskFeatures(admin, features)
        ) {
            recordIssue("Platform rejected setLockTaskFeatures")
            lastApplicationResult = PolicyApplicationResult.FAILED
            return lastApplicationResult
        }

        // 5. Read back and VERIFY. The request succeeding is not enough. On
        //    platforms without feature control (pre-API 28) only the allowlist
        //    is verifiable, and that is what is verified here.
        val actual = readPlatformPolicy(admin)
        val verified = actual.packages == packages.toSet() &&
            (!devicePolicy.lockTaskFeaturesSupported || actual.features == features)
        if (!verified) {
            recordIssue("Post-apply read-back mismatch (packages or features differ)")
            lastApplicationResult = PolicyApplicationResult.FAILED
            lastReconciliation = ReconciliationResult.MISMATCH
            return lastApplicationResult
        }

        // 6. Harden the device behind the same Device Owner seat: hide/suspend
        //    bypass packages + unauthorized sideloads, and raise user
        //    restrictions (unknown-source installs, Private DNS, VPN config).
        //    Best-effort and fully reversible; a hardening failure never masks
        //    the verified lock-task allowlist above (it is recorded, not fatal).
        applyHardening(allowed, packages)

        // 7. Record the expectation and report.
        // Store the CONFORMING policy so reconciliation matches the platform state
        // exactly without an unnecessary correction write.
        expected.set(ExpectedState(session, lockTaskPolicy, conformingPolicy))
        lastApplicationResult = PolicyApplicationResult.APPLIED
        lastReconciliation = ReconciliationResult.VERIFIED

        // Surface the honest wallet/phone/telegram situation for diagnostics.
        noteResolutionStatus(allowed)

        return lastApplicationResult
    }

    /** Removes the restriction. Never removes Device Owner or uninstall protection. */
    @Synchronized
    fun restoreNormalPolicyWithResult(): PolicyApplicationResult {
        if (!isDeviceOwner()) {
            recordIssue("Not Device Owner; normal policy is already in force")
            lastApplicationResult = PolicyApplicationResult.NOT_DEVICE_OWNER
            return lastApplicationResult
        }

        val admin = adminComponent()
        val policy = normalPolicy()

        // Clear the restrictive allowlist. The normal allowlist keeps RIY alone
        // so the app itself can never be locked out by a stale configuration;
        // every OTHER app loses its restriction, which is what "normal" means.
        if (!devicePolicy.setLockTaskPackages(admin, policy.allowedPackages)) {
            recordIssue("Platform rejected setLockTaskPackages while restoring normal policy")
            lastApplicationResult = PolicyApplicationResult.FAILED
            return lastApplicationResult
        }

        if (devicePolicy.lockTaskFeaturesSupported &&
            !devicePolicy.setLockTaskFeatures(admin, LockTaskFeatures.from(policy))
        ) {
            recordIssue("Platform rejected setLockTaskFeatures while restoring normal policy")
            lastApplicationResult = PolicyApplicationResult.FAILED
            return lastApplicationResult
        }

        // Verify the device really is back to normal.
        val actual = readPlatformPolicy(admin)
        val verified = actual.packages == policy.allowedPackages.toSet() &&
            (!devicePolicy.lockTaskFeaturesSupported ||
                actual.features == LockTaskFeatures.from(policy))
        if (!verified) {
            recordIssue("Post-restore read-back mismatch")
            lastApplicationResult = PolicyApplicationResult.FAILED
            lastReconciliation = ReconciliationResult.MISMATCH
            return lastApplicationResult
        }

        // Release hardening: unhide/unsuspend everything we hardened and drop
        // the protected-mode user restrictions. RIY's own uninstall protection
        // is deliberately untouched (owned by UninstallProtection).
        clearHardening()

        expected.set(null)
        lastApplicationResult = PolicyApplicationResult.APPLIED
        lastReconciliation = ReconciliationResult.VERIFIED
        return lastApplicationResult
    }

    /**
     * Inspects the ACTUAL platform state, compares it against the expected
     * policy and, on a mismatch, attempts one safe correction before reading
     * back again. Never claims success from a request alone.
     */
    @Synchronized
    fun reconcileWithResult(): ReconciliationResult {
        if (!isDeviceOwner()) {
            recordIssue("Not Device Owner; reconciliation skipped")
            lastReconciliation = ReconciliationResult.NOT_DEVICE_OWNER
            return lastReconciliation
        }

        val admin = adminComponent()
        val expectation = expected.get()
        val expectedPolicy = expectation?.appliedPolicy ?: normalPolicy()

        // 1. Read the ground truth.
        var actual = readPlatformPolicy(admin)
        var verified = actual.matches(expectedPolicy)

        // 2. Correct, then re-read. Success is judged on the SECOND read.
        if (!verified) {
            recordIssue(
                "Reconciliation mismatch — expected ${expectedPolicy.allowedPackages.sorted()}, " +
                    "actual ${actual.packages.sorted()}; re-applying expected policy",
            )
            devicePolicy.setLockTaskPackages(admin, expectedPolicy.allowedPackages)
            if (devicePolicy.lockTaskFeaturesSupported) {
                devicePolicy.setLockTaskFeatures(admin, LockTaskFeatures.from(expectedPolicy))
            }
            actual = readPlatformPolicy(admin)
            verified = actual.matches(expectedPolicy)
        }

        // 3. Package-state reconciliation: a newly installed / unhidden /
        //    unsuspended package must not silently widen the device. Re-apply
        //    hardening against the CURRENT installed set whenever a restricted
        //    session is expected; release it when no session is expected.
        //    This is what makes "install TeraBox mid-restriction" a no-op
        //    bypass: the install may land, but it is immediately neutralized.
        if (expectation != null && expectation.session.state.isRestrictedSession()) {
            val allowed = runCatching { appPolicyResolver.resolveAllowedApps() }.getOrElse { emptyList() }
            val allowedSet = (allowed.map { it.packageName } + expectedPolicy.allowedPackages).toSet()
            applyHardening(allowed, allowedSet.toList())
        } else if (expectation == null) {
            // No session expected: make sure no stale hardening orphans a
            // hidden app after expiry (restore path already clears, this is
            // the idempotent safety net for process-death races).
            runCatching { clearHardening() }
        }

        lastReconciliation = if (verified) ReconciliationResult.VERIFIED
        else if (actual.packages.isEmpty() && expectation == null) ReconciliationResult.ERROR
        else ReconciliationResult.MISMATCH
        return lastReconciliation
    }

    // ============================================================ §17 report

    /**
     * A complete diagnostic snapshot built entirely from LIVE platform state —
     * every field is read now, none is a constant or a cached request.
     */
    @Synchronized
    fun diagnostics(): EnforcementDiagnosticReport {
        val owner = isDeviceOwner()
        val admin = adminComponent()
        val allowed = appPolicyResolver.resolveAllowedApps()
        val actual = if (owner) {
            readPlatformPolicy(admin)
        } else {
            PlatformPolicy(emptySet(), LockTaskFeatures.UNRESTRICTED, featuresComparable = false)
        }

        return EnforcementDiagnosticReport(
            deviceOwner = owner,
            adminComponent = admin.flatten(),
            adminActive = if (owner) devicePolicy.isAdminActive(admin) else false,
            riyPackagePresent = discovery.isPackageInstalled(riyPackageName),
            riyEnabled = discovery.isPackageEnabled(riyPackageName),
            uninstallProtected = if (owner) devicePolicy.isUninstallBlocked(admin, riyPackageName) else false,
            lockTaskAllowlist = actual.packages.sorted(),
            allowHome = actual.features.allowHome,
            allowOverview = actual.features.allowOverview,
            phonePackage = allowed.firstOrNull { it.category == AllowedAppCategory.PHONE }?.packageName,
            walletPackage = allowed.firstOrNull { it.category == AllowedAppCategory.WALLET }?.packageName,
            emergencyPackage = allowed.firstOrNull { it.category == AllowedAppCategory.EMERGENCY }?.packageName,
            inputMethodPackage = allowed.firstOrNull { it.category == AllowedAppCategory.SYSTEM_ESSENTIAL }?.packageName,
            telegramPackage = allowed.firstOrNull { it.category == AllowedAppCategory.COMMUNICATION }?.packageName,
            applicationResult = lastApplicationResult,
            reconciliation = lastReconciliation,
            issues = issues.toList(),
            generatedAt = clock(),
        )
    }

    /** The most recent apply result, for callers that need the rich outcome. */
    fun lastApplicationResultSnapshot(): PolicyApplicationResult = lastApplicationResult

    /** The most recent reconciliation result. */
    fun lastReconciliationSnapshot(): ReconciliationResult = lastReconciliation

    /**
     * Drops the in-memory expectation of a restricted platform state.
     *
     * Phase 6 recovery hook, and the ONLY thing of its kind. After a session
     * has expired, the persisted protection session is gone, so a subsequent
     * [reconcileWithResult] would otherwise compare the live platform state
     * against a *stale* expectation and try to re-impose a restriction whose
     * session no longer exists. Clearing the expectation makes the normal
     * policy the thing reconciliation compares against again.
     *
     * This changes NO platform state and computes NO deadline: it only forgets
     * what this in-memory engine instance was assuming. It exists because the
     * expectation itself is in-memory and therefore does not survive a process
     * death — which is exactly the case recovery is hardening.
     */
    @Synchronized
    fun deferExpectation() {
        expected.set(null)
    }

    /**
     * Re-builds the in-memory expectation from a PERSISTED session, WITHOUT
     * touching the platform.
     *
     * The single Phase 6 recovery entry point that makes reconciliation work
     * after a process death. [ExpectedState] lives in memory only, so when the
     * RIY process is recreated the engine no longer knows which session the
     * persisted lock-task allowlist belongs to, and [reconcileWithResult] would
     * fall back to the NORMAL policy — i.e. try to *remove* a restriction that
     * is still legitimately live. Restoring the expectation first makes the
     * existing comparison and its single correction operate on the real
     * expected policy instead.
     *
     * HARD RULES, identical to the apply path:
     *  - Device Owner is verified first; nothing is set otherwise;
     *  - the allowlist is resolved from the REAL device and must pass the same
     *    pre-flight safety check, or the expectation is refused;
     *  - the platform is NOT written and NO deadline is computed. Only the
     *    in-memory expectation is restored. [reconcileWithResult] performs the
     *    single correction and the read-back that authorises any verdict.
     *
     * @return the effective policy the platform is now expected to hold, or
     *   null when it could not be reconstructed (not Device Owner, or the
     *   resolved allowlist failed the safety gate). A null return is the honest
     *   answer and must be reported as a failure, never as a success.
     */
    @Synchronized
    fun restoreExpectation(
        session: ProtectionSession,
        lockTaskPolicy: LockTaskPolicy,
    ): LockTaskPolicy? {
        // 1. Device Owner gate — before anything is assumed.
        if (!isDeviceOwner()) {
            recordIssue("Not Device Owner; expectation not restored")
            lastReconciliation = ReconciliationResult.NOT_DEVICE_OWNER
            return null
        }

        // 2-3. Resolve the real allowlist and run the same safety gate, so the
        //      expectation is never something the engine would refuse to apply.
        val allowed: List<AllowedApp> = appPolicyResolver.resolveAllowedApps()
        val safety = verifyAllowlistSafety(allowed)
        if (safety is AllowlistSafety.Unsafe) {
            recordIssue("Expectation rejected as unsafe: ${safety.reason}")
            return null
        }

        // 4. Record the expectation exactly as a successful apply would have,
        //    without a single platform write. Use the CONFORMING policy so
        //    reconciliation matches the platform state exactly.
        val packages = mergePackages(allowed, lockTaskPolicy)
        val effectivePolicy = lockTaskPolicy.copy(allowedPackages = packages)
        val conformingPolicy = LockTaskFeatures.from(effectivePolicy).platformConforming()
            .let { features ->
                effectivePolicy.copy(
                    allowHome = features.allowHome,
                    allowOverview = features.allowOverview,
                    allowNotifications = features.allowNotifications,
                    allowSystemInfo = features.allowSystemInfo,
                    allowGlobalActions = features.allowGlobalActions,
                )
            }
        expected.set(ExpectedState(session, lockTaskPolicy, conformingPolicy))
        return conformingPolicy
    }

    // ================================================================ priv

    /** The authoritative admin component (mirrors `ComponentName(ctx, RiyDeviceAdminReceiver)`). */
    private fun adminComponent(): AdminComponent =
        AdminComponent(riyPackageName, RiyDeviceAdminReceiver::class.java.name)

    /** Device Owner check — the gate in front of every privileged operation. */
    private fun isDeviceOwner(): Boolean = devicePolicy.isDeviceOwnerApp(riyPackageName)

    /**
     * The normal (unrestricted) policy: only RIY is lock-task-able, and every
     * affordance is on. This is what [restoreNormalPolicy] installs and what
     * [reconcileWithResult] compares against when no session is expected.
     */
    private fun normalPolicy(): LockTaskPolicy = LockTaskPolicy(
        allowedPackages = listOf(riyPackageName),
        allowHome = true,
        allowOverview = true,
        allowNotifications = true,
        allowSystemInfo = true,
        allowGlobalActions = true,
    )

    /** True only while a RESTRICTED / HARDENED session is expected. */
    private fun ProtectionState?.isRestrictedSession(): Boolean =
        this == ProtectionState.RESTRICTED || this == ProtectionState.HARDENED

    /**
     * Restrictive = the platform is holding an allowlist beyond the normal
     * (RIY-only) one. This is what distinguishes ACTIVE_RESTRICTED from NORMAL
     * when reporting ACTUAL state.
     */
    private fun isRestrictiveAllowlist(packages: List<String>): Boolean {
        val set = packages.toSet()
        return set.isNotEmpty() && set != setOf(riyPackageName)
    }

    /**
     * Merges the resolver's verified allowlist with anything the caller
     * explicitly listed, preserving installation order of the verified set.
     */
    private fun mergePackages(allowed: List<AllowedApp>, policy: LockTaskPolicy): List<String> {
        val merged = linkedSetOf<String>()
        allowed.forEach { merged += it.packageName }
        policy.allowedPackages.forEach { if (it.isNotBlank()) merged += it }
        return merged.toList()
    }

    /** Reads what the platform is ACTUALLY enforcing right now. */
    private fun readPlatformPolicy(admin: AdminComponent): PlatformPolicy =
        PlatformPolicy(
            packages = devicePolicy.getLockTaskPackages(admin).toSet(),
            features = devicePolicy.getLockTaskFeatures(admin),
            featuresComparable = devicePolicy.lockTaskFeaturesSupported,
        )

    /**
     * §20 pre-flight safety check. A restriction is only applied when the
     * allowlist provably keeps the device usable — this is what prevents an
     * accidental self-lockout.
     *
     *  - RIY is REQUIRED (else the protection UI could not be shown).
     *  - a PHONE is REQUIRED (the device must remain a phone).
     *  - at least one EMERGENCY or SYSTEM_ESSENTIAL is REQUIRED.
     *  - a WALLET is OPTIONAL; absence is reported, never fatal.
     */
    private fun verifyAllowlistSafety(allowed: List<AllowedApp>): AllowlistSafety {
        val byCategory = allowed.groupBy { it.category }

        if (allowed.isEmpty()) return AllowlistSafety.Unsafe("allowlist is empty")
        if (byCategory[AllowedAppCategory.RIY].isNullOrEmpty()) {
            return AllowlistSafety.Unsafe("RIY package is missing from the allowlist")
        }
        if (byCategory[AllowedAppCategory.PHONE].isNullOrEmpty()) {
            return AllowlistSafety.Unsafe(
                "no verified phone package was resolved; refusing to restrict without one",
            )
        }
        val essentials = (byCategory[AllowedAppCategory.EMERGENCY].orEmpty() +
            byCategory[AllowedAppCategory.SYSTEM_ESSENTIAL].orEmpty())
        if (essentials.isEmpty()) {
            return AllowlistSafety.Unsafe(
                "no emergency or system-essential package resolved; refusing to restrict",
            )
        }
        return AllowlistSafety.Safe
    }

    /** Records the honest phone/wallet/telegram resolution for diagnostics. */
    private fun noteResolutionStatus(allowed: List<AllowedApp>) {
        if (allowed.none { it.category == AllowedAppCategory.WALLET }) {
            recordIssue("Wallet unresolved — restriction will operate without a wallet app")
        }
        if (allowed.none { it.category == AllowedAppCategory.PHONE }) {
            recordIssue("Phone unresolved — integrity issue")
        }
        if (allowed.none { it.category == AllowedAppCategory.COMMUNICATION }) {
            recordIssue("Telegram unresolved — restriction will operate without Telegram")
        }
    }

    // ------------------------------------------------------- hardening layer
    // Second enforcement layer behind the same Device Owner seat. Lock-task
    // allowlisting is the primary control; hiding/suspending + user
    // restrictions close the routes lock-task alone cannot cover:
    //  - production never enters lock-task mode outside the debug hook, so
    //    hidden/suspended is what actually neutralizes TeraBox mid-restriction;
    //  - DISALLOW_INSTALL_UNKNOWN_SOURCES blocks sideloaded APK installs;
    //  - DISALLOW_CONFIG_PRIVATE_DNS / DISALLOW_CONFIG_VPN block the
    //    Private-DNS and rogue-VPN bypasses where the platform permits it.
    // RIY itself and every allowlisted package are ALWAYS exempt (self-update
    // and essentials keep working). System packages are never hidden/suspended
    // (device must stay bootable); they remain governed by the lock-task
    // allowlist. All hardening is best-effort and fully reversible.

    /**
     * Applies hardening for the CURRENT restriction: hides explicitly blocked
     * packages (TeraBox family), suspends unauthorized non-system packages,
     * and raises protected-mode user restrictions. Never throws.
     */
    private fun applyHardening(allowed: List<AllowedApp>, allowedPackages: List<String>) {
        if (!isDeviceOwner()) return
        val admin = adminComponent()
        val allowedSet = allowedPackages.toSet()
        runCatching {
            val installed = runCatching { discovery.installedPackages() }.getOrElse { emptyList() }
            val explicitBlocked = installed.filter {
                it.packageName != riyPackageName &&
                    BlockedAppPolicy.isBlockedPackage(it.packageName, it.label)
            }
            val unauthorized = installed.filter {
                it.packageName != riyPackageName &&
                    it.packageName !in allowedSet &&
                    !it.isSystem &&
                    discovery.hasLauncherActivity(it.packageName) &&
                    !BlockedAppPolicy.isBlockedPackage(it.packageName, it.label)
            }
            // Explicit bypass apps: hide (invisible + unlaunchable) AND suspend
            // (belt-and-braces if hidden is reverted externally).
            explicitBlocked.forEach { pkg ->
                val hid = devicePolicy.setApplicationHidden(admin, pkg.packageName, true)
                val sus = devicePolicy.setPackagesSuspended(admin, listOf(pkg.packageName), true)
                if (hid || sus) {
                    recordIssue(
                        "Blocked package neutralized: ${pkg.packageName} " +
                            "(${BlockedAppPolicy.blockedCategoryFor(pkg.packageName, pkg.label)})",
                    )
                } else {
                    recordIssue("Blocked package hardening FAILED (non-owner?): ${pkg.packageName}")
                }
            }
            // Generic unauthorized sideloads: suspend (reversible, still visible).
            if (unauthorized.isNotEmpty()) {
                val names = unauthorized.map { it.packageName }
                val ok = devicePolicy.setPackagesSuspended(admin, names, true)
                recordIssue(
                    if (ok) "Suspended ${names.size} unauthorized package(s): ${names.sorted()}"
                    else "Suspension FAILED for unauthorized packages: ${names.sorted()}",
                )
            }
        }.onFailure { e ->
            recordIssue("Hardening sweep failed: ${e.javaClass.simpleName}")
        }
        // User restrictions: unknown-source installs, Private DNS, VPN config.
        // Deliberately NOT setting no_install_apps / no_uninstall_apps: the
        // former would break RIY's own verified self-update, the latter would
        // block legitimate device management.
        runCatching {
            devicePolicy.addUserRestriction(
                admin, com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary
                    .RESTRICTION_INSTALL_UNKNOWN_SOURCES,
            )
            devicePolicy.addUserRestriction(
                admin, com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary
                    .RESTRICTION_CONFIG_PRIVATE_DNS,
            )
            devicePolicy.addUserRestriction(
                admin, com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary
                    .RESTRICTION_CONFIG_VPN,
            )
        }.onFailure { e ->
            recordIssue("User-restriction hardening failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Releases all hardening: unhides/unsuspends previously neutralized
     * packages and drops protected-mode user restrictions. Never touches
     * uninstall protection or Device Owner. Never throws.
     */
    private fun clearHardening() {
        if (!isDeviceOwner()) return
        val admin = adminComponent()
        runCatching {
            val installed = runCatching { discovery.installedPackages() }.getOrElse { emptyList() }
            val hardened = installed.filter {
                it.packageName != riyPackageName &&
                    (BlockedAppPolicy.isBlockedPackage(it.packageName, it.label) ||
                        (!it.isSystem && discovery.hasLauncherActivity(it.packageName)))
            }.map { it.packageName }
            hardened.forEach { runCatching { devicePolicy.setApplicationHidden(admin, it, false) } }
            if (hardened.isNotEmpty()) {
                runCatching { devicePolicy.setPackagesSuspended(admin, hardened, false) }
            }
        }.onFailure { e ->
            recordIssue("Hardening release failed: ${e.javaClass.simpleName}")
        }
        runCatching {
            devicePolicy.clearUserRestriction(
                admin, com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary
                    .RESTRICTION_INSTALL_UNKNOWN_SOURCES,
            )
            devicePolicy.clearUserRestriction(
                admin, com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary
                    .RESTRICTION_CONFIG_VPN,
            )
            // The private-DNS restriction is NOT purely a restriction-phase
            // control: while the DNS filter is live it is the only supported
            // Android control that keeps the system resolver (and therefore a
            // DNS-over-TLS provider) off the filtered resolver. Clearing it here
            // would silently re-open that bypass the moment a session ends, so
            // it is released only when the filter itself is not running.
            if (!com.vishal.riy.blocker.BlockerState.current().phase.let {
                    it == com.vishal.riy.blocker.BlockerState.Phase.CONNECTED ||
                        it == com.vishal.riy.blocker.BlockerState.Phase.INITIALIZING
                }
            ) {
                devicePolicy.clearUserRestriction(
                    admin, com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary
                        .RESTRICTION_CONFIG_PRIVATE_DNS,
                )
            }
        }.onFailure { e ->
            recordIssue("User-restriction release failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Single-package hardening used by the install-time fast path
     * ([com.vishal.riy.protection.integrity.PackageStateWatcher]): neutralize
     * [packageName] immediately when a restriction is live, unless it is RIY
     * itself or an allowlisted package. Returns true when the package was
     * neutralized or needed no action.
     */
    fun hardenSinglePackage(packageName: String): Boolean {
        if (packageName.isBlank()) return true
        if (BlockedAppPolicy.isSelfPackage(packageName, riyPackageName)) return true
        if (!isDeviceOwner()) return false
        val expectation = expected.get()
        val live = expectation?.session?.state.isRestrictedSession() == true
        if (!live) return true
        val allowedSet = runCatching { appPolicyResolver.resolveAllowedApps().map { it.packageName }.toSet() }
            .getOrElse { emptySet() } + (expectation?.appliedPolicy?.allowedPackages.orEmpty())
        if (packageName in allowedSet) return true
        val admin = adminComponent()
        val label = runCatching { discovery.packageLabel(packageName) }.getOrNull()
        return if (BlockedAppPolicy.isBlockedPackage(packageName, label)) {
            val hid = devicePolicy.setApplicationHidden(admin, packageName, true)
            val sus = devicePolicy.setPackagesSuspended(admin, listOf(packageName), true)
            if (hid || sus) recordIssue("Install-time block: $packageName neutralized")
            hid || sus
        } else {
            // Unknown non-system launcher app installed mid-restriction:
            // suspend it (default-deny at package level).
            val isSystem = runCatching { discovery.installedPackages().firstOrNull { it.packageName == packageName }?.isSystem }
                .getOrNull() ?: false
            if (isSystem) return true
            if (!runCatching { discovery.hasLauncherActivity(packageName) }.getOrDefault(true)) return true
            val ok = devicePolicy.setPackagesSuspended(admin, listOf(packageName), true)
            if (ok) recordIssue("Install-time suspend: unauthorized $packageName neutralized")
            ok
        }
    }

    @Synchronized
    private fun recordIssue(description: String) {
        issues += description
        if (issues.size > MAX_ISSUES) issues.removeAt(0)
    }

    /**
     * Immutable snapshot of what we asked the platform to hold.
     *
     * @param session         the session being enforced.
     * @param requestedPolicy the policy the caller asked for (for idempotency).
     * @param appliedPolicy   the policy actually applied, i.e. [requestedPolicy]
     *                        with the resolved/merged packages filled in. This
     *                        is what reconciliation compares against.
     */
    private data class ExpectedState(
        val session: ProtectionSession,
        val requestedPolicy: LockTaskPolicy,
        val appliedPolicy: LockTaskPolicy,
    )

    /**
     * Immutable snapshot of what the platform reports it holds.
     *
     * @param featuresComparable false on platforms with no lock-task FEATURE
     *   control (pre-API 28), where [features] is merely the platform default
     *   and must NOT be compared against an expected restrictive policy.
     */
    private data class PlatformPolicy(
        val packages: Set<String>,
        val features: LockTaskFeatures,
        val featuresComparable: Boolean,
    ) {
        fun matches(policy: LockTaskPolicy): Boolean =
            packages == policy.allowedPackages.toSet() &&
                (!featuresComparable || features == LockTaskFeatures.from(policy))
    }

    private sealed interface AllowlistSafety {
        data object Safe : AllowlistSafety
        data class Unsafe(val reason: String) : AllowlistSafety
    }

    private companion object {
        const val MAX_ISSUES = 32
    }
}
