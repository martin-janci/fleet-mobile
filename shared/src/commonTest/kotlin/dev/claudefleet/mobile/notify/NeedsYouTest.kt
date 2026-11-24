package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.Attention
import dev.claudefleet.mobile.model.GrantLevel
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.MyAccess
import dev.claudefleet.mobile.model.PendingInput
import dev.claudefleet.mobile.model.PendingOption
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun row(id: Long, reason: String? = null) =
    SessionRow(id = id, tmuxName = "s$id", friendlyName = "session $id", hostAlias = "pine", attention = reason?.let { Attention(it) })

class NeedsYouTest {

    /** Review w08-3 (option a): a session shared at Watch is its owner's question, so no alert; at Answer it alerts. */
    @Test
    fun a_watched_shared_session_does_not_alert() {
        val theirs = row(1, "waiting").copy(ownerPersonId = 2)
        val watch = MyAccess(personId = 7, grants = mapOf(1L to GrantLevel.WATCH))
        val (none, seen) = needsYouAlerts(mapOf(1L to null), listOf(theirs), watch)
        assertTrue(none.isEmpty())
        val (raised, _) = needsYouAlerts(seen, listOf(theirs), watch.copy(grants = mapOf(1L to GrantLevel.ANSWER)))
        assertEquals(listOf(1L), raised.map { it.sessionId })
        val (mine, _) = needsYouAlerts(mapOf(2L to null), listOf(row(2, "waiting")), watch)
        assertEquals(listOf(2L), mine.map { it.sessionId }, "a row shared with no one is mine")
    }

    /** Review w05-2: a shared session alerts only for what its level may act on, and Retry only at Steer. */
    @Test
    fun a_shared_session_alerts_only_for_what_its_level_may_do() {
        val failed = row(1, "failed").copy(ownerPersonId = 2, claudeStatus = "failed", lastPrompt = "go")
        val limit = row(2, "account_limit").copy(ownerPersonId = 2)
        val answer = MyAccess(personId = 7, grants = mapOf(1L to GrantLevel.ANSWER, 2L to GrantLevel.ANSWER))
        val (none, _) = needsYouAlerts(mapOf(1L to null, 2L to null), listOf(failed, limit), answer)
        assertTrue(none.isEmpty())
        val steer = MyAccess(personId = 7, grants = mapOf(1L to GrantLevel.DRIVE, 2L to GrantLevel.DRIVE))
        val (raised, _) = needsYouAlerts(mapOf(1L to null, 2L to null), listOf(failed, limit), steer)
        assertEquals(listOf(1L), raised.map { it.sessionId })
        assertTrue(raised.single().retryable)
    }

    @Test
    fun a_session_that_comes_to_need_you_is_news_once() {
        val (first, seen) = needsYouAlerts(mapOf(1L to null), listOf(row(1, "waiting")))
        assertEquals(listOf(NeedsYouAlert(1, "session 1", "Needs you · pine", reason = "waiting")), first)

        val (again, _) = needsYouAlerts(seen, listOf(row(1, "waiting")))
        assertTrue(again.isEmpty(), "still waiting is not news")
    }

    @Test
    fun a_different_reason_is_news_and_a_settled_session_is_not() {
        val (changed, seen) = needsYouAlerts(mapOf(1L to "waiting"), listOf(row(1, "stuck")))
        assertEquals("Failed · stuck · pine", changed.single().text)

        val (settled, after) = needsYouAlerts(seen, listOf(row(1)))
        assertTrue(settled.isEmpty())
        assertEquals(mapOf(1L to null), after)
    }

    /**
     * The hook says "waiting" first; the dialog's question follows a moment
     * later. That updates the alert quietly. A DIFFERENT question while still
     * waiting is news: it used to be silent, since the reason never changed.
     */
    @Test
    fun a_new_question_while_still_waiting_is_news_and_a_late_question_is_an_update() {
        val ask = { q: String -> row(1, "waiting").copy(pendingInput = PendingInput("permission", q, listOf(PendingOption(1, "Yes")))) }
        val (first, seen) = needsYouAlerts(mapOf(1L to null), listOf(row(1, "waiting")))
        assertEquals(false, first.single().quiet)

        val (filled, seen2) = needsYouAlerts(seen, listOf(ask("Run git push?")))
        assertEquals("Run git push?", filled.single().question)
        assertTrue(filled.single().quiet, "the question arriving updates the alert, quietly")

        val (same, _) = needsYouAlerts(seen2, listOf(ask("Run git push?")))
        assertTrue(same.isEmpty())

        val (next, seen3) = needsYouAlerts(seen2, listOf(ask("Delete build/?")))
        assertEquals("Delete build/?", next.single().question)
        assertEquals(false, next.single().quiet, "a second question is news")

        val (unparsed, _) = needsYouAlerts(seen3, listOf(row(1, "waiting")))
        assertTrue(unparsed.isEmpty(), "losing the parsed question is not news")
    }

