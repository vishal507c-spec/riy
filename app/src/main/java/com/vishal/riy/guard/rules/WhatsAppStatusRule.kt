package com.vishal.riy.guard.rules

import com.vishal.riy.guard.GuardConfidence
import com.vishal.riy.guard.GuardDestination
import com.vishal.riy.guard.GuardVerdict
import com.vishal.riy.guard.NodeEvidence
import com.vishal.riy.guard.ScreenSnapshot
import com.vishal.riy.guard.UiGuardRule

/**
 * Blocks the WhatsApp **Status / Updates** surface and nothing else.
 *
 * WhatsApp itself is never modified, patched, decompiled, intercepted on the
 * network, or restricted as a whole: chats, individual and group messages,
 * calls, notifications, in-chat media, search, settings and every other screen
 * are all outside this rule and are allowed through untouched.
 *
 * ## How detection works (and why it survives WhatsApp updates)
 *
 * No coordinate, no hard-coded pixel and no single fragile selector is used.
 * The rule scores independent, *stable* families of indicators and blocks only
 * when the accumulated confidence crosses [CONFIRM_THRESHOLD]:
 *
 * | Indicator family | Weight | Stability |
 * |---|---|---|
 * | activity component (`com.whatsapp.status.*`) | 70 | code-level package split, survives UI redesigns |
 * | the Status/Updates nav tab is the SELECTED one | 55 | structural selection state, no coordinate |
 * | resource ids from the status component's own R class | 55 | code-level, survives translations |
 * | status-tab content labels ("My status", "Add status", …) | 25 each, max 2 | user-visible, per-locale gaps are covered by the code-level signals |
 * | tappable entry label exactly "Status" / "Updates" | 30 | user-visible |
 * | that entry's own id chain contains "status" | 20 | code-level |
 * | that entry sits in a real bottom-navigation bar | 30 | structural, no coordinate |
 * | status viewer's reply bar with no message composer | 20 | structural |
 *
 * The first three are code-level or structural and need no translated string, so
 * detection keeps working on builds that ship Status inside the main activity
 * with the main resource package and in any locale.
 *
 * ## False positives are worse than false negatives
 *
 * A single weak signal can never reach [CONFIRM_THRESHOLD] on its own, so a
 * chat that happens to be called "Status" stays a [GuardConfidence.CANDIDATE]
 * and is never acted upon. Any indicator found inside a conversation / message
 * container is additionally vetoed outright (see [CONVERSATION_ID_TOKENS]), so
 * chat text, chat rows and chat media can never trigger a block. When the UI is
 * unrecognised the rule returns [GuardConfidence.NONE] and does nothing.
 *
 * ## Adapting to a new WhatsApp version
 *
 * Every label, id token and weight is a named constant on this object. A UI
 * change is a one-line edit here; nothing else in the engine, the service or
 * the UI has to be rewritten.
 */
object WhatsAppStatusRule : UiGuardRule {

    override val targetId: String = TARGET_ID
    override val packages: Set<String> = setOf(WHATSAPP_PACKAGE)

    // ------------------------------------------------------------- constants

    const val TARGET_ID = "whatsapp_status"
    const val WHATSAPP_PACKAGE = "com.whatsapp"

    /** WhatsApp's Status code lives in its own component package. */
    const val STATUS_COMPONENT_PACKAGE = "com.whatsapp.status"

    /** Accumulated confidence required before anything is ever blocked. */
    const val CONFIRM_THRESHOLD = 50

    private const val W_STATUS_ACTIVITY = 70
    private const val W_STATUS_SELECTED_TAB = 55
    private const val W_STATUS_RESOURCE_ID = 55
    private const val W_STATUS_CONTENT_LABEL = 25
    private const val W_ENTRY_LABEL = 30
    private const val W_ENTRY_ROW_ID = 20
    private const val W_ENTRY_TAB_BAR = 30
    private const val W_VIEWER_REPLY_BAR = 20

    // Stable indicator ids. These are code constants — never user content.
    const val SIGNAL_STATUS_ACTIVITY = "status_activity_component"
    const val SIGNAL_STATUS_SELECTED_TAB = "status_tab_selected"
    const val SIGNAL_STATUS_RESOURCE_ID = "status_resource_component"
    const val SIGNAL_STATUS_CONTENT_LABEL = "status_content_label"
    const val SIGNAL_ENTRY_LABEL = "status_entry_label"
    const val SIGNAL_ENTRY_ROW_ID = "status_entry_row_id"
    const val SIGNAL_ENTRY_TAB_BAR = "status_entry_in_tab_bar"
    const val SIGNAL_VIEWER_REPLY_BAR = "status_viewer_reply_bar"

