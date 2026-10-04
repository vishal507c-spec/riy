package com.vishal.riy.guard

import com.vishal.riy.guard.rules.WhatsAppStatusRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SAFETY CONTRACT of the WhatsApp Status blocker.
 *
 * Two halves, and both matter equally:
 *
 *  A. NOTHING ELSE IS EVER BLOCKED — chats, individual and group messages,
 *     calls, notifications, in-chat images/videos, search, settings and the
 *     chat list all have to come out as ALLOW or at worst CANDIDATE.
 *
 *  B. THE STATUS CONTENT IS ACTUALLY BLOCKED — the Status tab (as a separate
 *     activity, and embedded in the main activity) and full-screen playback.
 *
 * Plus the resilience half: no verdict may depend on a screen coordinate, and a
 * look-alike screen must never reach CONFIRMED.
 */
class WhatsAppStatusRuleTest {

    private val rule = WhatsAppStatusRule

    // ============================================ A. normal WhatsApp is untouched

    @Test
    fun `the chat list is allowed and only recognised as the entry point`() {
        val verdict = rule.evaluate(GuardFixtures.chatListScreen())

        // Confirmed-as-entry is fine: the engine only ARMS on ENTRY_POINT and
        // never backs out of it, so the chat list itself stays usable.
        assertEquals(GuardDestination.ENTRY_POINT, verdict.destination)
        assertFalse(
            "a normal WhatsApp screen must never be navigated away from",
            verdict.isConfirmed && verdict.destination != GuardDestination.ENTRY_POINT,
        )
    }

    @Test
    fun `a one to one chat with a composer and media is never blocked`() {
        val verdict = rule.evaluate(GuardFixtures.privateChatScreen())

        assertEquals(GuardVerdict.ALLOW, verdict)
    }

    @Test
    fun `a group conversation is never blocked`() {
        val verdict = rule.evaluate(GuardFixtures.groupChatScreen())

        assertEquals(GuardVerdict.ALLOW, verdict)
    }

    @Test
    fun `the calls tab is never blocked`() {
        val verdict = rule.evaluate(GuardFixtures.callsScreen())

        // At most the shared Status tab label, which is an entry point.
        assertTrue(
            verdict.destination == GuardDestination.UNKNOWN ||
                verdict.destination == GuardDestination.ENTRY_POINT,
        )
    }

    @Test
    fun `WhatsApp settings are never blocked`() {
        val verdict = rule.evaluate(GuardFixtures.settingsScreen())

        assertEquals(GuardVerdict.ALLOW, verdict)
    }

    @Test
    fun `WhatsApp search results are never blocked`() {
        val verdict = rule.evaluate(GuardFixtures.searchScreen())

        assertEquals(GuardVerdict.ALLOW, verdict)
    }

    @Test
    fun `message text that merely contains the word status is never blocked`() {
        val screen = GuardFixtures.snapshot(
            nodes = listOf(
                GuardFixtures.node(
                    viewId = "com.whatsapp:id/message_text",
                    text = "Look at my status update",
                    containerId = "com.whatsapp:id/message_bubble",
                ),
            ),
        )

        assertEquals(GuardVerdict.ALLOW, rule.evaluate(screen))
    }

    @Test
    fun `an empty or unreadable window does nothing`() {
        assertEquals(GuardVerdict.ALLOW, rule.evaluate(ScreenSnapshot.EMPTY))
        assertEquals(
            GuardVerdict.ALLOW,
            rule.evaluate(GuardFixtures.snapshot(nodes = emptyList())),
        )
    }

    @Test
    fun `another app is never inspected at all`() {
        val screen = GuardFixtures.snapshot(
            activity = "com.whatsapp.status.StatusActivity",
            packageName = "com.instagram.android",
            nodes = listOf(
                GuardFixtures.node(viewId = "com.whatsapp.status:id/status_title", text = "Status"),
            ),
        )

        assertEquals(GuardVerdict.ALLOW, rule.evaluate(screen))
    }

    // ==================================================== B. Status is blocked

