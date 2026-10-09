package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.Attention
import dev.claudefleet.mobile.model.HostRow
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
