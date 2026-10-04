package com.vishal.riy.guard

import com.vishal.riy.guard.rules.WhatsAppStatusRule

/**
 * Builders for realistic, hand-written accessibility trees.
 *
 * Every screen a real WhatsApp build can present for the Status flow has one,
 * plus one tree for each screen that MUST keep working. Using builders keeps the
 * tests readable and makes an accidental regression obvious.
 */
object GuardFixtures {

    const val WA_PACKAGE = "com.whatsapp"

    fun node(
        viewId: String = "",
        text: String = "",
        contentDescription: String = "",
        className: String = "android.widget.TextView",
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        selected: Boolean = false,
        containerId: String = "",
        hasClickableAncestor: Boolean = false,
        childCount: Int = 0,
        depth: Int = 0,
    ) = NodeEvidence(
        className = className,
        viewId = viewId,
        text = text,
        contentDescription = contentDescription,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        selected = selected,
        childCount = childCount,
        depth = depth,
        containerId = containerId,
        hasClickableAncestor = hasClickableAncestor,
    )

    fun snapshot(
        activity: String = "com.whatsapp.MainActivity",
        packageName: String = WA_PACKAGE,
        nodes: List<NodeEvidence> = emptyList(),
        event: GuardEventType = GuardEventType.WINDOW_STATE_CHANGED,
    ) = ScreenSnapshot(
        packageName = packageName,
        activityClassName = activity,
        eventType = event,
        nodes = nodes,
    )

    // ------------------------------------------------------- normal screens

    /** The WhatsApp chat list, including the tappable Status row and tab. */
    fun chatListScreen(): ScreenSnapshot = snapshot(
        nodes = listOf(
            node(viewId = "com.whatsapp:id/fragment_container", childCount = 3, depth = 0),
            node(
                viewId = "com.whatsapp:id/status_row",
                text = "Status",
                clickable = true,
                containerId = "com.whatsapp:id/conversation_list",
                depth = 1,
            ),
            node(
                viewId = "com.whatsapp:id/conversation_row",
                text = "Alice",
                clickable = true,
                containerId = "com.whatsapp:id/conversation_list",
                depth = 1,
            ),
            node(
                viewId = "com.whatsapp:id/message_text",
                text = "Hi, are we still on for tomorrow?",
                containerId = "com.whatsapp:id/conversation_row",
                depth = 2,
            ),
            // Bottom navigation: Chats / Status / Calls.
            node(viewId = "com.whatsapp:id/navigation", childCount = 3, depth = 0),
            node(
                viewId = "com.whatsapp:id/tab_item",
                text = "Chats",
                clickable = true,
                containerId = "com.whatsapp:id/navigation",
                depth = 1,
            ),
            node(
                viewId = "com.whatsapp:id/tab_item",
                text = "Status",
                clickable = true,
                containerId = "com.whatsapp:id/navigation",
                depth = 1,
            ),
            node(
                viewId = "com.whatsapp:id/tab_item",
                text = "Calls",
                clickable = true,
                containerId = "com.whatsapp:id/navigation",
                depth = 1,
            ),
        ),
    )

    /** A one-to-one chat, with a composer and a scrollable message list. */
    fun privateChatScreen(): ScreenSnapshot = snapshot(
        activity = "com.whatsapp.chat.ChatActivity",
        nodes = listOf(
            node(viewId = "com.whatsapp:id/conversation_header", text = "Alice", depth = 0),
            node(
                viewId = "com.whatsapp:id/message_list",
                scrollable = true,
                childCount = 12,
                depth = 0,
            ),
            node(
                viewId = "com.whatsapp:id/message_text",
                text = "Here is the photo",
                containerId = "com.whatsapp:id/message_bubble",
                depth = 2,
            ),
            node(
                viewId = "com.whatsapp:id/message_thumbnail",
                className = "android.widget.ImageView",
                containerId = "com.whatsapp:id/message_bubble",
                depth = 2,
            ),
            node(
                viewId = "com.whatsapp:id/text_entry",
                text = "Message",
                className = "android.widget.EditText",
                editable = true,
                depth = 0,
            ),
        ),
    )

