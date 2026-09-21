package com.vishal.riy.protection.enforcement

/**
 * A complete snapshot of the enforcement layer's view of the device, built
 * entirely from REAL platform state (see [AndroidEnforcementEngine.diagnostics]).
 *
 * Every field is read live; none is a constant or a cached request. This is
 * what the internal test hook prints so a real-device session can be audited,
 * and what an integrity check can diff against expectations.
 *
 * @param deviceOwner        whether RIY is the Device Owner right now.
 * @param adminComponent     the authoritative admin component (flattened).
 * @param adminActive        whether that admin component is active.
 * @param riyPackagePresent  whether RIY's own package is installed.
 * @param riyEnabled         whether RIY's own package is enabled.
 * @param uninstallProtected whether RIY's package is uninstall-blocked.
 * @param lockTaskAllowlist  the ACTUAL packages the platform allows under lock
 *                           task, read back from DevicePolicyManager.
 * @param allowHome          actual lock-task Home affordance, read back.
 * @param allowOverview      actual lock-task Overview affordance, read back.
 * @param phonePackage       the verified phone package, or null if unresolved.
 * @param walletPackage      the verified wallet package, or null if unresolved.
 * @param emergencyPackage   the verified emergency package, or null.
 * @param inputMethodPackage the keyboard package policy keeps available.
 * @param applicationResult  the outcome of the last apply attempt.
 * @param reconciliation     the outcome of the last reconciliation.
 * @param issues             enforcement/integrity notes worth recording.
 * @param generatedAt        epoch ms when this snapshot was taken.
 */
data class EnforcementDiagnosticReport(

    val deviceOwner: Boolean,

    val adminComponent: String,

    val adminActive: Boolean,

    val riyPackagePresent: Boolean,

    val riyEnabled: Boolean,

    val uninstallProtected: Boolean,

    val lockTaskAllowlist: List<String>,

    val allowHome: Boolean,

    val allowOverview: Boolean,

    val phonePackage: String?,

    val walletPackage: String?,

    val emergencyPackage: String?,

    val inputMethodPackage: String?,

    val applicationResult: PolicyApplicationResult,

    val reconciliation: ReconciliationResult,

    val issues: List<String>,

    val generatedAt: Long,
) {

    /** A stable, copy-pasteable multi-line dump for logs and the test hook. */
    fun toReportString(): String = buildString {
        appendLine("=== RIY Enforcement Diagnostics ===")
        appendLine("Device Owner        : $deviceOwner")
        appendLine("Admin component     : $adminComponent (active=$adminActive)")
        appendLine("RIY package present : $riyPackagePresent (enabled=$riyEnabled)")
        appendLine("Uninstall protected : $uninstallProtected")
        appendLine("Lock-task allowlist : ${lockTaskAllowlist.sorted()}")
        appendLine("  allowHome         : $allowHome")
        appendLine("  allowOverview     : $allowOverview")
        appendLine("Phone package       : ${phonePackage ?: "UNRESOLVED"}")
        appendLine("Wallet package      : ${walletPackage ?: "UNRESOLVED"}")
        appendLine("Emergency package   : ${emergencyPackage ?: "UNRESOLVED"}")
        appendLine("Input method        : ${inputMethodPackage ?: "UNRESOLVED"}")
        appendLine("Last apply result   : $applicationResult")
        appendLine("Reconciliation      : $reconciliation")
        if (issues.isNotEmpty()) {
            appendLine("Issues:")
            issues.forEach { appendLine("  - $it") }
        } else {
            appendLine("Issues: none")
        }
        appendLine("Generated at        : $generatedAt")
        appendLine("=== End diagnostics ===")
    }
}
