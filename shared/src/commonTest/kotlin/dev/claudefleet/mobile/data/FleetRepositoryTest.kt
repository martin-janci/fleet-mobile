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
    var projectsJson: String = "[]",
) {
    var sessionCalls = 0
        private set
    var hostCalls = 0
        private set
    var projectCalls = 0
        private set

    /** Makes `list_hosts` answer 401, so a refresh fails after its first call. */
    var failHosts = false

    /** Makes `list_sessions` answer 502, so every refresh fails at its first call. */
    var failSessions = false

    val client: HubClient = HubClient(
        HttpClient(
            MockEngine { request ->
                val body = (request.body as TextContent).text
                val payload = when {
                    "list_sessions" in body -> { sessionCalls += 1; sessionsJson }
                    "list_hosts" in body -> { hostCalls += 1; hostsJson }
                    "list_projects" in body -> { projectCalls += 1; projectsJson }
                    else -> "[]"
                }
                if (failHosts && "list_hosts" in body) {
                    respond("", HttpStatusCode.Unauthorized)
                } else if (failSessions && "list_sessions" in body) {
                    respond("upstream is down", HttpStatusCode.BadGateway)
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

    /**
     * The reconnect reason is a sentence written for a person, not whatever a
     * throwable happens to carry.
     *
     * `explain()` exists to stop an unexpected throwable's own message walking
     * onto the screen, and the repository used to have a second mapping —
     * `t.message ?: t::class.simpleName` — that did exactly that. It went
     * unnoticed while the field was never drawn; now that the banner renders
     * it, an arbitrary library's exception text would go with it.
     */
    @Test
    fun a_reconnect_reason_is_explained_rather_than_repeated() = runTest {
        val hub = FakeHub()
        val leaky = "Authorization: Bearer 0123456789abcdef"
        val stream = FakeStream { throw IllegalStateException(leaky) }
        stream.clock = this
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        testScheduler.advanceTimeBy(1_500)
        val status = repository.status.value
        repository.stop()

        assertTrue(status is ConnectionStatus.Reconnecting, "expected Reconnecting, got $status")
        assertFalse(
            status.reason.orEmpty().contains("Bearer"),
            "an unexpected throwable's own message reached the banner: ${status.reason}",
        )
        assertTrue(
            status.reason.orEmpty().contains("IllegalStateException"),
            "it should still say what kind of failure it was: ${status.reason}",
        )
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
        // The banner names the failure's *type*, not its text: `HubError.Transport`
        // stopped repeating `cause.message` when it turned out that a truncated
        // pair reply put the bearer token in it. See `TokenNeverLeaksTest`.
        assertTrue(
            status.reason.orEmpty().contains("RuntimeException"),
            "the banner should still say what kind of failure it was: ${status.reason}",
        )
        assertFalse(
            status.reason.orEmpty().contains("connection reset"),
            "and must not repeat the cause's own text: ${status.reason}",
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

    /**
     * Task 4 review. The failure count was zeroed when the `ready` frame
     * arrived, *before* the refetch it triggers — so a hub whose `/events`
     * answers and whose `/mcp` does not reconnected every second forever: each
     * pass reset the count to zero, the refetch threw, the count went back to
     * one, and the wait never grew past the first step. That is a phone radio
     * held open against a half-working hub.
     *
     * A connection is not a success until the resync it exists for has worked.
     */
    @Test
    fun a_ready_whose_refetch_fails_still_grows_the_backoff() = runTest {
        val hub = FakeHub()
        hub.failSessions = true
        val fourth = CompletableDeferred<Unit>()
        // The signal comes BEFORE the emit: a `ready` whose refetch throws takes
        // the exception out through `collect`, so anything after `emit` in this
        // block never runs.
        val stream = FakeStream { attempt ->
            if (attempt >= 4) {
                fourth.complete(Unit)
                awaitCancellation()
            }
            emit(READY)
        }
        stream.clock = this
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        fourth.await()
        repository.stop()

        // 0, +1 s, +2 s, +4 s — doubling, not a flat second.
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), stream.openedAt.take(4))
    }

    /**
     * Task 5 review, N4. Stopping the loop was only half of it.
     *
     * `SessionActions` exists so that "a 401 drops the credential and returns to
     * Pair" is impossible to forget rather than merely discouraged — and the
     * repository, which holds a raw `HubClient`, was the one caller outside it.
     * The app sat on a revoked token showing a sentence, while the identical 401
     * through `HubSessionActions` would have routed to Pair.
     */
    @Test
    fun a_401_on_the_stream_drops_the_credential_as_well_as_stopping() = runTest {
        val hub = FakeHub()
        val stream = FakeStream { throw HubError.Unauthorized("no") }
        stream.clock = this
        var revoked = 0
        val repository = FleetRepository(
            hub.client,
            stream,
            backgroundScope,
            onRevoked = { revoked += 1 },
        )

        repository.start()
        testScheduler.advanceTimeBy(600_000)

        assertEquals(1, revoked, "the credential must be dropped, not merely reported")
        assertTrue(repository.status.value is ConnectionStatus.Offline)
    }

    /** Only a 401. A hub that is merely down has not revoked anything. */
    @Test
    fun any_other_stream_failure_leaves_the_credential_alone() = runTest {
        val hub = FakeHub()
        val stream = FakeStream { throw HubError.Http(503, "events are not enabled") }
        stream.clock = this
        var revoked = 0
        val repository = FleetRepository(
            hub.client,
            stream,
            backgroundScope,
            onRevoked = { revoked += 1 },
        )

        repository.start()
        testScheduler.advanceTimeBy(10_000)
        repository.stop()

        assertEquals(0, revoked)
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

    /**
     * A session row names its project by id alone, so the list of projects is
     * the only thing that can turn `project_id: 3` into a heading a person
     * recognises. It is part of the snapshot for that reason.
     */
    @Test
    fun a_refresh_fetches_the_projects_the_session_rows_point_at() = runTest {
        val hub = FakeHub(
            sessionsJson = sessionRows(1),
            hostsJson = """[{"alias":"box"}]""",
            projectsJson = """[{"id":3,"owner":"martin-janci","repo":"claude-fleet","worktree_count":2}]""",
        )
        val repository = repo(hub, FakeStream { awaitCancellation() }, backgroundScope)

        repository.refresh()

        assertEquals(1, hub.projectCalls)
        assertEquals(listOf("martin-janci/claude-fleet"), repository.projects.value.map { it.label })
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
