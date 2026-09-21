package com.vishal.riy.protection.risk

import com.vishal.riy.protection.event.ProtectionEvent
import com.vishal.riy.protection.event.ProtectionEvidenceType

/**
 * Immutable result of classifying one [ProtectionEvent]. Pure Kotlin: it holds
 * no Android object, no reference to DevicePolicyManager and no ability to
 * change anything. It is an opinion about evidence, nothing more.
 *
 * @param riskLevel   the assigned severity.
 * @param confidence  the evidence confidence that produced it.
 * @param evidenceType the evidence type that was classified (kept explicitly so
 *                     a consumer never has to re-derive it from the event).
 * @param reasoning   a short machine- and human-readable explanation, for the
 *                    event log and the UI's recent-events list.
 * @param event       the original evidence.
 */
data class RiskAssessment(

    val riskLevel: RiskLevel,

    val confidence: ProtectionEvent.Confidence,

    val evidenceType: ProtectionEvidenceType,

    val reasoning: String,

    val event: ProtectionEvent,
) {

    /** True when this assessment describes actionable prohibited content. */
    val isActionable: Boolean
        get() = riskLevel == RiskLevel.CONFIRMED ||
            riskLevel == RiskLevel.RESTRICTED ||
            riskLevel == RiskLevel.HARDENED
}
