package com.vishal.riy.protection.integrity

import android.content.Context
import android.util.Log
import com.vishal.riy.blocker.BlockerStateStore
import com.vishal.riy.lock.LockEngine
import com.vishal.riy.lock.PrefsLockStore
import com.vishal.riy.protection.ProtectionEventProcessor
import com.vishal.riy.protection.enforcement.AndroidEnforcementEngine
import com.vishal.riy.protection.enforcement.BlockedAppPolicy
import com.vishal.riy.protection.enforcement.LockTaskPolicy
import com.vishal.riy.protection.enforcement.PackageManagerAppPolicyResolver
import com.vishal.riy.protection.enforcement.platform.AndroidDevicePolicyBoundary
import com.vishal.riy.protection.enforcement.platform.AndroidPackageDiscoveryBoundary
import com.vishal.riy.protection.events.PrefsProtectionEventStore
import com.vishal.riy.protection.events.ProtectionLogEvent
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.state.PrefsProtectionStateStore
import java.util.UUID

/**
 * Single reconciliation entry point for EVERY package/boot/foreground trigger.
 *
 * WHY THIS EXISTS: package installation, boot, process death, and foreground
 * return must all restore the same enforcement — but the authoritative state
 * must stay in exactly the places it already lives:
 *  - the 2-hour deadline in [com.vishal.riy.lock.LockEngine]/[PrefsLockStore];
 *  - the session in [PrefsProtectionStateStore];
 *  - the install intent in [BlockerStateStore];
 *  - the platform allowlist in DevicePolicyManager (via [AndroidEnforcementEngine]).
 *
 * This object introduces NO new persisted state, NO second deadline, and NO
 * second policy engine. It only READS the stores above and drives the
 * EXISTING recovery + enforcement paths. Every trigger below funnels here:
 *  - [com.vishal.riy.protection.integrity.PackageStateWatcher] (install /
 *    enable / disable / update of ANY package);
 *  - [com.vishal.riy.blocker.BootReceiver] / [com.vishal.riy.protection.recovery.PackageBootRecovery]
 *    (reboot);
 *  - [com.vishal.riy.MainActivity] foreground self-heal;
 *  - [com.vishal.riy.blocker.BlockerVpnService] sticky restart.
 *
 * RIY's own legitimate update (same package, PACKAGE_REPLACED) is ALWAYS
 * exempt: it is verified, never hidden/suspended/blocked.
 *
 * Nothing here inspects message or media content — only package identity,
 * install metadata, and the persisted protection verdict.
 */
object ProtectionReconciler {

    private const val TAG = "RiyReconciler"

    /** True while a persisted RESTRICTED/HARDENED session is still live. */
    fun isRestrictionLive(context: Context): Boolean {
        return try {
            val app = context.applicationContext
            val session = PrefsProtectionStateStore(app).current() ?: return false
            if (session.state != ProtectionState.RESTRICTED &&
                session.state != ProtectionState.HARDENED
            ) return false
            val now = System.currentTimeMillis()
            if (session.isExpired(now)) return false
            LockEngine.isLocked(PrefsLockStore(app).loadState(), now)
        } catch (e: Exception) {
            Log.w(TAG, "isRestrictionLive failed: ${e.message}")
            false
        }
    }

    /** True when the user wants protection (VPN intent ON). */
    fun isProtectionWanted(context: Context): Boolean {
        return try {
            BlockerStateStore(context.applicationContext).isProtectionWanted()
        } catch (e: Exception) {
            Log.w(TAG, "isProtectionWanted failed: ${e.message}")
            false
        }
    }