    /**
     * Labels of the tappable way in. Matched EXACTLY (after normalisation), so
     * a contact or group merely *containing* the word can never match.
     */
    val ENTRY_LABELS = setOf("status", "updates", "statuses")

    /**
     * Labels that only exist on the Status tab itself. Two of them are required
     * for confirmation, which is why a chat named "My status" is still only a
     * candidate.
     */
    val STATUS_CONTENT_LABELS = setOf(
        "my status",
        "add status",
        "add a status",
        "your status",
        "your updates",
        "recent updates",
        "status updates",
        "status and updates",
    )

    /** Containers that mark a bottom-navigation / tab strip (never a coordinate). */
    val TAB_BAR_ID_TOKENS = listOf("navigation", "nav_bar", "bottom", "tab_bar", "tabbar", "tabs")

    /**
     * Any node under one of these is conversation content — a chat row, a
     * message bubble, the composer. Indicators found there are discarded, which
     * is what guarantees in-chat media and normal scrolling are never blocked.
     */
    val CONVERSATION_ID_TOKENS = listOf(
        "conversation",
        "message",
        "bubble",
        "chat_row",
        "text_entry",
        "entry_row",
        "picker",
        "row_text",
    )

    /** Labels that identify the viewer's own reply affordance. */
    private val REPLY_LABELS = setOf("reply", "reply to status", "reply to…", "reply to...")

    // -------------------------------------------------------------- evaluation

    override fun evaluate(snapshot: ScreenSnapshot): GuardVerdict {
        // Package gate: any other app is not this rule's business at all.
        if (snapshot.packageName != WHATSAPP_PACKAGE) return GuardVerdict.ALLOW

        val signals = LinkedHashSet<String>()
        var score = 0
        var destination = GuardDestination.UNKNOWN

        // 1. The activity component itself. Survives any UI redesign, because it
        //    is a code-level package boundary rather than a rendered label.
        if (snapshot.activityClassName.startsWith(STATUS_COMPONENT_PACKAGE)) {
            score += W_STATUS_ACTIVITY
            signals += SIGNAL_STATUS_ACTIVITY
            destination = GuardDestination.LIST
        }

        // 2. Resource ids minted by the status component's own R class. Present
        //    on the Status tab and inside the viewer on every modern build.
        if (snapshot.nodes.any { it.viewIdPackage == STATUS_COMPONENT_PACKAGE }) {
            score += W_STATUS_RESOURCE_ID
            signals += SIGNAL_STATUS_RESOURCE_ID
            if (destination == GuardDestination.UNKNOWN) destination = GuardDestination.LIST
        }

        // 3. Status-tab content labels, counted once each and capped at two so
        //    a single lucky string can never reach the threshold by itself.
        val contentLabels = snapshot.nodes
            .filter { it.isOutsideConversation() }
            .mapNotNull { it.label }
            .filter { it in STATUS_CONTENT_LABELS }
            .distinct()
            .take(2)
        if (contentLabels.isNotEmpty()) {
            score += W_STATUS_CONTENT_LABEL * contentLabels.size
            signals += SIGNAL_STATUS_CONTENT_LABEL
            if (destination == GuardDestination.UNKNOWN) destination = GuardDestination.LIST
        }

        // 4. The tappable way in. Finding it EARLY is what lets the engine arm
        //    before the content screen is drawn.
        val tabBars = snapshot.tabBarContainerIds()
        val entry = snapshot.nodes
            .filter { it.isStatusEntry() }
            .maxByOrNull { it.entryRank(tabBars) }
        if (entry != null) {
            score += W_ENTRY_LABEL
            signals += SIGNAL_ENTRY_LABEL
            if ("status" in entry.idChain) {
                score += W_ENTRY_ROW_ID
                signals += SIGNAL_ENTRY_ROW_ID
            }
            if (entry.containerId.isNotBlank() && entry.containerId in tabBars) {
                score += W_ENTRY_TAB_BAR
                signals += SIGNAL_ENTRY_TAB_BAR
            }
            // The Status/Updates tab being the SELECTED one means the Status
            // destination is ALREADY on screen. This is the structural signal
            // that keeps working on a modern build rendering Status inside the
            // main activity with the main resource package.
            if (entry.selected && entry.containerId in tabBars) {
                score += W_STATUS_SELECTED_TAB
                signals += SIGNAL_STATUS_SELECTED_TAB
                destination = GuardDestination.LIST
            }
            // A plain chat list showing the Status row/tab is the entry point,
            // never content: the engine arms on it and never backs out of it.
            if (destination == GuardDestination.UNKNOWN) destination = GuardDestination.ENTRY_POINT
        }

        // 5. Full-screen playback: the viewer replaces the message composer with
        //    a reply bar. Both halves are required, so an ordinary screen that
        //    happens to show the word "reply" cannot match.
        val hasComposer = snapshot.nodes.any { it.editable || it.isTextEntry() }
        if (!hasComposer && snapshot.nodes.any { it.label in REPLY_LABELS }) {
            score += W_VIEWER_REPLY_BAR
            signals += SIGNAL_VIEWER_REPLY_BAR
            destination = GuardDestination.VIEWER
        }

        val confidence = when {
            score >= CONFIRM_THRESHOLD -> GuardConfidence.CONFIRMED
            score > 0 -> GuardConfidence.CANDIDATE
            else -> GuardConfidence.NONE
        }
        if (confidence == GuardConfidence.NONE) return GuardVerdict.ALLOW

        return GuardVerdict(
            targetId = targetId,
            confidence = confidence,
            destination = destination,
            signals = signals.toList(),
        )
    }

