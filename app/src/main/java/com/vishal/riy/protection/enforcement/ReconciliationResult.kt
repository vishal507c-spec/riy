package com.vishal.riy.protection.enforcement

/**
 * The outcome of comparing the policy RIY EXPECTS to be applied against the
 * policy Android is ACTUALLY enforcing.
 *
 * Nothing here is a guess: [VERIFIED] is only ever returned after the live
 * lock-task allowlist and feature set have been READ BACK from the platform
 * and found to match. A requested API call succeeding is NOT sufficient.
 */
enum class ReconciliationResult {

    /** Expected policy == actual platform state, read back and confirmed. */
    VERIFIED,

    /** The platform is enforcing something different from the expectation. */
    MISMATCH,

    /**
     * RIY is not the Device Owner, so NO privileged operation was attempted.
     * Reported explicitly rather than crashing or pretending success.
     */
    NOT_DEVICE_OWNER,

    /** The platform rejected the operation or the state could not be read. */
    ERROR,
}