    /**
     * Fast path for a single package event (install / replace / enable).
     * Neutralizes the package immediately when protection requires it, then
     * funnels into the full platform reconciliation. Never throws.
     *
     * @param packageName the affected package (blank = ignore).
     * @param reason short trigger name for logs (e.g. "package_added").
     */
    fun reconcilePackage(context: Context, packageName: String, reason: String) {
        if (packageName.isBlank()) return
        val app = context.applicationContext
        try {
            // RIY itself: integrity verification only, never block. A same-
            // package update (the legitimate self-update flow) must keep working.
            if (BlockedAppPolicy.isSelfPackage(packageName, app.packageName)) {
                Log.i(TAG, "$reason for RIY itself — verifying only")
                return
            }
            val live = isRestrictionLive(app)
            val wanted = isProtectionWanted(app)
            if (!live && !wanted) return

            val discovery = AndroidPackageDiscoveryBoundary(app)
            val label = runCatching { discovery.packageLabel(packageName) }.getOrNull()
            val explicitlyBlocked = BlockedAppPolicy.isBlockedPackage(packageName, label)

            // Outside a live restriction we still neutralize EXPLICIT bypass
            // apps (TeraBox family) while protection is wanted — otherwise a
            // bypass CDN that never touches a blocked adult domain would never
            // arm a restriction and would stay usable. Generic unknown apps are
            // only neutralized inside a live restriction (default-deny then
            // applies); in NORMAL they stay usable so legitimate browsing and
            // Telegram are never disturbed.
            if (!live && !explicitlyBlocked) return

            val engine = newEngine(app)
            if (live) {
                // Rebuild the in-memory expectation first (process may be fresh),
                // then neutralize this package and reconcile the platform.
                restoreExpectation(app, engine)
                val neutralized = engine.hardenSinglePackage(packageName)
                engine.reconcileWithResult()
                log(
                    app,
                    if (explicitlyBlocked) ProtectionLogEvent.Type.APP_BLOCKED
                    else ProtectionLogEvent.Type.POLICY_RECONCILED,
                    "reconcilePackage $reason: $packageName neutralized=$neutralized " +
                        "(explicitBlock=$explicitlyBlocked)",
                )
                Log.i(
                    TAG,
                    "$reason: $packageName neutralized=$neutralized " +
                        "(explicitBlock=$explicitlyBlocked)",
                )
            } else {
                // Protection wanted, no live restriction, explicit bypass app:
                // hide/suspend directly behind the Device Owner seat.
                val ok = hideDirectly(app, packageName)
                log(
                    app,
                    ProtectionLogEvent.Type.APP_BLOCKED,
                    "reconcilePackage $reason: $packageName neutralized=$ok " +
                        "(protection-wanted bypass block)",
                )
                Log.i(TAG, "$reason: $packageName protection-wanted block ok=$ok")
            }
        } catch (e: Exception) {
            Log.e(TAG, "reconcilePackage failed for $packageName ($reason)", e)
        }
    }

    /**
     * Full reconciliation pass (boot / foreground / VPN restart / package
     * removed / enabled-state change). Restores a live restriction or clears
     * an expired one via the EXISTING recovery orchestrator, then re-applies
     * package hardening so newly installed bypass apps cannot linger.
     * Never throws.
     */
    fun reconcileAll(context: Context, reason: String) {
        val app = context.applicationContext
        try {
            val recovery = ProtectionEventProcessor.recoveryForContext(app)
            val result = runCatching { recovery.recoverWithResult() }.getOrNull()
            Log.i(
                TAG,
                "reconcileAll ($reason): outcome=${result?.outcome} " +
                    "status=${result?.enforcementStatus} reconciliation=${result?.reconciliation}",
            )
            // Sweep explicitly blocked packages while protection is wanted even
            // when no restriction is live (closes the never-detected bypass).
            if (isProtectionWanted(app) && !isRestrictionLive(app)) {
                sweepBypassApps(app)
            }
        } catch (e: Exception) {
            Log.e(TAG, "reconcileAll failed ($reason)", e)
        }
    }

    // ------------------------------------------------------------------ priv

    private fun newEngine(context: Context): AndroidEnforcementEngine {
        val app = context.applicationContext
        val discovery = AndroidPackageDiscoveryBoundary(app)
        return AndroidEnforcementEngine(
            devicePolicy = AndroidDevicePolicyBoundary(app),
            discovery = discovery,
            appPolicyResolver = PackageManagerAppPolicyResolver(discovery, app.packageName),
            riyPackageName = app.packageName,
        )
    }

