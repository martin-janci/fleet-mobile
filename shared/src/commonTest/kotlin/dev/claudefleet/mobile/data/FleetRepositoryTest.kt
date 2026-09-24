@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.net.EventStream
import dev.claudefleet.mobile.net.HubClient
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.HubEvent
import dev.claudefleet.mobile.net.contractVerdict
import dev.claudefleet.mobile.net.sentence
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    /** What `tools/list` answers, as the `tools` array; null makes it a 404-free JSON-RPC error. */
    var toolsJson: String? = null
    var trackersJson: String = "[]"
    var mineJson: String = "[]"
    var toolListCalls = 0
        private set

    /** Makes `list_hosts` answer 401, so a refresh fails after its first call. */
    var failHosts = false

    /** Makes `list_sessions` answer 502, so every refresh fails at its first call. */
    var failSessions = false

    val client: HubClient = HubClient(
        HttpClient(
            MockEngine { request ->
                val body = (request.body as TextContent).text
                if ("tools/list" in body) {
                    toolListCalls += 1
                    val reply = toolsJson?.let { """{"jsonrpc":"2.0","id":1,"result":{"tools":$it}}""" }
                        ?: """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"method not found"}}"""
                    return@MockEngine respond(reply, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                val payload = when {
                    "\"trackers\"" in body -> trackersJson
                    "\"tickets\"" in body -> mineJson
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

    /** The `Last-Event-ID` each connection was opened with. */
    val resumedFrom = mutableListOf<String?>()

    override fun connect(lastEventId: String?): Flow<HubEvent> = flow {
        resumedFrom += lastEventId
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

    /**
     * Every age the list draws is a hub timestamp minus a local clock, so a
     * device whose time is wrong is wrong about the whole fleet at once, and
     * silently. The hub states its own time once per connection; this is where
     * the difference is taken.
     */
    @Test
    fun the_ready_frame_sets_the_clock_skew_against_this_device() = runTest {
        val hub = FakeHub()
        val stream = FakeStream { emit(READY.copy(now = 1_000_120L)); awaitCancellation() }
        val repository = FleetRepository(hub.client, stream, backgroundScope, clock = { 1_000_000L })

        repository.start()
        repository.status.first { it is ConnectionStatus.Connected }

        assertEquals(120L, repository.clockSkewSeconds.value, "the hub is two minutes ahead of this phone")
        repository.stop()
    }

    /** A hub that states no time leaves the app on its own clock, as before. */
    @Test
    fun a_ready_frame_without_a_clock_leaves_the_skew_alone() = runTest {
        val hub = FakeHub()
        val stream = FakeStream { emit(READY); awaitCancellation() }
        val repository = FleetRepository(hub.client, stream, backgroundScope, clock = { 1_000_000L })

        repository.start()
        repository.status.first { it is ConnectionStatus.Connected }

        assertEquals(0L, repository.clockSkewSeconds.value)
        repository.stop()
    }

    /**
     * A hub naming a contract revision this build does not understand is not
     * a transport failure — it answered fine — so it gets its own status
     * rather than a reconnect: [ConnectionStatus.Refused] with the sentence
     * [contractVerdict] hands back, no resync, and every later frame on this
     * same connection dropped. The reconnect/backoff loop itself is
     * unchanged: an upgraded hub (or app) is picked up on the next attempt,
     * this test just never drives the stream that far.
     *
     * `Refused` rather than `Offline`, and the difference is behaviour rather
     * than wording: a screen probes an offline hub and re-enables Send when it
     * answers, which for a refused hub means a live Send button pointed at a
     * hub this build has already decided it cannot read. See
     * `ConnectionStatus.Refused`.
     */
    @Test
    fun a_ready_frame_naming_a_too_new_contract_is_refused_and_applies_no_rows() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1, 2))
        val stream = FakeStream {
            emit(HubEvent.Ready("0.9.9", listOf("session", "host"), contract = 5))
            emit(rowEvent("session:updated", """{"id":1,"tmux_name":"renamed","host_alias":"box"}"""))
            awaitCancellation()
        }
        val repository = repo(hub, stream, backgroundScope)
        val expected = ConnectionStatus.Refused(contractVerdict(5).sentence()!!)

        repository.start()
        // Matching the exact refusal, not merely `it is Refused`: the
        // sentence is the whole content of this status, and a test that only
        // checked the type would pass on a refusal naming the wrong side.
        val status = repository.status.first { it == expected }

        assertEquals(expected, status)
        assertEquals(emptyList<Long>(), repository.sessions.value.map { it.id }, "no rows applied from a refused connection")
        assertEquals(0, hub.sessionCalls, "the resync must be skipped")
        repository.stop()
    }

    /**
     * The hub's version is what gates the structured `send_prompt { keys }`
     * chips on a blocked card ([dev.claudefleet.mobile.net.HUB_VERSION_KEYS]),
     * so it is remembered from every `ready` — including one whose contract is
     * then refused, because "which hub is this" is exactly the fact a person
     * staring at a refusal needs. It is never cleared on a drop, either: the
     * last hub seen is a better answer than none while the stream is down.
     */
    @Test
    fun every_ready_frame_records_the_hubs_version_including_a_refused_one() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1))
        val stream = FakeStream { emit(HubEvent.Ready("0.9.9", listOf("session"), contract = 5)); awaitCancellation() }
        val repository = repo(hub, stream, backgroundScope)

        assertNull(repository.hubVersion.value, "nothing seen before the first ready")
        repository.start()
        repository.status.first { it is ConnectionStatus.Refused }

        assertEquals("0.9.9", repository.hubVersion.value)
        repository.stop()
        assertEquals("0.9.9", repository.hubVersion.value, "a stopped stream does not un-see the hub")
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

    /**
     * A session screen needs to know *which* session changed, not just that the
     * snapshot did, so it can refetch its own conversation and leave every other
     * open screen alone.
     *
     * `READY` itself now also publishes [ALL_SESSIONS_CHANGED] (see below), so
     * it is the first thing this test's collector sees — that is a separate,
     * deliberately over-inclusive signal, not this test's own subject.
     */
    @Test
    fun a_session_row_event_publishes_its_id_on_sessionChanges() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1, 2))
        val stream = FakeStream {
            emit(READY)
            emit(rowEvent("session:updated", """{"id":2,"tmux_name":"renamed","host_alias":"box"}"""))
            emit(rowEvent("session:killed", """{"id":1}"""))
            awaitCancellation()
        }
        val repository = repo(hub, stream, backgroundScope)
        val seen = mutableListOf<Long>()
        // Subscribed and suspended in `collect` before a single event is sent:
        // `sessionChanges` has no replay, so a collector that starts after the
        // fact — like a screen opened after the row already changed — is not
        // meant to see it, and neither would this test's assertion.
        val collector = backgroundScope.launch { repository.sessionChanges.collect { seen += it } }
        runCurrent()

        repository.start()
        repository.sessions.first { it.size == 1 }
        // `sessions` and `sessionChanges` are two independent collectors, each
        // resumed through its own dispatched continuation; the row's removal
        // from `sessions` is not proof the same event's id has reached the
        // other flow's collector yet.
        runCurrent()

        assertEquals(listOf(ALL_SESSIONS_CHANGED, 2L, 1L), seen)
        collector.cancel()
        repository.stop()
    }

    /** `host:probed` and `project:updated` are not about any session. */
    @Test
    fun a_non_session_row_event_adds_nothing_beyond_the_readys_own_resync_signal() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1))
        val stream = FakeStream {
            emit(READY)
            emit(rowEvent("host:probed", """{"alias":"box"}"""))
            awaitCancellation()
        }
        val repository = repo(hub, stream, backgroundScope)
        val seen = mutableListOf<Long>()
        val collector = backgroundScope.launch { repository.sessionChanges.collect { seen += it } }
        runCurrent()

        repository.start()
        repository.hosts.first { it.isNotEmpty() }
        runCurrent()

        assertEquals(listOf(ALL_SESSIONS_CHANGED), seen, "only READY's own resync signal — host:probed names no session")
        collector.cancel()
        repository.stop()
    }

    /**
     * Finding #4: a reconnect resync used to publish nothing on
     * [dev.claudefleet.mobile.data.FleetRepository.sessionChanges] at all, so a
     * reply that landed during the gap never appeared on an open session
     * screen until a manual Refresh. `READY` now also emits
     * [ALL_SESSIONS_CHANGED] once its own `refresh()` has landed — a resync
     * has no per-row event of its own for a screen to key on, so this is the
     * one signal every open session screen treats as "refetch me too."
     */
    @Test
    fun a_ready_frame_publishes_the_resync_sentinel_once_its_refetch_has_landed() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1))
        val stream = FakeStream { emit(READY); awaitCancellation() }
        val repository = repo(hub, stream, backgroundScope)
        val seen = mutableListOf<Long>()
        val collector = backgroundScope.launch { repository.sessionChanges.collect { seen += it } }
        runCurrent()

        repository.start()
        repository.status.first { it is ConnectionStatus.Connected }
        runCurrent()

        assertEquals(listOf(ALL_SESSIONS_CHANGED), seen)
        collector.cancel()
        repository.stop()
    }

    /** Same rule for a `lagged` resync as for a `ready` one. */
    @Test
    fun a_lagged_frame_also_publishes_the_resync_sentinel() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1))
        val refetched = CompletableDeferred<Unit>()
        val stream = FakeStream { attempt ->
            if (attempt > 1) awaitCancellation()
            emit(READY)
            hub.sessionsJson = sessionRows(1, 2)
            emit(HubEvent.Lagged(3))
            refetched.complete(Unit)
            awaitCancellation()
        }
        val repository = repo(hub, stream, backgroundScope)
        val seen = mutableListOf<Long>()
        val collector = backgroundScope.launch { repository.sessionChanges.collect { seen += it } }
        runCurrent()

        repository.start()
        refetched.await()
        runCurrent()

        assertEquals(listOf(ALL_SESSIONS_CHANGED, ALL_SESSIONS_CHANGED), seen, "once for ready, once for the lag")
        collector.cancel()
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

    /**
     * `/events` has no request deadline (a live stream has no natural end),
     * so the idle-socket timeout is the only thing that notices a half-open
     * connection. `HubEventStream.connect()`'s catch-all wraps whatever the
     * engine throws once that timeout fires in [HubError.Transport] — the
     * same shape any other dropped connection arrives in — so this proves a
     * stream that goes silent ends up `Reconnecting` and a later connection
     * still recovers to `Connected`, rather than, say, being read as a clean
     * end of stream (no error at all) or routed to `Offline` the way a 401 is.
     */
    @Test
    fun a_stream_that_goes_idle_past_the_socket_timeout_reconnects_and_recovers() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1))
        val reconnected = CompletableDeferred<Unit>()
        val stream = FakeStream { attempt ->
            if (attempt == 1) {
                // What HubEventStream.connect()'s catch-all produces once the
                // idle-socket timeout fires on a stream gone silent.
                throw HubError.Transport(RuntimeException("Read timed out"))
            }
            emit(READY)
            reconnected.complete(Unit)
            awaitCancellation()
        }
        stream.clock = this
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        val reconnecting = repository.status.first { it is ConnectionStatus.Reconnecting && it.attempt == 2 }
                as ConnectionStatus.Reconnecting
        assertTrue(
            reconnecting.reason.orEmpty().contains("RuntimeException"),
            "an idle timeout should explain the drop like any other transport failure: ${reconnecting.reason}",
        )

        reconnected.await()
        val status = repository.status.first { it is ConnectionStatus.Connected }

        assertEquals(ConnectionStatus.Connected("0.9.3"), status)
        repository.stop()
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

    /**
     * The banner says "reconnecting" from the moment `start()` is called.
     *
     * Not once the first attempt has already failed — *while* it is being
     * made. Opening the app out of signal, or pointed at a hub that is down,
     * means the first connect hangs until its timeout; without this the screen
     * would sit on whatever it said before (`not connected yet`) for that whole
     * time, which reads as "nothing is happening" rather than "trying".
     *
     * Found by mutation: deleting the announcement changed nothing any test
     * could see, because every other reconnect test advances the clock past a
     * failure first and then looks.
     */
    @Test
    fun the_first_connection_attempt_is_announced_before_it_answers() = runTest {
        val stream = FakeStream { awaitCancellation() }
        stream.clock = this
        val repository = repo(FakeHub(), stream, backgroundScope)

        repository.start()
        runCurrent()
        val status = repository.status.value
        repository.stop()

        assertTrue(status is ConnectionStatus.Reconnecting, "expected Reconnecting, got $status")
        assertEquals(1, status.attempt, "the attempt being waited through is the first one")
        assertNull(status.reason, "nothing has failed yet, so there is nothing to blame")
    }

    /**
     * A hub that closes the stream cleanly still gets a reason.
     *
     * This is the one drop that arrives as no exception at all: the flow simply
     * ends. Every other path reaches the banner through `explain(t)`, so a
     * clean close is the only way to get a `Reconnecting` with nothing in it —
     * a banner that says the app is reconnecting and will not say from what,
     * which is exactly the state a person cannot act on.
     *
     * Found by mutation: blanking the reason survived every test, because none
     * of them closed a stream without throwing.
     */
    @Test
    fun a_cleanly_closed_stream_says_so_rather_than_reconnecting_silently() = runTest {
        val stream = FakeStream { attempt ->
            // First connection ends normally, with no error; the second stays
            // open so the loop settles somewhere observable.
            if (attempt > 1) awaitCancellation()
        }
        stream.clock = this
        val repository = repo(FakeHub(), stream, backgroundScope)

        repository.start()
        val status = repository.status.first {
            it is ConnectionStatus.Reconnecting && it.attempt == 2
        } as ConnectionStatus.Reconnecting
        repository.stop()

        assertNotNull(
            status.reason,
            "a hub that closed the stream must say so; a reasonless Reconnecting banner " +
                "tells a person the app is retrying and refuses to say from what",
        )
    }

    /**
     * A dropped stream costs what it missed, not a re-list: the next
     * connection names the last frame the snapshot actually applied, and a
     * hub that answers `resumed: true` is trusted to replay the rest — no
     * `list_*` round trip at all.
     */
    @Test
    fun a_reconnect_resumes_from_the_last_applied_frame_and_skips_the_relist() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1))
        val stream = FakeStream { attempt ->
            if (attempt == 1) {
                emit(READY)
                emit(HubEvent.Row("session:updated", Json.parseToJsonElement("""{"id":1,"tmux_name":"a","host_alias":"box"}"""), "7-10"))
                emit(HubEvent.Row("session:updated", Json.parseToJsonElement("""{"id":1,"tmux_name":"b","host_alias":"box"}"""), "7-11"))
                throw HubError.Transport(RuntimeException("tunnel"))
            }
            emit(READY.copy(resumed = true))
            emit(HubEvent.Row("session:updated", Json.parseToJsonElement("""{"id":1,"tmux_name":"c","host_alias":"box"}"""), "7-12"))
            awaitCancellation()
        }
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        repository.sessions.first { rows -> rows.singleOrNull()?.tmuxName == "c" }

        assertEquals(listOf(null, "7-11"), stream.resumedFrom)
        assertEquals(1, hub.sessionCalls, "a resumed stream is not re-listed")
        assertEquals(ConnectionStatus.Connected("0.9.3"), repository.status.value)
        repository.stop()
    }

    /**
     * A full re-list is newer than every frame before it, so the id it
     * followed is spent: resuming from it would replay older rows over the
     * fresh snapshot. A hub that could not resume re-lists, and the next
     * connection starts clean.
     */
    @Test
    fun a_relist_spends_the_id_it_followed() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1))
        val third = CompletableDeferred<Unit>()
        val stream = FakeStream { attempt ->
            when (attempt) {
                1 -> {
                    emit(READY)
                    emit(HubEvent.Row("session:updated", Json.parseToJsonElement("""{"id":1,"tmux_name":"a","host_alias":"box"}"""), "7-10"))
                    throw HubError.Transport(RuntimeException("tunnel"))
                }
                2 -> {
                    emit(READY.copy(resumed = false))
                    throw HubError.Transport(RuntimeException("tunnel again"))
                }
                else -> { third.complete(Unit); awaitCancellation() }
            }
        }
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        third.await()

        assertEquals(listOf(null, "7-10", null), stream.resumedFrom)
        assertEquals(2, hub.sessionCalls, "the unresumed ready re-listed")
        repository.stop()
    }

    // ---- the work graph (M8): what this token may call, per connection ----

    @Test
    fun every_ready_asks_the_hub_which_tools_this_token_has() = runTest {
        val hub = FakeHub().apply {
            toolsJson = """[{"name":"list_sessions"},{"name":"work"},{"name":"work_link"}]"""
            trackersJson = """[{"id":1,"provider":"jira","name":"acme","state":"ok"}]"""
            mineJson = """[{"id":70,"key":"PAY-7","title":"Refund"},{"id":90,"key":"PAY-9","title":"Ledger"}]"""
        }
        val stream = FakeStream { emit(READY); awaitCancellation() }
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        val caps = repository.capabilities.first { it.work }

        assertTrue(caps.workLink)
        assertEquals(setOf(70L, 90L), repository.myWork.first { it != null })
        assertEquals(listOf("PAY-7", "PAY-9"), repository.tickets.value.map { it.key })
        assertEquals(1, hub.toolListCalls)
        repository.stop()
    }

    /** No tracker connected: work groups still work, but there is no *My work* to filter by. */
    @Test
    fun a_hub_with_work_and_no_tracker_has_no_my_work() = runTest {
        val hub = FakeHub().apply { toolsJson = """[{"name":"work"}]""" }
        val stream = FakeStream { emit(READY); awaitCancellation() }
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        repository.capabilities.first { it.work }
        repository.status.first { it is ConnectionStatus.Connected }

        assertNull(repository.myWork.value)
        assertFalse(repository.capabilities.value.workLink, "a readonly token is not shown work_link")
        repository.stop()
    }

    /** A hub too old for `tools/list` is the old hub: connected, and nothing work-shaped offered. */
    @Test
    fun a_hub_that_cannot_list_tools_stays_connected_with_no_work() = runTest {
        val hub = FakeHub(sessionsJson = sessionRows(1))
        val stream = FakeStream { emit(READY); awaitCancellation() }
        val repository = repo(hub, stream, backgroundScope)

        repository.start()
        repository.status.first { it is ConnectionStatus.Connected }
        while (hub.toolListCalls == 0) yield()

        assertFalse(repository.capabilities.value.work)
        assertEquals(listOf(1L), repository.sessions.value.map { it.id })
        repository.stop()
    }

    @Test
    fun an_action_the_hub_refused_stays_hidden_until_the_next_connection() = runTest {
        val hub = FakeHub().apply { toolsJson = """[{"name":"work"},{"name":"work_link"}]""" }
        val drop = CompletableDeferred<Unit>()
        val stream = FakeStream { attempt ->
            emit(READY)
            if (attempt == 1) {
                drop.await()
                throw IllegalStateException("the connection dropped")
            }
            awaitCancellation()
        }
        val repository = FleetRepository(hub.client, stream, backgroundScope, backoff = { kotlin.time.Duration.ZERO })

        repository.start()
        repository.capabilities.first { it.workLink }
        repository.actionMissing("work_link", "confirm")
        assertFalse(repository.capabilities.value.has("work_link", "confirm"))

        drop.complete(Unit)
        repository.capabilities.first { it.has("work_link", "confirm") }
        repository.stop()
    }
}