    @Test
    fun `the Status activity is confirmed and blocked`() {
        val verdict = rule.evaluate(GuardFixtures.statusListActivityScreen())

        assertTrue(verdict.isConfirmed)
        assertEquals(WhatsAppStatusRule.TARGET_ID, verdict.targetId)
        assertTrue(verdict.signals.contains(WhatsAppStatusRule.SIGNAL_STATUS_ACTIVITY))
    }

    @Test
    fun `the Status tab embedded in the main activity is confirmed via resource ids`() {
        val verdict = rule.evaluate(GuardFixtures.statusListEmbeddedScreen())

        assertTrue(verdict.isConfirmed)
        assertTrue(verdict.signals.contains(WhatsAppStatusRule.SIGNAL_STATUS_RESOURCE_ID))
        assertEquals(GuardDestination.LIST, verdict.destination)
    }

    @Test
    fun `status playback is confirmed and reported as a viewer`() {
        val verdict = rule.evaluate(GuardFixtures.statusViewerScreen())

        assertTrue(verdict.isConfirmed)
        assertEquals(GuardDestination.VIEWER, verdict.destination)
    }

    @Test
    fun `playback embedded in the main activity is confirmed by its resource ids alone`() {
        val verdict = rule.evaluate(GuardFixtures.statusViewerEmbeddedScreen())

        assertTrue("resource ids alone must be enough", verdict.isConfirmed)
    }

    @Test
    fun `a modern main-activity build with main-package ids is still blocked`() {
        val verdict = rule.evaluate(GuardFixtures.statusListMainPackageScreen())

        assertTrue(verdict.isConfirmed)
        assertTrue(verdict.signals.contains(WhatsAppStatusRule.SIGNAL_STATUS_SELECTED_TAB))
        assertEquals(GuardDestination.LIST, verdict.destination)
    }

    @Test
    fun `a non-Latin chat list is never blocked`() {
        assertEquals(GuardVerdict.ALLOW, rule.evaluate(GuardFixtures.chatListNonLatinScreen()))
    }

    @Test
    fun `a DIFFERENT selected nav tab is never backed out of`() {
        val calls = GuardFixtures.chatListScreen().copy(
            nodes = GuardFixtures.chatListScreen().nodes.map { node ->
                node.copy(selected = node.label == "Calls")
            },
        )

        val verdict = rule.evaluate(calls)

        assertEquals(GuardDestination.ENTRY_POINT, verdict.destination)
    }

    @Test
    fun `swiping to the next status keeps being blocked`() {
        val viewer = GuardFixtures.statusViewerScreen()

        // Every event while the viewer is on screen is independently confirmed,
        // so a swipe (a content-changed event) can never slip past.
        val events = listOf(
            GuardEventType.WINDOW_CONTENT_CHANGED,
            GuardEventType.WINDOW_CONTENT_CHANGED,
            GuardEventType.WINDOW_STATE_CHANGED,
        )
        events.forEachIndexed { index, event ->
            val verdict = rule.evaluate(viewer.copy(eventType = event))
            assertTrue("event $index must stay confirmed", verdict.isConfirmed)
            assertEquals(GuardDestination.VIEWER, verdict.destination)
        }
    }

    // ============================================ C. look-alikes stay candidates

    @Test
    fun `a chat contact named Status is only a candidate and never confirmed`() {
        val screen = GuardFixtures.snapshot(
            activity = "com.whatsapp.MainActivity",
            nodes = listOf(
                GuardFixtures.node(
                    viewId = "com.whatsapp:id/contact_row",
                    text = "Status",
                    clickable = true,
                    containerId = "com.whatsapp:id/conversation_list",
                ),
            ),
        )

        val verdict = rule.evaluate(screen)

        assertFalse("a contact named Status must not be confirmed", verdict.isConfirmed)
        assertEquals(GuardVerdict.ALLOW, verdict)
    }

    @Test
    fun `a single status content label is not enough to confirm`() {
        val screen = GuardFixtures.snapshot(
            activity = "com.whatsapp.MainActivity",
            nodes = listOf(
                GuardFixtures.node(
                    viewId = "com.whatsapp:id/preference_title",
                    text = "My status",
                    containerId = "com.whatsapp:id/preference_category",
                ),
            ),
        )

        val verdict = rule.evaluate(screen)

        assertTrue("one weak signal must stay a candidate", verdict.isCandidate)
        assertFalse(verdict.isConfirmed)
    }