    /** A group conversation. */
    fun groupChatScreen(): ScreenSnapshot = snapshot(
        activity = "com.whatsapp.chat.ChatActivity",
        nodes = listOf(
            node(viewId = "com.whatsapp:id/conversation_header", text = "Family Group", depth = 0),
            node(
                viewId = "com.whatsapp:id/conversation",
                scrollable = true,
                childCount = 40,
                depth = 0,
            ),
            node(
                viewId = "com.whatsapp:id/message_text",
                text = "Status update for everyone",
                containerId = "com.whatsapp:id/message_bubble",
                depth = 2,
            ),
            node(
                viewId = "com.whatsapp:id/text_entry",
                text = "Message",
                className = "android.widget.EditText",
                editable = true,
                depth = 0,
            ),
        ),
    )

    /** The Calls tab. */
    fun callsScreen(): ScreenSnapshot = snapshot(
        nodes = listOf(
            node(viewId = "com.whatsapp:id/call_log", scrollable = true, depth = 0),
            node(
                viewId = "com.whatsapp:id/tab_item",
                text = "Calls",
                clickable = true,
                containerId = "com.whatsapp:id/navigation",
                depth = 1,
            ),
            node(
                viewId = "com.whatsapp:id/tab_item",
                text = "Status",
                clickable = true,
                containerId = "com.whatsapp:id/navigation",
                depth = 1,
            ),
        ),
    )

    /** WhatsApp Settings. */
    fun settingsScreen(): ScreenSnapshot = snapshot(
        activity = "com.whatsapp.settings.SettingsActivity",
        nodes = listOf(
            node(viewId = "com.whatsapp:id/preference_screen", depth = 0),
            node(
                viewId = "com.whatsapp:id/preference_title",
                text = "Chats",
                containerId = "com.whatsapp:id/preference_category",
                depth = 1,
            ),
            node(
                viewId = "com.whatsapp:id/preference_title",
                text = "Notifications",
                containerId = "com.whatsapp:id/preference_category",
                depth = 1,
            ),
            node(
                viewId = "com.whatsapp:id/preference_title",
                text = "Storage",
                containerId = "com.whatsapp:id/preference_category",
                depth = 1,
            ),
        ),
    )

    /** Search results inside WhatsApp. */
    fun searchScreen(): ScreenSnapshot = snapshot(
        nodes = listOf(
            node(
                viewId = "com.whatsapp:id/search_entry",
                text = "status",
                className = "android.widget.EditText",
                editable = true,
                depth = 0,
            ),
            node(
                viewId = "com.whatsapp:id/search_result_title",
                text = "status update",
                containerId = "com.whatsapp:id/conversation_row",
                depth = 1,
            ),
        ),
    )

    // --------------------------------------------------------- blocked screens

    /**
     * The Status tab as a separate activity (older and mid-range builds). The
     * component package alone is a definitive indicator.
     */
    fun statusListActivityScreen(): ScreenSnapshot = snapshot(
        activity = "com.whatsapp.status.StatusActivity",
        nodes = listOf(
            node(viewId = "com.whatsapp.status:id/status_title", text = "Status", depth = 0),
            node(viewId = "com.whatsapp.status:id/recent_updates", text = "Recent updates", depth = 0),
            node(viewId = "com.whatsapp.status:id/my_status", text = "My status", depth = 0),
            node(viewId = "com.whatsapp.status:id/add_status", text = "Add status", depth = 0),
        ),
    )

    /**
     * The Status list embedded in the main activity (modern builds): no separate
     * activity class, so only the resource-id component and the content labels
     * can prove it.
     */
    fun statusListEmbeddedScreen(): ScreenSnapshot = snapshot(
        activity = "com.whatsapp.MainActivity",
        nodes = listOf(
            node(viewId = "com.whatsapp.status:id/status_screen", depth = 0),
            node(viewId = "com.whatsapp.status:id/section_header", text = "Recent updates", depth = 1),
            node(viewId = "com.whatsapp.status:id/my_status_row", text = "My status", clickable = true, depth = 1),
            node(viewId = "com.whatsapp.status:id/add_status_button", text = "Add status", clickable = true, depth = 1),
        ),
    )

