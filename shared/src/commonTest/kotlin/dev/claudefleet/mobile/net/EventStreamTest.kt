package dev.claudefleet.mobile.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HUB = "https://fleet.example.com"
private const val TOKEN = "clt_5f3a9c1e7b2d4a86"

private class Recorder {
    val requests = mutableListOf<HttpRequestData>()
}

private fun streamOf(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    recorder: Recorder? = null,
    token: String? = TOKEN,
): EventStream {
    val engine = MockEngine { request ->
        recorder?.requests?.add(request)
        respond(body, status, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }
    return HubEventStream(HttpClient(engine), HUB, token)
}

class EventStreamTest {

    /** The whole point: name and payload, for every frame the hub sent. */
    @Test
    fun every_frame_in_the_body_becomes_an_event() = runTest {
        val body = buildString {
            append("event: ready\ndata: {\"version\":\"0.9.3\",\"now\":1,\"kinds\":[\"session\"]}\n\n")
            append(":\n\n")
            append("event: session:updated\ndata: {\"id\":7,\"tmux_name\":\"s7\"}\n\n")
            append("event: session:killed\ndata: {\"id\":7}\n\n")
        }

        val events = streamOf(body).connect().toList()

        assertEquals(3, events.size, "the keep-alive comment is not an event")
        assertEquals(HubEvent.Ready("0.9.3", listOf("session"), now = 1L), events[0])
        assertEquals("session:updated", (events[1] as HubEvent.Row).name)
        assertEquals("session:killed", (events[2] as HubEvent.Row).name)
    }

    /**
     * The `ready` frame's `contract` field, read straight from the wire
     * through [frameToEvent] rather than the live `connect()` path — it is
     * `internal`, and [SseFrameReaderTest] already tests it this way. A hub
     * that names no `contract` at all (every hub released before the
     * mechanism existed) must decode to `null`, not `0` — that distinction is
     * [HubContractVerdictTest]'s to make, not this parser's.
     */
    @Test
    fun ready_carries_the_contract_revision_when_the_hub_names_one() {
        val ready = frameToEvent(SseFrame("ready", """{"version":"0.2.31","kinds":["session"],"contract":7}"""))
        assertEquals(HubEvent.Ready(version = "0.2.31", kinds = listOf("session"), contract = 7), ready)

        val old = frameToEvent(SseFrame("ready", """{"version":"0.2.20","kinds":["session"]}"""))
        assertNull((old as HubEvent.Ready).contract)
    }

    /**
     * A `contract` that is PRESENT and unreadable is not the same fact as one
     * that is absent, and must not decode to the same `null`.
     *
     * `toIntOrNull()` alone collapsed both into `null`, which is the one value
     * [contractVerdict] trusts unconditionally — so `"contract": "next"`, a
     * float, a bool, an object, or a `u32` past `Int.MAX_VALUE` (which the
     * desktop can send and this app cannot hold) all read as "a hub from
     * before contracts existed" and were trusted outright. They become
     * [UNREADABLE_CONTRACT], which classifies as `AppTooOld`.
     */
    @Test
    fun a_contract_that_cannot_be_read_is_refused_rather_than_trusted() {
        val unreadable = listOf(
            """"x"""",
            "1.5",
            "true",
            "{}",
            // Past Int.MAX_VALUE: a perfectly good u32 on the desktop's side.
            "3000000000",
        )

        for (value in unreadable) {
            val ready = frameToEvent(SseFrame("ready", """{"version":"0.9.9","contract":$value}"""))

            assertEquals(UNREADABLE_CONTRACT, (ready as HubEvent.Ready).contract, "contract:$value")
            assertEquals(ContractVerdict.AppTooOld(UNREADABLE_CONTRACT), contractVerdict(ready.contract), "contract:$value")
        }
    }

    /** And the absent key still means "a hub from before contracts", which is trusted. */
    @Test
    fun an_absent_contract_is_still_null_and_still_trusted() {
        val ready = frameToEvent(SseFrame("ready", """{"version":"0.2.20"}""")) as HubEvent.Ready

        assertNull(ready.contract)
        assertEquals(ContractVerdict.Ok, contractVerdict(ready.contract))
    }

    @Test
    fun the_lagged_frame_comes_through_as_itself() = runTest {
        val events = streamOf("event: lagged\ndata: {\"skipped\":9}\n\n").connect().toList()

        assertEquals(listOf(HubEvent.Lagged(9)), events)
    }

    /**
     * Frames must surface as they arrive, not when the body ends — an event
     * stream never ends on a healthy hub.
     *
     * The test never closes the channel, so a reader that waited for the body
     * to finish would hang here until `runTest` gave up. That the event arrives
     * at all *is* the assertion.
     */
    @Test
    fun a_frame_is_delivered_before_the_stream_closes() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val engine = MockEngine {
            respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val stream = HubEventStream(HttpClient(engine), HUB, TOKEN)

        val first = CompletableDeferred<HubEvent>()
        val collector = backgroundScope.launch {
            first.complete(stream.connect().first())
        }
        channel.writeString("event: session:killed\ndata: {\"id\":3}\n\n")
        channel.flush()

        val event = first.await()
        collector.cancel()

        assertEquals("session:killed", (event as HubEvent.Row).name)
    }

    @Test
    fun the_request_carries_the_bearer_token_and_asks_only_for_the_kinds_the_app_applies() = runTest {
        val recorder = Recorder()
        streamOf("", recorder = recorder).connect().toList()

        val request = recorder.requests.single()
        assertEquals("Bearer $TOKEN", request.headers[HttpHeaders.Authorization])
        assertEquals("text/event-stream", request.headers[HttpHeaders.Accept])
        assertEquals("/events", request.url.encodedPath)
        assertEquals("session,host,project", request.url.parameters["kinds"])
    }

    @Test
    fun a_client_with_no_token_sends_no_authorization_header() = runTest {
        val recorder = Recorder()
        streamOf("", recorder = recorder, token = null).connect().toList()

        assertEquals(null, recorder.requests.single().headers[HttpHeaders.Authorization])
    }

    /** The repository has to be able to tell a revoked token from a flaky link. */
    @Test
    fun a_401_is_unauthorized_not_a_transport_failure() = runTest {
        val stream = streamOf("", HttpStatusCode.Unauthorized)

        assertFailsWith<HubError.Unauthorized> { stream.connect().toList() }
    }

    @Test
    fun a_403_names_the_hub_the_app_used() = runTest {
        val stream = streamOf("", HttpStatusCode.Forbidden)

        val failure = assertFailsWith<HubError.Forbidden> { stream.connect().toList() }

        assertTrue(failure.message.orEmpty().contains(HUB))
    }

    /** `/events` on a hub with no event source answers 503 and says why. */
    @Test
    fun any_other_status_arrives_as_itself() = runTest {
        val stream = streamOf("events are not enabled on this server", HttpStatusCode.ServiceUnavailable)

        val failure = assertFailsWith<HubError.Http> { stream.connect().toList() }

        assertEquals(503, failure.status)
        assertTrue(failure.body.contains("not enabled"))
    }

    @Test
    fun an_unreachable_hub_is_a_transport_failure() = runTest {
        val engine = MockEngine { throw RuntimeException("connection refused") }
        val stream = HubEventStream(HttpClient(engine), HUB, TOKEN)

        assertFailsWith<HubError.Transport> { stream.connect().toList() }
    }

    /**
     * The one rule the whole task is strictest about. A failure is what gets
     * screenshotted and pasted into a bug report.
     */
    @Test
    fun no_failure_the_stream_raises_repeats_the_token() = runTest {
        val failures = mutableListOf<Throwable>()
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.BadGateway)) {
            failures += assertFailsWith<HubError> { streamOf("", status).connect().toList() }
        }
        val engine = MockEngine { throw RuntimeException("connection refused to $HUB") }
        failures += assertFailsWith<HubError> {
            HubEventStream(HttpClient(engine), HUB, TOKEN).connect().toList()
        }

        for (failure in failures) {
            assertFalse(TOKEN in failure.toString(), "token in toString: $failure")
            assertFalse(TOKEN in failure.message.orEmpty(), "token in message: ${failure.message}")
        }
    }

    /** A trailing partial frame is dropped rather than parsed as truth. */
    @Test
    fun a_body_cut_off_mid_frame_yields_only_the_complete_frames() = runTest {
        val body = "event: session:killed\ndata: {\"id\":1}\n\nevent: session:updated\ndata: {\"id\":2"

        val events = streamOf(body).connect().toList()

        assertEquals(1, events.size)
        assertEquals("session:killed", (events[0] as HubEvent.Row).name)
    }

    /**
     * `/events` has no request deadline — a live stream has no natural end —
     * but it does have a bounded idle-socket timeout: nothing else notices a
     * half-open TCP connection (no OkHttp `pingInterval`, the hub's 15 s
     * keep-alive comment is consumed and discarded by [SseFrameReader]), so
     * without one, a dead stream would report [ConnectionStatus.Connected]
     * forever. Asserting on the resolved [HttpTimeoutConfig] proves both
     * halves deterministically and instantly, with no real-time wait needed to
     * prove either bound applies. `an_ordinary_call_keeps_a_finite_deadline_above_the_keep_alive_interval`
     * in `HubClientTest` covers the other half: this override does not leak
     * into calls that should still time out.
     *
     * `withHubTimeouts()` is applied first, exactly as `AppContainer` applies
     * it before handing the client to [HubEventStream] — so this proves the
     * per-request override on `/events` wins over the client-wide default,
     * not merely that an unconfigured client has no timeout to begin with.
     *
     * `connectTimeoutMillis` is asserted too, on purpose: `/events`' own
     * `timeout {}` block only touches `requestTimeoutMillis` and
     * `socketTimeoutMillis`, leaving `connectTimeoutMillis` unset so it falls
     * through to `withHubTimeouts()`'s client-wide 15 s default — an unbounded
     * *connect* would leave a phone hung dialing a hub that never answers at
     * all, which is a different failure from the one this fix targets. That
     * fallthrough is `HttpTimeout`'s own merge behaviour (`on(Send)` fills
     * only the capability's `null` fields from the plugin's installed
     * defaults, mutating the same `HttpTimeoutConfig` the request carries),
     * not something this file implements — asserting it here is what would
     * catch a later refactor silently breaking that merge.
     */
    @Test
    fun the_events_request_has_no_deadline_but_a_bounded_idle_socket_timeout() = runTest {
        val recorder = Recorder()
        val engine = MockEngine { request ->
            recorder.requests.add(request)
            respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val http = HttpClient(engine).withHubTimeouts()

        HubEventStream(http, HUB, TOKEN).connect().toList()

        val timeout = recorder.requests.single().getCapabilityOrNull(HttpTimeoutCapability)
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, timeout?.requestTimeoutMillis)
        assertEquals(EVENTS_IDLE_TIMEOUT_MS, timeout?.socketTimeoutMillis)
        assertEquals(HUB_CONNECT_TIMEOUT_MS, timeout?.connectTimeoutMillis)
        assertTrue(EVENTS_IDLE_TIMEOUT_MS > 15_000L, "shorter than the hub's own keep-alive would flap a healthy stream")
    }
}
