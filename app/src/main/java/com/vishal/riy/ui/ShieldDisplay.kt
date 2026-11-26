package com.vishal.riy.ui

import com.vishal.riy.blocker.ShieldStatus

/**
 * The ONLY four states the Shield dashboard is allowed to show.
 *
 * The backend keeps its finer [ShieldStatus] values for diagnostics and tests,
 * but the user sees exactly one of these four words, always backed by evidence:
 *  - [PROTECTED] only after a real self-test proved enforcement;
 *  - [CHECKING] while a verification/recovery is genuinely in flight;
 *  - [NOT_VERIFIED] when the filter is up but cannot be confirmed;
 *  - [NOT_PROTECTED] when protection is off, missing permission or disconnected.
 */
enum class ShieldDisplayState {
    PROTECTED,
    CHECKING,
    NOT_VERIFIED,
    NOT_PROTECTED,
}

/**
 * Pure mapping from the authoritative backend status to one of the four display
 * states. `busy` is the immediate UI acknowledgement of a button press — it can
 * only ADD a CHECKING state, never turn an unverified filter into PROTECTED.
 */
fun shieldDisplayOf(status: ShieldStatus, busy: Boolean = false): ShieldDisplayState = when {
    busy || status == ShieldStatus.INITIALIZING || status == ShieldStatus.UNKNOWN ->
        ShieldDisplayState.CHECKING

    status == ShieldStatus.VERIFIED -> ShieldDisplayState.PROTECTED

    status == ShieldStatus.RUNNING_UNVERIFIED ||
        status == ShieldStatus.FILTER_FAILED -> ShieldDisplayState.NOT_VERIFIED

    else -> ShieldDisplayState.NOT_PROTECTED
}
