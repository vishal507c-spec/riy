package com.vishal.riy.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.vishal.riy.R
import com.vishal.riy.blocker.ShieldStatus
import com.vishal.riy.protection.enforcement.AllowedApp
import com.vishal.riy.protection.enforcement.AllowedAppCategory
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.ui.ProtectionUiState

/**
 * The zero-confusion mapping: the AUTHORITATIVE [ProtectionUiState] translated
 * into the one human-language status a completely non-technical person reads.
 *
 * THIS IS NOT A STATE MACHINE. It is a pure function of the backend state — it
 * holds no transition table, no lifecycle, no memory of its own and it can
 * change nothing. The only state machine stays
 * [com.vishal.riy.protection.policy.ProtectionPolicyEngine]; this file only
 * decides which human words and which icon the value that engine produced is
 * shown with. Every precedence rule below mirrors a derived flag that already
 * exists on [ProtectionUiState], so the two can never disagree about what the
 * device is under.
 *
 * Developer terminology is never exposed: the words NORMAL, SUSPICIOUS,
 * CONFIRMED, RESTRICTED, HARDENED and RECOVERY exist only in the backend and
 * appear nowhere in any string resource this mapping selects.
 */
data class ProtectionStatusPresentation(

    /** Large title, e.g. "You're Protected". */
    val titleRes: Int,

    /** Short explanation under the title. */
    val detailRes: Int,

    /** One large status icon. */
    val icon: ImageVector,

    /** True when the countdown to the single deadline should be shown large. */
    val showsCountdown: Boolean,

    /** The line the primary status card states, e.g. "Protection Active". */
    val activeLineRes: Int,

    /** The supporting line under it, e.g. "Some apps are temporarily unavailable." */
    val activeDetailRes: Int,
)

/**
 * The single mapping. Precedence is deliberate: a broken filter outranks
 * everything, because an app-restriction story on a device whose filter is not
 * running is still "not protected". Then an honest warning outranks a
 * reassuring label, an in-flight restoration outranks a resolved one, and an
 * escalated restriction is named differently from an ordinary one — but
 * RESTRICTED and HARDENED share the SAME deadline, so both show the SAME
 * countdown and there is never a second timer on screen.
 *
 * The shield states come first on purpose: "You're Protected" must never be
 * reachable while the network filter is off, disconnected, broken or simply
 * unverified.
 */
