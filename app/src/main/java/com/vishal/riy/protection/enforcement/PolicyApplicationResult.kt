package com.vishal.riy.protection.enforcement

/**
 * The outcome of one attempt to apply (or restore) the lock-task policy.
 *
 * IMPORTANT: [APPLIED] means the platform ACCEPTED the calls AND a subsequent
 * read-back confirmed the state. Anything less is reported honestly by the
 * other states, together with a human/log-readable [reason].
 */
enum class PolicyApplicationResult {

    /** Policy was applied and then VERIFIED by reading the platform state back. */
    APPLIED,

    /** RIY is not the Device Owner; no privileged operation was attempted. */
    NOT_DEVICE_OWNER,

    /**
     * The resolved allowlist failed the pre-flight safety check (for example it
     * was empty, contained only RIY, or was missing the required phone app).
     * NOTHING was applied — this prevents an accidental self-lockout.
     */
    REJECTED_UNSAFE_ALLOWLIST,

    /** The platform rejected the write, or the read-back did not match. */
    FAILED,
}
