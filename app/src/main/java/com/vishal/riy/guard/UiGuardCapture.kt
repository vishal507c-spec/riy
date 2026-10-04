package com.vishal.riy.guard

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Turns a live [AccessibilityEvent] plus the current window root into the
 * reduced [ScreenSnapshot] the rules are allowed to see.
 *
 * This is the ONLY place that touches the Android accessibility API; every
 * decision above it is pure JVM.
 *
 * PRIVACY + PERFORMANCE:
 *  - the walk is bounded ([MAX_NODES] nodes, [MAX_DEPTH] levels) so a large
 *    chat list can never stall the main thread;
 *  - only the fields declared on [NodeEvidence] are copied out. No bitmap, no
 *    view internals, no content provider, no keystroke, no package listing;
 *  - nothing is stored or transmitted — the snapshot exists for the duration of
 *    one event callback and is then dropped.
 */
object UiGuardCapture {

    /** Hard cap on inspected nodes per event. */
    const val MAX_NODES = 80

    /** Hard cap on hierarchy depth per event. */
    const val MAX_DEPTH = 14

    /** Builds the snapshot for one event. Never throws. */
    fun capture(
        event: AccessibilityEvent?,
        root: AccessibilityNodeInfo?,
        packageName: String,
    ): ScreenSnapshot {
        if (event == null && root == null) return ScreenSnapshot.EMPTY
        val nodes = ArrayList<NodeEvidence>(MAX_NODES)
        root?.let { walk(it, containerId = "", parentClickable = false, depth = 0, out = nodes) }
        return ScreenSnapshot(
            packageName = packageName,
            activityClassName = runCatching { event?.className?.toString() }.getOrNull().orEmpty(),
            eventType = eventTypeOf(event?.eventType),
            nodes = nodes,
        )
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        containerId: String,
        parentClickable: Boolean,
        depth: Int,
        out: MutableList<NodeEvidence>,
    ) {
        if (out.size >= MAX_NODES || depth > MAX_DEPTH) return
        val id = runCatching { node.viewIdResourceName }.getOrNull().orEmpty()
        val clickable = runCatching { node.isClickable }.getOrDefault(false)
        val evidence = NodeEvidence(
            className = runCatching { node.className?.toString() }.getOrNull().orEmpty(),
            viewId = id,
            text = runCatching { node.text?.toString() }.getOrNull().orEmpty(),
            contentDescription = runCatching { node.contentDescription?.toString() }
                .getOrNull().orEmpty(),
            clickable = clickable,
            editable = runCatching { node.isEditable }.getOrDefault(false),
            scrollable = runCatching { node.isScrollable }.getOrDefault(false),
            selected = runCatching { node.isSelected }.getOrDefault(false),
            childCount = runCatching { node.childCount }.getOrDefault(0),
            depth = depth,
            containerId = containerId,
            hasClickableAncestor = parentClickable,
        )
        out += evidence

        val nextContainer = if (id.isNotBlank()) id else containerId
        val nextParentClickable = parentClickable || clickable
        val childCount = evidence.childCount
        if (childCount <= 0) return
        for (i in 0 until childCount) {
            if (out.size >= MAX_NODES) return
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                walk(child, nextContainer, nextParentClickable, depth + 1, out)
            } catch (_: Throwable) {
                // A stale child during a UI mutation is normal; skip it.
            }
        }
    }

    private fun eventTypeOf(raw: Int?): GuardEventType = when (raw) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> GuardEventType.WINDOW_STATE_CHANGED
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> GuardEventType.WINDOW_CONTENT_CHANGED
        AccessibilityEvent.TYPE_VIEW_CLICKED -> GuardEventType.VIEW_CLICKED
        else -> GuardEventType.OTHER
    }
}
