package com.vishal.riy.guard

/**
 * ONE immutable, reduced description of a single accessibility node.
 *
 * PRIVACY CONTRACT: this is the COMPLETE set of properties any guard rule may
 * ever read. No rule can reach an [android.view.View], a bitmap, a database, a
 * content provider or the network, and nothing here is ever persisted,
 * serialised or transmitted. Only the short label is consulted, and only to
 * recognise a navigation destination — never to read a conversation.
 */
data class NodeEvidence(

    /** Fully qualified view class (e.g. "android.widget.TextView"). */
    val className: String = "",

    /** Full resource id ("com.whatsapp:id/status_row"), "" when absent. */
    val viewId: String = "",

    /** Visible text of the node ("" when the node exposes none). */
    val text: String = "",

    /** Accessibility label of the node ("" when absent). */
    val contentDescription: String = "",

    /** The node itself reacts to a click. */
    val clickable: Boolean = false,

    /** The node accepts text input (a message composer, search field, …). */
    val editable: Boolean = false,

    /** The node scrolls its own children. */
    val scrollable: Boolean = false,

    /** The node is the currently selected item of its group (a nav tab, …). */
    val selected: Boolean = false,

    /** Number of direct children. */
    val childCount: Int = 0,

    /** Depth in the inspected hierarchy (root = 0). */
    val depth: Int = 0,

    /**
     * Resource id of the nearest ancestor that has one ("" when the tree above
     * this node is anonymous). This is how containment is judged WITHOUT any
     * screen coordinate: "is this label inside the bottom navigation bar?".
     */
    val containerId: String = "",

    /** True when any ancestor of this node is clickable. */
    val hasClickableAncestor: Boolean = false,
) {

    /** The best short label available, or null when the node exposes none. */
    val label: String?
        get() = text.normalizedLabel() ?: contentDescription.normalizedLabel()

    /**
     * Resource-id PACKAGE of [viewId] — the "com.whatsapp" of
     * "com.whatsapp:id/status_row". Android joins the two with ':' before the
     * name, so this is deliberately split on ':' and not on '/'.
     *
     * This is the strongest code-level containment signal available: a resource
     * id's package is minted by the module that owns the view, which makes it a
     * component boundary rather than a rendered string.
     */
    val viewIdPackage: String
        get() = viewId.substringBefore(':', missingDelimiterValue = "")

    /** This node plus its nearest identified ancestor, lower-cased for matching. */
    val idChain: String
        get() = "$viewId/$containerId".lowercase()

    companion object {

        /** Collapses whitespace and lower-cases; "" means "no label". */
        fun String?.normalizedLabel(): String? =
            this?.trim()?.lowercase()?.replace(WHITESPACE, " ")?.takeIf { it.isNotEmpty() }

        private val WHITESPACE = Regex("\\s+")
    }
}

/** The accessibility event kinds the guard pipeline reacts to. */
enum class GuardEventType {
    WINDOW_STATE_CHANGED,
    WINDOW_CONTENT_CHANGED,
    VIEW_CLICKED,
    OTHER,
}

/**
 * Everything a rule is allowed to see about "where the user currently is":
 * which app, which activity, and the reduced node evidence of the window.
 *
 * Deliberately contains no raw [android.view.accessibility.AccessibilityNodeInfo]
 * so the whole decision layer stays pure JVM and unit-testable.
 */
data class ScreenSnapshot(
    val packageName: String = "",
    val activityClassName: String = "",
    val eventType: GuardEventType = GuardEventType.OTHER,
    val nodes: List<NodeEvidence> = emptyList(),
) {
    companion object {
        /** An empty snapshot — every rule must treat it as "do nothing". */
        val EMPTY = ScreenSnapshot()
    }
}