    /** Full-screen Status playback. */
    fun statusViewerScreen(): ScreenSnapshot = snapshot(
        activity = "com.whatsapp.status.views.StatusPreviewActivity",
        nodes = listOf(
            node(viewId = "com.whatsapp.status:id/status_progress", childCount = 3, depth = 0),
            node(viewId = "com.whatsapp.status:id/sender_name", text = "Bob", depth = 0),
            node(viewId = "com.whatsapp.status:id/reply_bar", text = "Reply", clickable = true, depth = 0),
            node(viewId = "com.whatsapp.status:id/status_video", className = "android.view.SurfaceView", depth = 0),
        ),
    )

    /** The viewer as rendered by a build that keeps everything in MainActivity. */
    fun statusViewerEmbeddedScreen(): ScreenSnapshot = snapshot(
        activity = "com.whatsapp.MainActivity",
        nodes = listOf(
            node(viewId = "com.whatsapp.status:id/status_viewer_root", depth = 0),
            node(viewId = "com.whatsapp.status:id/progress_segments", childCount = 2, depth = 1),
            node(viewId = "com.whatsapp.status:id/message_action_bar", text = "Message", clickable = true, depth = 1),
        ),
    )

    /**
     * A modern build that renders Status inside the main activity and mints its
     * resource ids in the MAIN package — so neither the activity component nor
     * the status R class is available. Only the selected navigation tab and the
     * Status ids prove it.
     */
    fun statusListMainPackageScreen(): ScreenSnapshot = snapshot(
        activity = "com.whatsapp.MainActivity",
        nodes = listOf(
            node(viewId = "com.whatsapp:id/status_screen", childCount = 2, depth = 0),
            node(viewId = "com.whatsapp:id/status_section_header", text = "Recent updates", depth = 1),
            node(viewId = "com.whatsapp:id/status_row", text = "My status", clickable = true, depth = 1),
            node(viewId = "com.whatsapp:id/add_status_button", text = "Add status", clickable = true, depth = 1),
            node(viewId = "com.whatsapp:id/navigation", childCount = 3, depth = 0),
            node(
                viewId = "com.whatsapp:id/tab_item",
                text = "Chats",
                clickable = true,
                containerId = "com.whatsapp:id/navigation",
                depth = 1,
            ),
            node(
                viewId = "com.whatsapp:id/tab_item",
                text = "Status",
                clickable = true,
                selected = true,
                containerId = "com.whatsapp:id/navigation",
                depth = 1,
            ),
            node(
                viewId = "com.whatsapp:id/tab_item",
                text = "Calls",
                clickable = true,
                containerId = "com.whatsapp:id/navigation",
                depth = 1,
            ),
        ),
    )

    /** A normal chat list in a non-Latin locale. Must never be blocked. */
    fun chatListNonLatinScreen(): ScreenSnapshot = snapshot(
        activity = "com.whatsapp.MainActivity",
        nodes = listOf(
            node(
                viewId = "com.whatsapp:id/contact_row",
                contentDescription = "\u0938\u094d\u091f\u093e\u091f\u0938",
                clickable = true,
                containerId = "com.whatsapp:id/conversation_list",
                depth = 1,
            ),
            node(viewId = "com.whatsapp:id/navigation", childCount = 3, depth = 0),
            node(
                viewId = "com.whatsapp:id/tab_item",
                contentDescription = "\u091a\u0947\u091f",
                clickable = true,
                containerId = "com.whatsapp:id/navigation",
                depth = 1,
            ),
            node(
                viewId = "com.whatsapp:id/tab_item",
                contentDescription = "\u0938\u094d\u091f\u093e\u091f\u0938",
                clickable = true,
                containerId = "com.whatsapp:id/navigation",
                depth = 1,
            ),
        ),
    )
}