fun ProtectionUiState.userFacing(): ProtectionStatusPresentation = when {

    shieldStatus == ShieldStatus.FILTER_FAILED -> ProtectionStatusPresentation(
        titleRes = R.string.status_filter_failed_title,
        detailRes = R.string.status_filter_failed_detail,
        icon = Icons.Filled.Warning,
        showsCountdown = false,
        activeLineRes = R.string.filter_label_failed,
        activeDetailRes = R.string.filter_failed,
    )

    shieldStatus == ShieldStatus.PERMISSION_MISSING -> ProtectionStatusPresentation(
        titleRes = R.string.status_permission_title,
        detailRes = R.string.status_permission_detail,
        icon = Icons.Filled.Warning,
        showsCountdown = false,
        activeLineRes = R.string.filter_label_permission_missing,
        activeDetailRes = R.string.filter_permission_missing,
    )

    shieldStatus == ShieldStatus.DISCONNECTED -> ProtectionStatusPresentation(
        titleRes = R.string.status_disconnected_title,
        detailRes = R.string.status_disconnected_detail,
        icon = Icons.Filled.Autorenew,
        showsCountdown = false,
        activeLineRes = R.string.filter_label_disconnected,
        activeDetailRes = R.string.filter_disconnected,
    )

    shieldStatus == ShieldStatus.RUNNING_UNVERIFIED -> ProtectionStatusPresentation(
        titleRes = R.string.status_unverified_title,
        detailRes = R.string.status_unverified_detail,
        icon = Icons.Filled.Warning,
        showsCountdown = false,
        activeLineRes = R.string.filter_label_unverified,
        activeDetailRes = R.string.filter_unverified,
    )

    shieldStatus == ShieldStatus.INITIALIZING ||
    shieldStatus == ShieldStatus.UNKNOWN -> ProtectionStatusPresentation(
        titleRes = R.string.status_starting_title,
        detailRes = R.string.status_starting_detail,
        icon = Icons.Filled.Autorenew,
        showsCountdown = false,
        activeLineRes = R.string.filter_label_starting,
        activeDetailRes = R.string.filter_initializing,
    )

    shieldStatus == ShieldStatus.DISABLED -> ProtectionStatusPresentation(
        titleRes = R.string.status_off_title,
        detailRes = R.string.status_off_detail,
        icon = Icons.Filled.Shield,
        showsCountdown = false,
        activeLineRes = R.string.filter_label_off,
        activeDetailRes = R.string.filter_off,
    )

    enforcementMismatch -> ProtectionStatusPresentation(
        titleRes = R.string.status_mismatch_title,
        detailRes = R.string.status_mismatch_detail,
        icon = Icons.Filled.Warning,
        showsCountdown = false,
        activeLineRes = R.string.protection_active_line,
        activeDetailRes = R.string.protection_active_normal,
    )

    isRecovering -> ProtectionStatusPresentation(
        titleRes = R.string.status_recovery_title,
        detailRes = R.string.status_recovery_detail,
        icon = Icons.Filled.Autorenew,
        showsCountdown = false,
        activeLineRes = R.string.protection_active_line,
        activeDetailRes = R.string.recovery_step_verifying,
    )

    protectionState == ProtectionState.HARDENED -> ProtectionStatusPresentation(
        titleRes = R.string.status_hardened_title,
        detailRes = R.string.status_hardened_detail,
        icon = Icons.Filled.EnhancedEncryption,
        showsCountdown = true,
        activeLineRes = R.string.protection_active_line,
        activeDetailRes = R.string.protection_active_hardened,
    )

    isRestricted -> ProtectionStatusPresentation(
        titleRes = R.string.status_restricted_title,
        detailRes = R.string.status_restricted_detail,
        icon = Icons.Filled.Lock,
        showsCountdown = true,
        activeLineRes = R.string.protection_active_line,
        activeDetailRes = R.string.protection_active_restricted,
    )

    else -> ProtectionStatusPresentation(
        titleRes = R.string.status_protected_title,
        detailRes = R.string.status_protected_detail,
        icon = Icons.Filled.Shield,
        showsCountdown = false,
        activeLineRes = R.string.protection_active_line,
        activeDetailRes = R.string.protection_active_normal,
    )
}

/**
 * The friendly, human-readable label for one allowed app. The resolver already
 * prefers the app's real launcher label; this only substitutes a plain word
 * when that label fell back to the raw package name — so a package name is the
 * one thing the UI can never end up showing.
 *
 * Composable because the fallback comes from string resources.
 */
@Composable
fun AllowedApp.friendlyLabel(
    riyPackageLabel: String = stringResource(R.string.app_name_riy),
): String = when {
    displayName != packageName -> displayName
    category == AllowedAppCategory.RIY -> riyPackageLabel
    category == AllowedAppCategory.PHONE -> stringResource(R.string.app_name_phone)
    category == AllowedAppCategory.WALLET -> stringResource(R.string.app_name_wallet)
    category == AllowedAppCategory.EMERGENCY -> stringResource(R.string.app_name_emergency)
    category == AllowedAppCategory.COMMUNICATION -> stringResource(R.string.app_name_telegram)
    else -> stringResource(R.string.app_name_keyboard)
}

/**
 * The app categories worth listing as "Available right now". The input method
 * is deliberately excluded: it is a mechanism, not something a user thinks of
 * as an app they can open.
 */
val AllowedAppCategory.isShownAsAvailable: Boolean
    get() = this == AllowedAppCategory.RIY ||
        this == AllowedAppCategory.PHONE ||
        this == AllowedAppCategory.WALLET ||
        this == AllowedAppCategory.EMERGENCY ||
        this == AllowedAppCategory.COMMUNICATION
