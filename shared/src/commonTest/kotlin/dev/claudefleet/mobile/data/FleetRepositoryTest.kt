@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.net.EventStream
import dev.claudefleet.mobile.net.HubClient
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.HubEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val HUB = "https://fleet.example.com"

/**
 * No `withTimeout` anywhere below, on purpose: `runTest`'s clock is virtual, so
 * a timeout scheduled on it fires the moment the scheduler runs out of tasks —
 * which is exactly while a mock-engine call is in flight on a real dispatcher.
 * `runTest`'s own timeout is the safety net.
 */

/** An `/mcp` reply, framed the way the hub frames one. */
private fun okResult(payloadJson: String): String {
    val quoted = payloadJson.replace("\\", "\\\\").replace("\"", "\\\"")
    return "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1," +
        "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"$quoted\"}]}}\n\n"
}

/**
 * A hub that answers `list_sessions` and `list_hosts` from whatever the test
 * last put in [sessionsJson] / [hostsJson], and counts the calls.
 */
private class FakeHub(
    var sessionsJson: String = "[]",
    var hostsJson: String = "[]",
) {
    var sessionCalls = 0
        private set
    var hostCalls = 0
        private set

    /** Makes `list_hosts` answer 401, so a refresh fails after its first call. */
    var failHosts = false

    val client: HubClient = HubClient(
        HttpClient(
            MockEngine { request ->
                val body = (request.body as TextContent).text
                val payload = when {
                    "list_sessions" in body -> { sessionCalls += 1; sessionsJson }
                    "list_hosts" in body -> { hostCalls += 1; hostsJson }
                    else -> "[]"
                }
                if (failHosts && "list_hosts" in body) {
                    respond("", HttpStatusCode.Unauthorized)
                } else {
                    respond(
                        okResult(payload),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                }
            },
        ),
        HUB,
        "tok-phone",
    )
}

/**
 * A stream the test drives. [behaviour] is invoked per connection attempt with
 * the attempt number (1-based) and may emit, throw, or hang.
 */
private class FakeStream(
    private val behaviour: suspend FlowCollector<HubEvent>.(attempt: Int) -> Unit,
) : EventStream {
    /** Virtual-clock millis at which each connection was opened. */
    val openedAt = mutableListOf<Long>()
    var cancelled = 0
        private set
    var clock: TestScope? = null

    override fun connect(): Flow<HubEvent> = flow {
        openedAt += clock?.testScheduler?.currentTime ?: 0L
        try {
            behaviour(openedAt.size)
        } catch (t: CancellationException) {
            cancelled += 1
            throw t
        }
    }
}

private fun repo(hub: FakeHub, stream: EventStream, scope: CoroutineScope) =
    FleetRepository(hub.client, stream, scope)

private val READY = HubEvent.Ready("0.9.3", listOf("session", "host"))

private fun sessionRows(vararg ids: Long) =
    ids.joinToString(",", "[", "]") { """{"id":$it,"tmux_name":"s$it","host_alias":"box"}""" }

private fun rowEvent(name: String, json: String) =
    HubEvent.Row(name, Json.parseToJsonElement(json))

class FleetRepositoryTest {

    @Test
    fun the_ready_frame_resyncs_the_snapshot_and_reports_connected() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1, 2), hostsJson = """[{"alias":"box"}]""")
        val stream = FakeStream { emit(READY); awaitCancellation() }
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        val status = repository.status.first { it is ConnectionStatus.Connected }

        assertEquals(ConnectionStatus.Connected("0.9.3"), status)
        assertEquals(listOf(1L, 2L), repository.sessions.value.map { it.id })
        assertEquals(listOf("box"), repository.hosts.value.map { it.alias })
        assertEquals(1, hub.sessionCalls)
        repository.stop()
    }

    @Test
    fun a_session_updated_frame_replaces_a_row_in_the_published_snapshot() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1, 2))
        val stream = FakeStream {
            emit(READY)
            emit(rowEvent("session:updated", """{"id":2,"tmux_name":"renamed","host_alias":"box"}"""))
            awaitCancellation()
        }
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        val sessions = repository.sessions.first { rows -> rows.any { it.tmuxName == "renamed" } }

        assertEquals(listOf(1L, 2L), sessions.map { it.id })
        repository.stop()
    }

    @Test
    fun a_session_killed_frame_removes_the_row() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1, 2))
        val stream = FakeStream {
            emit(READY)
            emit(rowEvent("session:killed", """{"id":1}"""))
            awaitCancellation()
        }
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        val sessions = repository.sessions.first { it.size == 1 }

        assertEquals(listOf(2L), sessions.map { it.id })
        repository.stop()
    }

    @Test
    fun an_unknown_event_name_leaves_the_snapshot_alone() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1, 2))
        val seen = CompletableDeferred<Unit>()
        val stream = FakeStream {
            emit(READY)
            emit(rowEvent("task:updated", """{"id":1,"state":"done"}"""))
            emit(rowEvent("account_usage:updated", """{"account_uuid":"u"}"""))
            emit(rowEvent("session:renamed", """{"id":1}"""))
            seen.complete(Unit)
            awaitCancellation()
        }
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        seen.await()

        assertEquals(listOf(1L, 2L), repository.sessions.value.map { it.id })
        assertEquals("s1", repository.sessions.value[0].tmuxName)
        repository.stop()
    }

    /** The hub says the picture has a hole in it; the only honest fix is to refetch. */
    @Test
    fun a_lagged_frame_triggers_a_refetch() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1))
        val refetched = CompletableDeferred<Unit>()
        val stream = FakeStream { attempt ->
            if (attempt > 1) awaitCancellation()
            emit(READY)
            // The rows the hub is about to tell us we missed.
            hub.sessionsJson = sessionRows(1, 2, 3)
            emit(HubEvent.Lagged(17))
            refetched.complete(Unit)
            awaitCancellation()
        }
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        refetched.await()

        assertEquals(listOf(1L, 2L, 3L), repository.sessions.value.map { it.id })
        assertEquals(2, hub.sessionCalls, "once for ready, once for the lag")
        repository.stop()
    }

    /**
     * A dropped stream reconnects, and waits longer each time. The clock is
     * virtual and nothing here touches the network, so these are exact.
     */
    @Test
    fun a_dropped_stream_reconnects_with_growing_backoff() = runTest {
        val hub = FakeHub()
        val stream = FakeStream { throw HubError.Transport(RuntimeException("connection reset")) }
        stream.clock = this
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        testScheduler.advanceTimeBy(15_001)
        repository.stop()

        // 1 s, 2 s, 4 s, 8 s between attempts.
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L), stream.openedAt)
    }

    @Test
    fun the_backoff_is_capped_rather_than_doubling_forever() = runTest {
        val hub = FakeHub()
        val stream = FakeStream { throw HubError.Transport(RuntimeException("down")) }
        stream.clock = this
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        testScheduler.advanceTimeBy(600_001)
        repository.stop()

        val gaps = stream.openedAt.zipWithNext { a, b -> b - a }
        assertEquals(30_000L, gaps.last(), "the wait settles at the cap")
        assertTrue(gaps.all { it <= 30_000L }, "nothing waits longer than the cap: $gaps")
    }

    @Test
    fun the_status_flow_reports_each_reconnect_attempt() = runTest {
        val hub = FakeHub()
        val stream = FakeStream { throw HubError.Transport(RuntimeException("connection reset")) }
        stream.clock = this
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        testScheduler.advanceTimeBy(3_500)
        val status = repository.status.value
        repository.stop()

        assertTrue(status is ConnectionStatus.Reconnecting, "expected Reconnecting, got $status")
        assertEquals(4, status.attempt, "three failures so far, so the fourth attempt is pending")
        assertTrue(
            status.reason.orEmpty().contains("connection reset"),
            "the banner should say why: ${status.reason}",
        )
    }

    /**
     * A successful connection earns a fresh budget, not the tail of the old one.
     *
     * The fourth attempt is awaited rather than clock-advanced: attempt 3 sends
     * `ready`, which refetches over the mock engine — real work on a real
     * dispatcher that no amount of virtual time will hurry. `openedAt` still
     * records the virtual clock, so the gaps stay exact.
     */
    @Test
    fun a_connection_that_succeeds_resets_the_backoff() = runTest {
        val hub = FakeHub()
        val fourth = CompletableDeferred<Unit>()
        val stream = FakeStream { attempt ->
            when (attempt) {
                1, 2 -> throw HubError.Transport(RuntimeException("down"))
                3 -> emit(READY) // then the flow completes: the hub closed the stream
                else -> {
                    fourth.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        stream.clock = this
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        fourth.await()
        repository.stop()

        // 0, +1 s, +2 s, then a good connection, so the next wait is 1 s again.
        assertEquals(listOf(0L, 1_000L, 3_000L, 4_000L), stream.openedAt.take(4))
    }

    /** Retrying against a revoked token forever helps nobody. */
    @Test
    fun a_401_stops_the_loop_instead_of_reconnecting() = runTest {
        val hub = FakeHub()
        val stream = FakeStream { throw HubError.Unauthorized("no") }
        stream.clock = this
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        testScheduler.advanceTimeBy(600_000)

        assertEquals(1, stream.openedAt.size, "one attempt, then it gives up")
        val status = repository.status.value
        assertTrue(status is ConnectionStatus.Offline, "expected Offline, got $status")
        assertTrue(status.reason.isNotBlank())
    }

    @Test
    fun stop_cancels_the_stream_and_everything_under_it() = runTest {
        val hub = FakeHub()
        val stream = FakeStream { emit(READY); awaitCancellation() }
        stream.clock = this
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        repository.status.first { it is ConnectionStatus.Connected }
        repository.stop()
        testScheduler.advanceTimeBy(600_000)

        assertEquals(1, stream.cancelled, "the open stream was cancelled")
        assertEquals(1, stream.openedAt.size, "and nothing reconnected after stop()")
        assertTrue(repository.status.value is ConnectionStatus.Offline)
    }

    @Test
    fun start_twice_does_not_open_two_streams() = runTest {
        val hub = FakeHub()
        val stream = FakeStream { emit(READY); awaitCancellation() }
        stream.clock = this
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        repository.status.first { it is ConnectionStatus.Connected }
        repository.start()
        testScheduler.advanceTimeBy(60_000)
        repository.stop()

        assertEquals(1, stream.openedAt.size)
    }

    /** The app backgrounds and resumes; what was on screen stays on screen. */
    @Test
    fun a_stopped_repository_keeps_the_last_snapshot() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1, 2))
        val stream = FakeStream { emit(READY); awaitCancellation() }
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        repository.sessions.first { it.size == 2 }
        repository.stop()

        assertEquals(listOf(1L, 2L), repository.sessions.value.map { it.id })
        assertFalse(repository.status.value is ConnectionStatus.Connected)
    }

    /** `refresh()` is the explicit path, and it tells the caller when it failed. */
    @Test
    fun refresh_reports_a_failure_rather_than_swallowing_it() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        val client = HubClient(HttpClient(engine), HUB, "tok-phone")
        val repository = FleetRepository(client, FakeStream { awaitCancellation() }, backgroundScope)

        var thrown: Throwable? = null
        try {
            repository.refresh()
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue(thrown is HubError.Unauthorized, "expected Unauthorized, got $thrown")
    }

    /** A half-applied refresh is worse than a failed one. */
    @Test
    fun a_refresh_that_fails_half_way_leaves_the_old_snapshot_intact() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1, 2), hostsJson = """[{"alias":"box"}]""")
        val stream = FakeStream { emit(READY); awaitCancellation() }
        val repository = repo(hub, stream, backgroundScope)
        repository.start()
        repository.sessions.first { it.size == 2 }
        repository.stop()

        // Sessions would now answer differently, but hosts refuses.
        hub.sessionsJson = sessionRows(9)
        hub.failHosts = true
        try {
            repository.refresh()
        } catch (_: HubError) {
            // expected
        }

        assertEquals(listOf(1L, 2L), repository.sessions.value.map { it.id })
        assertEquals(listOf("box"), repository.hosts.value.map { it.alias })
    }
}
