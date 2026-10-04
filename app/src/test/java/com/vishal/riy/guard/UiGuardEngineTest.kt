package com.vishal.riy.guard

import com.vishal.riy.guard.rules.WhatsAppStatusRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GENERIC half of the guard: rule selection and the action policy.
 *
 * These tests hold for every target, not just WhatsApp, which is what keeps the
 * engine a single shared blocking system instead of a second blocker per app.
 */
class UiGuardEngineTest {

    private val engine = UiGuardEngine()

    // ================================================================ selection

    @Test
    fun `no rule recognises an unknown package so the verdict is ALLOW`() {
        val screen = GuardFixtures.snapshot(packageName = "com.example.other")

        assertEquals(GuardVerdict.ALLOW, engine.evaluate(screen))
    }

    @Test
    fun `the strongest verdict across rules wins`() {
        val engine = UiGuardEngine(
            listOf(
                AlwaysRule("weak", GuardVerdict("weak", GuardConfidence.CANDIDATE)),
                AlwaysRule("strong", GuardVerdict("strong", GuardConfidence.CONFIRMED)),
                AlwaysRule("middling", GuardVerdict("middling", GuardConfidence.NONE)),
            ),
        )

        assertEquals("strong", engine.evaluate(GuardFixtures.chatListScreen()).targetId)
    }

    @Test
    fun `a disabled target is skipped entirely`() {
        val disabled = { target: String -> false }

        assertEquals(
            GuardVerdict.ALLOW,
            engine.evaluate(GuardFixtures.statusViewerScreen(), disabled),
        )
    }

    @Test
    fun `a rule that throws cannot break protection`() {
        val engine = UiGuardEngine(
            listOf(
                ExplodingRule(),
                AlwaysRule("backup", GuardVerdict("backup", GuardConfidence.CONFIRMED)),
            ),
        )

        assertEquals("backup", engine.evaluate(GuardFixtures.chatListScreen()).targetId)
    }

    @Test
    fun `the shipped rule set contains exactly the WhatsApp Status target`() {
        assertEquals(
            listOf(WhatsAppStatusRule.TARGET_ID),
            UiGuardEngine.DEFAULT_RULES.map { it.targetId },
        )
    }

    // ============================================================ action policy

    @Test
    fun `a normal screen is completely idle`() {
        val verdict = engine.evaluate(GuardFixtures.privateChatScreen())

        val result = engine.step(GuardState(), verdict, nowMs = 1_000L)

        assertEquals(GuardStep.IDLE, result.step)
        assertFalse(result.shouldNotify)
        assertFalse(result.state.armed)
    }

    @Test
    fun `a low confidence candidate never produces an action`() {
        val verdict = GuardVerdict(
            targetId = WhatsAppStatusRule.TARGET_ID,
            confidence = GuardConfidence.CANDIDATE,
            destination = GuardDestination.VIEWER,
        )

        val result = engine.step(GuardState(), verdict, nowMs = 1_000L)

        assertEquals(GuardStep.IDLE, result.step)
        assertFalse(result.shouldNotify)
    }

    @Test
    fun `tapping the entry only arms and never navigates away`() {
        val verdict = engine.evaluate(GuardFixtures.chatListScreen())
        assertEquals(GuardDestination.ENTRY_POINT, verdict.destination)

        val result = engine.step(GuardState(), verdict, nowMs = 1_000L)

        // The user stays exactly where they were: on the chat list.
        assertEquals(GuardStep.ARMED, result.step)
        assertFalse(result.shouldNotify)
        assertTrue(result.state.armed)
    }

    @Test
    fun `the destination opening after a tap is backed out immediately`() {
        val armed = engine.step(GuardState(), engine.evaluate(GuardFixtures.chatListScreen()), 1_000L)

        val landed = engine.evaluate(GuardFixtures.statusListEmbeddedScreen())
        val result = engine.step(armed.state, landed, nowMs = 1_150L)

        assertEquals(GuardStep.BACK_NOW, result.step)
        assertTrue("the user should be told, briefly", result.shouldNotify)
        assertFalse(result.state.armed)
    }

    @Test
    fun `an already open Status screen is backed out without any tap`() {
        val verdict = engine.evaluate(GuardFixtures.statusViewerScreen())

        val result = engine.step(GuardState(), verdict, nowMs = 5_000L)

        assertEquals(GuardStep.BACK_NOW, result.step)
        assertTrue(result.shouldNotify)
    }

