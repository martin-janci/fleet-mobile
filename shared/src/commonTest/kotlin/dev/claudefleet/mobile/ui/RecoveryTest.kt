package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.RepairReport
import dev.claudefleet.mobile.model.RestorePlanEntry
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recovery on the New bar (redesign 14.5, MobileRecovery): what each card says and offers. */
class RecoveryTest {

    private fun row(status: String?, activity: String? = null, lastPrompt: String? = null) =
        SessionRow(id = 1, tmuxName = "s", claudeStatus = status, currentActivity = activity, lastPrompt = lastPrompt)

    @Test
    fun only_a_failed_session_gets_the_card() {
        for (status in listOf("working", "idle", "blocked", "completed", null)) {
            assertNull(failedSession(row(status), emptyList()), "status $status")
        }
        assertNull(failedSession(null, emptyList()))
    }

    @Test
    fun the_card_names_the_failed_step_the_cause_and_the_prompt_to_retry() {
        val turns = listOf(
            ConvTurn(prompt = "older", items = listOf(ConvItem.Text("ok"))),
            ConvTurn(
                prompt = "Run the suite and fix what breaks.",
                items = listOf(ConvItem.Tool("Read package.json"), ConvItem.Tool("Run npm test", error = true), ConvItem.Text("…")),
            ),
        )
        val f = failedSession(row("failed", activity = "Claude's API was overloaded (529)"), turns)!!
        assertEquals("Run npm test", f.failedStep)
        assertEquals("Claude's API was overloaded (529)", f.cause)
        assertEquals("Run the suite and fix what breaks.", f.retryPrompt)
    }

    @Test
    fun without_words_from_the_hub_the_cause_is_still_a_sentence() {
        val f = failedSession(row("failed", activity = "  ", lastPrompt = "go on"), emptyList())!!
        assertEquals("Claude Code stopped with an error on the last turn.", f.cause)
        assertNull(f.failedStep)
        assertEquals("go on", f.retryPrompt, "the row's last prompt stands in for an unread conversation")
        assertNull(failedSession(row("failed"), emptyList())!!.retryPrompt, "nothing to send again: no Retry")
    }

    /** A dismissed failure stays away for that failure only: the next failed turn is a new key. */
    @Test
    fun each_failure_has_its_own_key() {
        val turns = listOf(ConvTurn(prompt = "go", at = "2026-10-16T10:00:00Z"))
        val first = failedSession(row("failed").copy(turnSeq = 4), turns)!!
        assertEquals(failedKey(row("failed").copy(turnSeq = 4), turns), first.key)
        val next = failedSession(row("failed").copy(turnSeq = 5), turns + ConvTurn(prompt = "again", at = "2026-10-16T10:05:00Z"))!!
        assertTrue(first.key != next.key)
    }

    @Test
    fun a_failure_changes_the_quick_replies() {
        assertEquals(listOf("Retry", "Show the error"), FAILED_QUICK_REPLIES)
    }

    @Test
    fun send_says_queue_while_claude_works() {
        assertEquals("Queue", sendLabel(working = true))
        assertEquals("Send", sendLabel(working = false))
    }

    @Test
    fun not_sent_says_why_after_the_words() {
        val n = NotSent("Run the full suite", Friendly("Mercury did not answer", "", isError = true))
        assertEquals("Not sent: mercury did not answer", notSentHeadline(n))
    }

    /** Review phone-systemic-2: the hub's `may_have_run` codes, and a transport failure after the request left. */
    @Test
    fun a_write_whose_outcome_is_unknown_may_have_gone_through() {
        for (code in listOf("E_SSH_TIMEOUT", "E_TIMEOUT", "E_AGENT_PROTOCOL", "E_IN_FLIGHT")) {
            assertTrue(mayHaveRun(HubError.Tool(code, "x")), code)
            assertEquals("May have gone through", friendlyWrite(HubError.Tool(code, "x")).title)
        }
        assertFalse(mayHaveRun(HubError.Tool("E_BUSY", "mid-turn")))
        assertEquals("The hub refused that", friendlyWrite(HubError.Tool("E_BUSY", "mid-turn")).title)
        // The answer was lost on the way back: the hub may have it.
        assertTrue(mayHaveRun(HubError.Transport(SocketTimeoutException())))
        // Never left the phone.
        assertFalse(mayHaveRun(HubError.Transport(UnknownHostException())))
        assertEquals("Cannot reach the hub", friendlyWrite(HubError.Transport(UnknownHostException())).title)
        assertFalse(mayHaveRun(IllegalStateException("local")))
        // A read keeps its plain words: friendly() is unchanged.
        assertEquals("The hub refused that", friendly(HubError.Tool("E_SSH_TIMEOUT", "x")).title)

        val kept = NotSent("deploy", friendlyWrite(HubError.Tool("E_TIMEOUT", "x")), "sp-1", mayHaveRun = true)
        assertEquals("May have gone through", notSentHeadline(kept))
    }

    @Test
    fun a_prompt_send_id_is_fresh_each_time() {
        val r = kotlin.random.Random(7)
        val a = newPromptSendId(1_000, r)
        val b = newPromptSendId(1_000, r)
        assertTrue(a.startsWith("sp-"))
        assertTrue(a != b)
    }

    @Test
    fun a_repair_lists_what_it_did_and_what_is_left() {
        val report = RepairReport(
            healthy = false,
            actions = listOf("Restarted the tmux pane", "Re-attached the conversation"),
            deferred = listOf("3 files are not committed"),
            warnings = listOf("tmux is older than 3.3"),
        )
        assertEquals(listOf("Restarted the tmux pane", "Re-attached the conversation"), repairDone(report))
        assertEquals(listOf("3 files are not committed", "tmux is older than 3.3"), repairLeft(report))
        assertEquals(listOf("Nothing needed repairing"), repairDone(RepairReport(healthy = true)))
    }

    @Test
    fun move_names_the_missing_choice_then_how_it_moves() {
        assertEquals("Choose a host", moveButtonLabel(null, whenIdle = false))
        assertEquals("Choose a host", moveButtonLabel(null, whenIdle = true))
        assertEquals("Move", moveButtonLabel("nas", whenIdle = false))
        assertEquals("Move when idle", moveButtonLabel("nas", whenIdle = true))
    }

    @Test
    fun each_host_option_states_its_facts() {
        assertEquals("1 running · toolchain not checked", moveHostFacts(HostRow("hetzner-1", reachable = true), 1))
        assertEquals("agent · 0 running · toolchain not checked", moveHostFacts(HostRow("nas", reachable = true, transport = "agent"), 0))
        // Free disk only when the hub sampled it.
        assertEquals(
            "2 running · 22 GB free · toolchain not checked",
            moveHostFacts(HostRow("hetzner-1", reachable = true, diskHomeFreeKb = 22_000_000L), 2),
        )
    }

    @Test
    fun each_plan_entry_carries_its_verdict_as_a_word() {
        assertEquals("Resume", planVerdict(RestorePlanEntry(sessionId = 1, action = "restore")))
        assertEquals("Skip", planVerdict(RestorePlanEntry(sessionId = 2, action = "skip", reason = "a controller session")))
        assertEquals("Unknown", planVerdict(RestorePlanEntry(sessionId = 3)))
        // A word the hub adds later is shown as it comes, not dropped.
        assertEquals("Recreate fresh", planVerdict(RestorePlanEntry(sessionId = 4, action = "recreate_fresh")))
    }
}

/** Named as the JVM's own, so [HubError.Transport.kind] reads the same on every target. */
private class SocketTimeoutException : Exception("read timed out")
private class UnknownHostException : Exception("no such host")