    /** Review w04-2: a lost row (reason lifecycle, state paused) needs nobody, so nothing rings for it. */
    @Test
    fun a_lost_row_raises_no_alert() {
        val lost = row(6).copy(attention = Attention("lifecycle", state = "paused"), lostAt = 80)
        val (alerts, seen) = needsYouAlerts(mapOf(6L to null), listOf(lost))
        assertTrue(alerts.isEmpty(), "a lost row is not news")
        assertEquals(mapOf(6L to null), seen)
        // The old fallback (a hub that stamps nothing) reads a ghost as lifecycle too: still silent.
        val (ghost, _) = needsYouAlerts(emptyMap(), listOf(row(7).copy(status = "ghost")))
        assertTrue(ghost.isEmpty())
        // A lost row that still shows a question waits on an agent that is gone: silent too (review w13-2).
        val (asks, _) = needsYouAlerts(emptyMap(), listOf(row(8, "waiting").copy(lostAt = 80)))
        assertTrue(asks.isEmpty())
    }

    @Test
    fun a_new_session_that_already_needs_you_is_news() {
        val (alerts, _) = needsYouAlerts(emptyMap(), listOf(row(2, "failed")))
        assertEquals(listOf(2L), alerts.map { it.sessionId })
    }

    /** 14.11: a session that finished its task is a Done alert once, and never one running outside fleet. */
    @Test
    fun a_finished_session_is_a_done_alert_once() {
        val done = row(3).copy(claudeStatus = "completed")
        val (first, seen) = needsYouAlerts(mapOf(3L to null), listOf(done))
        assertEquals(DONE_REASON, first.single().reason)
        assertEquals("Done · pine", first.single().text)
        assertEquals("session 3 is done", needsYouContent(first.single()).title)
        assertEquals(listOf("Open", "Later"), needsYouContent(first.single()).actions.map { it.label })

        val (again, _) = needsYouAlerts(seen, listOf(done))
        assertTrue(again.isEmpty(), "still done is not news")

        val (external, _) = needsYouAlerts(emptyMap(), listOf(done.copy(kind = "external")))
        assertTrue(external.isEmpty())
        val (asks, _) = needsYouAlerts(emptyMap(), listOf(row(4, "waiting").copy(claudeStatus = "completed")))
        assertEquals("waiting", asks.single().reason, "a session that asks something is Needs you, not Done")
        // A routine's run finishing is the routine's news; its failure still rings.
        val (routine, _) = needsYouAlerts(emptyMap(), listOf(done.copy(origin = "routine", originRef = "3")))
        assertTrue(routine.isEmpty(), "a routine run's Done does not ring")
        val (routineFailed, _) = needsYouAlerts(emptyMap(), listOf(row(5, "failed").copy(origin = "routine")))
        assertEquals("failed", routineFailed.single().reason)
    }

    @Test
    fun the_reasons_read_as_words() {
        assertEquals("Needs you", reasonWords("waiting"))
        assertEquals("Paused", reasonWords("lifecycle"))
        assertEquals("CI failing", reasonWords("ci_failing"))
        assertEquals("Odd reason", reasonWords("odd_reason"))
    }