    @Test
    fun `a stale arming releases itself instead of firing later`() {
        val armed = engine.step(GuardState(), engine.evaluate(GuardFixtures.chatListScreen()), 1_000L)
        assertTrue(armed.state.armed)

        // The navigation never happened; the next screen is an ordinary one.
        val result = engine.step(
            armed.state,
            engine.evaluate(GuardFixtures.privateChatScreen()),
            nowMs = 1_000L + UiGuardEngine.ENTRY_WINDOW_MS + 1,
        )

        assertEquals(GuardStep.IDLE, result.step)
        assertFalse(result.state.armed)
    }

    @Test
    fun `a stuck Status screen cannot be backed out forever`() {
        val verdict = engine.evaluate(GuardFixtures.statusViewerScreen())
        var state = GuardState()
        var backs = 0

        repeat(UiGuardEngine.MAX_CONSECUTIVE_BACKS + 4) {
            val result = engine.step(state, verdict, nowMs = 1_000L + it * 100L)
            state = result.state
            if (result.step == GuardStep.BACK_NOW) backs++
        }

        assertEquals(
            "back-outs must be capped so WhatsApp is never force-closed by them",
            UiGuardEngine.MAX_CONSECUTIVE_BACKS,
            backs,
        )
    }

    @Test
    fun `a fresh attempt earns a fresh back-out budget`() {
        var state = engine.step(
            GuardState(),
            engine.evaluate(GuardFixtures.statusViewerScreen()),
            1_000L,
        ).state
        repeat(UiGuardEngine.MAX_CONSECUTIVE_BACKS) {
            state = engine.step(state, engine.evaluate(GuardFixtures.statusViewerScreen()), 1_000L).state
        }
        // The budget is now spent.
        assertEquals(
            GuardStep.IDLE,
            engine.step(state, engine.evaluate(GuardFixtures.statusViewerScreen()), 9_000L).step,
        )

        // A new tap on the entry resets it.
        val rearmed = engine.step(state, engine.evaluate(GuardFixtures.chatListScreen()), 10_000L)
        assertEquals(GuardStep.ARMED, rearmed.step)
        assertEquals(0, rearmed.state.consecutiveBacks)

        val again = engine.step(
            rearmed.state,
            engine.evaluate(GuardFixtures.statusViewerScreen()),
            10_100L,
        )
        assertEquals(GuardStep.BACK_NOW, again.step)
    }

    @Test
    fun `returning to a normal screen resets the engine state`() {
        val blocked = engine.step(
            GuardState(),
            engine.evaluate(GuardFixtures.statusViewerScreen()),
            1_000L,
        )
        assertEquals(1, blocked.state.consecutiveBacks)

        // Back on the chat list: the user is never navigated away from, and the
        // back-out budget for the previous attempt is released.
        val back = engine.step(blocked.state, engine.evaluate(GuardFixtures.chatListScreen()), 1_100L)

        assertEquals(GuardStep.ARMED, back.step)
        assertEquals(0, back.state.consecutiveBacks)
        assertFalse("an entry point is never a reason to notify", back.shouldNotify)
    }

    @Test
    fun `repeated status attempts are all blocked`() {
        // Five consecutive user attempts, each one a full tap -> open -> exit.
        repeat(5) { attempt ->
            val entry = engine.evaluate(GuardFixtures.chatListScreen())
            val armed = engine.step(GuardState(), entry, nowMs = attempt * 10_000L)
            val result = engine.step(
                armed.state,
                engine.evaluate(GuardFixtures.statusViewerScreen()),
                nowMs = attempt * 10_000L + 200L,
            )
            assertEquals("attempt $attempt must be blocked", GuardStep.BACK_NOW, result.step)
        }
    }

    // ================================================================ fixtures

    private class AlwaysRule(
        override val targetId: String,
        private val verdict: GuardVerdict,
    ) : UiGuardRule {
        override val packages = setOf(GuardFixtures.WA_PACKAGE)
        override fun evaluate(snapshot: ScreenSnapshot) = verdict
    }

    private class ExplodingRule : UiGuardRule {
        override val targetId = "exploding"
        override val packages = setOf(GuardFixtures.WA_PACKAGE)
        override fun evaluate(snapshot: ScreenSnapshot): GuardVerdict = error("boom")
    }
}
