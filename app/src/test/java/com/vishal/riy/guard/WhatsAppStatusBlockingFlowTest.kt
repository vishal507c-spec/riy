package com.vishal.riy.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WHOLE WhatsApp Status journey, replayed event by event through the real
 * engine and the real rule.
 *
 * This is the closest thing to an on-device test that a pure JVM suite can make:
 * it feeds the exact accessibility event stream each user action produces and
 * asserts that the guard's action is right at every single step.
 */
class WhatsAppStatusBlockingFlowTest {

    private val engine = UiGuardEngine()
    private var now = 0L

    /** One step of the replay: feed a screen, get back the action taken. */
    private fun GuardSession.replay(screen: ScreenSnapshot): GuardStep {
        val verdict = engine.evaluate(screen) { true }
        val result = engine.step(state, verdict, now)
        state = result.state
        now += 50L
        return result.step
    }

    private class GuardSession(var state: GuardState = GuardState())

    // ================================================================== happy paths

    @Test
    fun `ordinary WhatsApp use never navigates the user away`() {
        val session = GuardSession()
        val steps = listOf(
            GuardFixtures.chatListScreen(),
            GuardFixtures.privateChatScreen(),
            GuardFixtures.groupChatScreen(),
            GuardFixtures.callsScreen(),
            GuardFixtures.settingsScreen(),
            GuardFixtures.searchScreen(),
        ).map { session.replay(it) }

        steps.forEachIndexed { index, step ->
            // IDLE, or ARMED on a screen that merely OFFERS the Status tab —
            // both leave the user exactly where they were. Only BACK_NOW would
            // move them, and it never happens on a normal screen.
            assertTrue("normal screen $index must not navigate: $step", step != GuardStep.BACK_NOW)
        }
    }

