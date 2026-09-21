package com.vishal.riy.protection.integrity

/**
 * One discrepancy found while verifying the protection system. Pure Kotlin.
 *
 * @param component   which part was checked (for example "device_owner").
 * @param description what is wrong, in terms a human or log can act on.
 * @param severity    how seriously the mismatch should be treated.
 */
data class IntegrityIssue(

    val component: String,

    val description: String,

    val severity: Severity,
) {

    /** How a mismatch should be weighed. */
    enum class Severity {

        /** Informational; the system self-corrected. */
        INFO,

        /** State drifted; reconciliation was required. */
        WARN,

        /** A protection component is missing or tampered; escalate. */
        ERROR,

        /** Freezer-based degradation that threatens protection. */
        CRITICAL,
    }
}