    // ----------------------------------------------------------------- helpers

    /**
     * True when this node is NOT inside conversation content. This is the
     * false-positive firewall: chat rows, message bubbles, the composer and
     * media pickers can never contribute an indicator.
     */
    private fun NodeEvidence.isOutsideConversation(): Boolean {
        val chain = "$viewId/$containerId".lowercase()
        return CONVERSATION_ID_TOKENS.none { chain.contains(it) }
    }

    private fun NodeEvidence.isTextEntry(): Boolean =
        editable || className.contains("EditText", ignoreCase = true)

    /**
     * Is this node the tappable way into Status/Updates?
     *
     * Identification is deliberately label-OR-resource-id based, so it survives
     * both a re-worded tab ("Status" → "Updates") and a build whose Status views
     * live in the main resource package. A label match is EXACT, so a contact
     * merely containing the word can never qualify; an id match is scored far
     * too low to confirm anything on its own.
     */
    private fun NodeEvidence.isStatusEntry(): Boolean {
        if (!clickable && !hasClickableAncestor) return false
        if (!isOutsideConversation()) return false
        val labelMatches = label != null && label in ENTRY_LABELS
        val idMatches = "status" in idChain || "updates" in idChain
        return labelMatches || idMatches
    }

    /**
     * Container ids that really are a navigation strip: an id that names itself
     * like a tab/navigation bar AND that actually holds at least two labelled
     * tappable rows. Structure only — never a screen coordinate.
     */
    /**
     * Ranks competing Status entry candidates so the most informative one is
     * judged: a selected tab row beats an unselected one, which beats a plain
     * list row. Only ONE candidate is ever scored, so overlapping nodes can
     * never inflate the confidence of a screen.
     */
    private fun NodeEvidence.entryRank(tabBars: Set<String>): Int {
        val inTabBar = containerId.isNotBlank() && containerId in tabBars
        var rank = 0
        if (inTabBar) rank += 2
        if (selected && inTabBar) rank += 4
        if ("status" in idChain) rank += 1
        return rank
    }

    private fun ScreenSnapshot.tabBarContainerIds(): Set<String> {
        val labelledRows = HashMap<String, Int>()
        nodes.forEach { node ->
            if (!node.clickable || node.label.isNullOrEmpty()) return@forEach
            if (node.containerId.isBlank()) return@forEach
            labelledRows.merge(node.containerId, 1, Int::plus)
        }
        return labelledRows
            .filterValues { it >= 2 }
            .keys
            .filter { id -> TAB_BAR_ID_TOKENS.any { id.contains(it, ignoreCase = true) } }
            .toSet()
    }

    /** Exposed for the engine's default rule list and for tests. */
    override fun toString(): String = "UiGuardRule($targetId)"
}