    @Test
    fun `a status label inside a conversation container is vetoed outright`() {
        val screen = GuardFixtures.snapshot(
            nodes = listOf(
                GuardFixtures.node(
                    viewId = "com.whatsapp:id/status_row",
                    text = "Status",
                    clickable = true,
                    containerId = "com.whatsapp:id/conversation_list",
                ),
                GuardFixtures.node(
                    viewId = "com.whatsapp:id/message_text",
                    text = "Updates",
                    containerId = "com.whatsapp:id/message_bubble",
                ),
            ),
        )

        // The conversation veto removes the only two possible indicators.
        assertEquals(GuardVerdict.ALLOW, rule.evaluate(screen))
    }

    @Test
    fun `a reply label with a composer present is not a viewer`() {
        val screen = GuardFixtures.snapshot(
            activity = "com.whatsapp.chat.ChatActivity",
            nodes = listOf(
                GuardFixtures.node(
                    viewId = "com.whatsapp:id/message_action_bar",
                    text = "Reply",
                    clickable = true,
                ),
                GuardFixtures.node(
                    viewId = "com.whatsapp:id/text_entry",
                    className = "android.widget.EditText",
                    editable = true,
                ),
            ),
        )

        assertEquals(GuardVerdict.ALLOW, rule.evaluate(screen))
    }

    // ============================================================ D. resilience

    @Test
    fun `no detection input carries a screen coordinate`() {
        // The evidence model has no x/y at all, which is the structural reason
        // detection cannot be tied to a fixed pixel like 500,100.
        val evidence = NodeEvidence::class.java.declaredFields.map { it.name }
        assertFalse(evidence.any { it.contains("x", true) && it.contains("coord", true) })
        assertFalse(evidence.contains("x"))
        assertFalse(evidence.contains("y"))
    }

    @Test
    fun `labels are matched case and whitespace insensitively`() {
        val screen = GuardFixtures.snapshot(
            activity = "com.whatsapp.status.StatusActivity",
            nodes = listOf(
                GuardFixtures.node(text = "  RECENT   UPDATES ", clickable = true),
                GuardFixtures.node(text = "my\nstatus", clickable = true),
            ),
        )

        assertTrue(rule.evaluate(screen).isConfirmed)
    }

    @Test
    fun `a label carried only as a content description still matches`() {
        val screen = GuardFixtures.snapshot(
            activity = "com.whatsapp.status.StatusActivity",
            nodes = listOf(
                GuardFixtures.node(contentDescription = "Recent updates"),
                GuardFixtures.node(contentDescription = "My status"),
            ),
        )

        assertTrue(rule.evaluate(screen).isConfirmed)
    }

    @Test
    fun `the rule never reports a package other than WhatsApp`() {
        assertEquals(setOf("com.whatsapp"), rule.packages)
    }

    @Test
    fun `rule signals are code constants and never carry screen text`() {
        val declared = listOf(
            WhatsAppStatusRule.SIGNAL_STATUS_ACTIVITY,
            WhatsAppStatusRule.SIGNAL_STATUS_RESOURCE_ID,
            WhatsAppStatusRule.SIGNAL_STATUS_CONTENT_LABEL,
            WhatsAppStatusRule.SIGNAL_ENTRY_LABEL,
            WhatsAppStatusRule.SIGNAL_ENTRY_ROW_ID,
            WhatsAppStatusRule.SIGNAL_ENTRY_TAB_BAR,
            WhatsAppStatusRule.SIGNAL_VIEWER_REPLY_BAR,
        )
        val verdict = rule.evaluate(GuardFixtures.statusViewerScreen())

        assertTrue(verdict.signals.isNotEmpty())
        verdict.signals.forEach { signal ->
            assertTrue("'$signal' must be a declared constant", signal in declared)
        }
        // The sender name on the viewer screen must never reach the log line.
        assertFalse(verdict.signals.any { it.contains("bob", ignoreCase = true) })
    }
}