    @Test
    fun `opening a chat, sending and receiving stays untouched`() {
        val session = GuardSession()

        session.replay(GuardFixtures.chatListScreen())
        assertEquals(GuardStep.IDLE, session.replay(GuardFixtures.privateChatScreen()))
        // Scrolling the message list (many content-changed events).
        repeat(20) {
            assertEquals(GuardStep.IDLE, session.replay(GuardFixtures.privateChatScreen()))
        }
        // Opening media from the chat.
        assertEquals(
            GuardStep.IDLE,
            session.replay(
                GuardFixtures.privateChatScreen().copy(
                    nodes = GuardFixtures.privateChatScreen().nodes + GuardFixtures.node(
                        viewId = "com.whatsapp:id/media_viewer",
                        className = "android.view.SurfaceView",
                        containerId = "com.whatsapp:id/message_bubble",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `tapping Status arms, the destination opens, and the user is put back`() {
        val session = GuardSession()

        // On the chat list the tap is seen as an entry point only.
        assertEquals(GuardStep.ARMED, session.replay(GuardFixtures.chatListScreen()))
        // The navigation lands on the Status tab.
        assertEquals(GuardStep.BACK_NOW, session.replay(GuardFixtures.statusListEmbeddedScreen()))
        // The user is back on the chat list, which is fully usable again.
        assertEquals(GuardStep.ARMED, session.replay(GuardFixtures.chatListScreen()))
    }

    @Test
    fun `repeated Status attempts are every single time blocked`() {
        repeat(8) { attempt ->
            val session = GuardSession()
            session.replay(GuardFixtures.chatListScreen())
            assertEquals("attempt $attempt", GuardStep.BACK_NOW, session.replay(GuardFixtures.statusViewerScreen()))
            assertEquals(GuardStep.ARMED, session.replay(GuardFixtures.chatListScreen()))
        }
    }

    @Test
    fun `swiping through a Status is blocked at every step`() {
        val session = GuardSession()
        session.replay(GuardFixtures.chatListScreen())
        assertEquals(GuardStep.BACK_NOW, session.replay(GuardFixtures.statusViewerScreen()))

        // A swipe produces fresh content-changed events on the viewer. Every one
        // of them is blocked, so the user never advances to a second item — until
        // the bounded budget for this single attempt runs out, at which point the
        // guard stops instead of walking further back.
        val budget = UiGuardEngine.MAX_CONSECUTIVE_BACKS
        var backs = 1 // the entry above already spent one
        repeat(10) { swipe ->
            val step = session.replay(GuardFixtures.statusViewerScreen())
            if (step == GuardStep.BACK_NOW) backs++
            assertTrue(
                "the back-out budget must never exceed $budget (swipe $swipe spent $backs)",
                backs <= budget,
            )
        }
        assertEquals("the full budget should have been spent", budget, backs)

        // Coming back to a normal screen restores the guard completely.
        assertEquals(GuardStep.ARMED, session.replay(GuardFixtures.chatListScreen()))
        assertEquals(GuardStep.BACK_NOW, session.replay(GuardFixtures.statusViewerScreen()))
    }

    @Test
    fun `a Status opened from a notification is blocked without any tap`() {
        val session = GuardSession()

        assertEquals(GuardStep.BACK_NOW, session.replay(GuardFixtures.statusListActivityScreen()))
    }

    @Test
    fun `WhatsApp is never force-closed by the guard`() {
        val session = GuardSession()
        session.replay(GuardFixtures.chatListScreen())

        // Even if the platform keeps insisting on the Status screen, the guard
        // stops after its bounded number of back-outs rather than walking out
        // of WhatsApp entirely.
        var backs = 0
        repeat(30) {
            if (session.replay(GuardFixtures.statusViewerScreen()) == GuardStep.BACK_NOW) backs++
        }

        assertEquals(UiGuardEngine.MAX_CONSECUTIVE_BACKS, backs)
    }

    // ================================================================== safety

    @Test
    fun `a chat contact named Status is never navigated away from`() {
        val session = GuardSession()
        val contact = GuardFixtures.snapshot(
            activity = "com.whatsapp.MainActivity",
            nodes = listOf(
                GuardFixtures.node(
                    viewId = "com.whatsapp:id/contact_row",
                    text = "Status",
                    clickable = true,
                    containerId = "com.whatsapp:id/conversation_list",
                ),
                GuardFixtures.node(
                    viewId = "com.whatsapp:id/message_text",
                    text = "Are you free tonight?",
                    containerId = "com.whatsapp:id/message_bubble",
                ),
                GuardFixtures.node(
                    viewId = "com.whatsapp:id/text_entry",
                    className = "android.widget.EditText",
                    editable = true,
                ),
            ),
        )

        repeat(10) { assertEquals(GuardStep.IDLE, session.replay(contact)) }
    }

    @Test
    fun `a group chat discussing status updates is never navigated away from`() {
        val session = GuardSession()

        repeat(10) {
            assertEquals(GuardStep.IDLE, session.replay(GuardFixtures.groupChatScreen()))
        }
    }

    @Test
    fun `another app sharing the same view ids is never touched`() {
        val session = GuardSession()

        repeat(5) {
            assertEquals(
                GuardStep.IDLE,
                session.replay(
                    GuardFixtures.statusListActivityScreen().copy(packageName = "com.instagram.android"),
                ),
            )
        }
    }

    @Test
    fun `a disabled toggle blocks nothing at all`() {
        val session = GuardSession()
        val disabled = { _: String -> false }

        val verdict = engine.evaluate(GuardFixtures.statusViewerScreen(), disabled)
        assertEquals(GuardVerdict.ALLOW, verdict)
        assertEquals(GuardStep.IDLE, engine.step(GuardState(), verdict, 0L).step)
    }

    @Test
    fun `the user always ends up on a normal WhatsApp screen, never outside it`() {
        val session = GuardSession()
        val journey = listOf(
            GuardFixtures.chatListScreen(),
            GuardFixtures.statusListMainPackageScreen(),
            GuardFixtures.chatListScreen(),
            GuardFixtures.privateChatScreen(),
            GuardFixtures.statusViewerScreen(),
            GuardFixtures.chatListScreen(),
            GuardFixtures.groupChatScreen(),
        )

        // Whatever happens, an allowed screen is never followed by a back-out.
        journey.zipWithNext().forEach { (previous, next) ->
            if (engine.evaluate(next) == GuardVerdict.ALLOW) {
                val step = session.replay(next)
                assertTrue(
                    "an allowed screen must never be navigated away from " +
                        "(after ${engine.evaluate(previous).destination})",
                    step == GuardStep.IDLE || step == GuardStep.ARMED,
                )
            }
        }
        assertFalse(session.state.armed && session.state.consecutiveBacks == 0)
    }
}
