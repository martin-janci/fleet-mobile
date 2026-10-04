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
        assertEquals(listOf(NeedsYouAlert(1, "session 1", "Waiting for you · pine")), first)

        val (again, _) = needsYouAlerts(seen, listOf(row(1, "waiting")))
        assertTrue(again.isEmpty(), "still waiting is not news")
    }

    @Test
    fun a_different_reason_is_news_and_a_settled_session_is_not() {
        val (changed, seen) = needsYouAlerts(mapOf(1L to "waiting"), listOf(row(1, "stuck")))
        assertEquals("Stuck · pine", changed.single().text)

        val (settled, after) = needsYouAlerts(seen, listOf(row(1)))
        assertTrue(settled.isEmpty())
        assertEquals(mapOf(1L to null), after)
    }

    @Test
    fun a_new_session_that_already_needs_you_is_news() {
        val (alerts, _) = needsYouAlerts(emptyMap(), listOf(row(2, "failed")))
        assertEquals(listOf(2L), alerts.map { it.sessionId })
    }

    @Test
    fun the_reasons_read_as_words() {
        assertEquals("Waiting for you", reasonWords("waiting"))
        assertEquals("Needs a decision", reasonWords("lifecycle"))
        assertEquals("Ci failing", reasonWords("ci_failing"))
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
}