    private class Fleet : FleetState {
        override val sessions = MutableStateFlow(listOf(row(1, "waiting"), row(2)))
        override val hosts = MutableStateFlow(listOf(HostRow("pine", reachable = true)))
        override val projects = MutableStateFlow(emptyList<ProjectRow>())
        override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Offline("not yet"))
        override val hubVersion = MutableStateFlow<String?>(null)
        override val clockSkewSeconds = MutableStateFlow(0L)
        override val sessionChanges = MutableSharedFlow<Long>()
        override suspend fun refresh() = Unit
    }

    @Test
    fun what_already_needed_you_when_watching_began_is_not_news() = runTest {
        val fleet = Fleet()
        val got = mutableListOf<NeedsYouAlert>()
        backgroundScope.launch { needsYouAlerts(fleet).collect { got += it } }
        runCurrent()

        // Not connected yet: nothing is judged, not even the baseline.
        fleet.sessions.value = listOf(row(1, "waiting"), row(2, "stuck"))
        runCurrent()
        fleet.status.value = ConnectionStatus.Connected("0.9.3")
        runCurrent()
        assertTrue(got.isEmpty(), "the first connected look is the baseline")

        fleet.sessions.value = listOf(row(1, "waiting"), row(2, "stuck"), row(3, "waiting"))
        runCurrent()
        assertEquals(listOf(3L), got.map { it.sessionId })
    }

    @Test
    fun a_reconnect_keeps_the_baseline() = runTest {
        val fleet = Fleet()
        fleet.status.value = ConnectionStatus.Connected("0.9.3")
        val got = mutableListOf<NeedsYouAlert>()
        backgroundScope.launch { needsYouAlerts(fleet).collect { got += it } }
        runCurrent()

        fleet.status.value = ConnectionStatus.Offline("dropped")
        fleet.sessions.value = listOf(row(1, "waiting"), row(2, "failed"))
        runCurrent()
        fleet.status.value = ConnectionStatus.Connected("0.9.3")
        runCurrent()
        // What changed while away is news once the stream is back.
        assertEquals(listOf(2L), got.map { it.sessionId })
    }

    @Test
    fun a_session_that_stops_needing_you_is_resolved() = runTest {
        val fleet = Fleet()
        fleet.status.value = ConnectionStatus.Connected("0.9.3")
        val got = mutableListOf<NeedsYouEvent>()
        backgroundScope.launch { needsYouEvents(fleet).collect { got += it } }
        runCurrent()

        fleet.sessions.value = listOf(row(1), row(2))
        runCurrent()
        assertEquals(listOf<NeedsYouEvent>(NeedsYouResolved(1)), got)
    }

    @Test
    fun what_began_waiting_while_nothing_watched_is_news_on_the_next_run() = runTest {
        val fleet = Fleet()
        fleet.status.value = ConnectionStatus.Connected("0.9.3")
        val got = mutableListOf<NeedsYouEvent>()
        var kept: Map<Long, String?> = emptyMap()
        // The last run saw session 1 waiting and session 2 fine.
        backgroundScope.launch { needsYouEvents(fleet, remembered = mapOf(1L to "waiting", 2L to null)) { kept = it }.collect { got += it } }
        runCurrent()

        assertTrue(got.isEmpty(), "unchanged since the last run: nothing to say")
        assertEquals(mapOf(1L to "waiting", 2L to null), kept)
        fleet.sessions.value = listOf(row(1, "waiting"), row(2, "stuck"))
        runCurrent()
        assertEquals(listOf(2L), got.filterIsInstance<NeedsYouAlert>().map { it.sessionId })
    }

    @Test
    fun the_memory_is_written_only_when_a_reason_moves() = runTest {
        val fleet = Fleet()
        fleet.status.value = ConnectionStatus.Connected("0.9.3")
        var writes = 0
        backgroundScope.launch { needsYouEvents(fleet) { writes++ }.collect {} }
        fleet.sessions.value = listOf(row(1, "waiting"), row(2))
        runCurrent()
        val first = writes
        // A frame that changes something other than a notify reason: a rename.
        fleet.sessions.value = listOf(row(1, "waiting").copy(tmuxName = "renamed"), row(2))
        runCurrent()
        assertEquals(first, writes, "no reason moved, so nothing is persisted")
        fleet.sessions.value = listOf(row(1), row(2, "waiting"))
        runCurrent()
        assertEquals(first + 1, writes)
    }

    @Test
    fun the_memory_survives_a_round_trip_through_a_key_value_store() {
        val seen = mapOf(1L to "waiting", 2L to null, 3L to "ci_failing")
        assertEquals(seen, decodeSeen(encodeSeen(seen)))
        assertEquals(emptyMap(), decodeSeen(listOf("garbage", "=x")))
    }
}