    private fun restoreExpectation(context: Context, engine: AndroidEnforcementEngine) {
        val app = context.applicationContext
        try {
            val session = PrefsProtectionStateStore(app).current() ?: return
            if (session.state != ProtectionState.RESTRICTED &&
                session.state != ProtectionState.HARDENED
            ) return
            engine.restoreExpectation(
                session,
                LockTaskPolicy(
                    allowedPackages = session.allowedPackages,
                    allowHome = false,
                    allowOverview = false,
                    allowNotifications = true,
                    allowSystemInfo = true,
                    allowGlobalActions = true,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "restoreExpectation failed: ${e.message}")
        }
    }

    /** Direct Device Owner hide+suspend for the protection-wanted bypass path. */
    private fun hideDirectly(context: Context, packageName: String): Boolean {
        return try {
            val app = context.applicationContext
            val dpm = AndroidDevicePolicyBoundary(app)
            val admin = com.vishal.riy.protection.enforcement.platform.AdminComponent(
                app.packageName,
                com.vishal.riy.admin.RiyDeviceAdminReceiver::class.java.name,
            )
            if (!dpm.isDeviceOwnerApp(app.packageName)) return false
            val hid = dpm.setApplicationHidden(admin, packageName, true)
            val sus = dpm.setPackagesSuspended(admin, listOf(packageName), true)
            hid || sus
        } catch (e: Exception) {
            Log.w(TAG, "hideDirectly failed for $packageName: ${e.message}")
            false
        }
    }

    /**
     * Hardens the device while protection is wanted but no restriction is live:
     * hides every installed explicit-bypass app (TeraBox family) and raises
     * the install/Private-DNS/VPN user restrictions. Generic unknown apps are
     * deliberately untouched here (default-deny governs them only inside a
     * live restriction), so Telegram and legitimate apps stay usable.
     */
    private fun sweepBypassApps(context: Context) {
        try {
            val app = context.applicationContext
            val discovery = AndroidPackageDiscoveryBoundary(app)
            val installed = runCatching { discovery.installedPackages() }.getOrElse { emptyList() }
            installed
                .filter {
                    it.packageName != app.packageName &&
                        BlockedAppPolicy.isBlockedPackage(it.packageName, it.label)
                }
                .forEach { pkg ->
                    val ok = hideDirectly(app, pkg.packageName)
                    Log.i(TAG, "sweepBypassApps: ${pkg.packageName} hidden=$ok")
                }
            // Unknown-source installs, Private DNS, and rogue VPN config are
            // the three bypass transports the platform lets a Device Owner
            // remove. Safe while merely wanted: Play installs, RIY's own
            // verified self-update, and Telegram networking are unaffected.
            // Cleared automatically when protection is off (engine restore /
            // reconcile with no expected session).
            runCatching {
                val dpm = AndroidDevicePolicyBoundary(app)
                val admin = com.vishal.riy.protection.enforcement.platform.AdminComponent(
                    app.packageName,
                    com.vishal.riy.admin.RiyDeviceAdminReceiver::class.java.name,
                )
                if (dpm.isDeviceOwnerApp(app.packageName)) {
                    dpm.addUserRestriction(
                        admin,
                        com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary
                            .RESTRICTION_INSTALL_UNKNOWN_SOURCES,
                    )
                    dpm.addUserRestriction(
                        admin,
                        com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary
                            .RESTRICTION_CONFIG_PRIVATE_DNS,
                    )
                    dpm.addUserRestriction(
                        admin,
                        com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary
                            .RESTRICTION_CONFIG_VPN,
                    )
                }
            }.onFailure { e ->
                Log.w(TAG, "sweepBypassApps restrictions failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "sweepBypassApps failed: ${e.message}")
        }
    }

    private fun log(context: Context, type: ProtectionLogEvent.Type, message: String) {
        runCatching {
            PrefsProtectionEventStore(context.applicationContext).append(
                ProtectionLogEvent(
                    eventId = "reconcile-${UUID.randomUUID()}",
                    type = type,
                    timestamp = System.currentTimeMillis(),
                    message = message.take(240),
                ),
            )
        }
    }
}
