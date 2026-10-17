package dev.claudefleet.mobile.log

import dev.claudefleet.mobile.net.HubClient
import dev.claudefleet.mobile.net.HubError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2026-10-17T10:15:40.123Z. */
private const val NOW = 1_792_232_140_123L

private const val TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

/** A hub's `/log` as a list of answers, one per post, recording every body. */
private class FakeLogHub(vararg answers: Pair<Int, String>) {
    private val queue = ArrayDeque(answers.toList())
    val bodies = mutableListOf<String>()
    var fail = false

    suspend fun post(body: String): Pair<Int, String> {
        bodies += body
        if (fail) throw HubError.Transport(RuntimeException("offline"))
        return queue.removeFirstOrNull() ?: (204 to "")
    }
}

private fun ringWithAnHour(): LogRing {
    val ring = LogRing(clock = { NOW })
    ring.add(LogLevel.Info, "t", "an hour ago", atMillis = NOW - 60 * 60_000L)
    ring.add(LogLevel.Warn, "t", "five minutes ago", atMillis = NOW - 5 * 60_000L)
    ring.add(LogLevel.Error, "t", "just now", atMillis = NOW)
    return ring
}

private fun poller(ring: LogRing, hub: FakeLogHub) =
    LogPoller(ring, "fleet-mobile 1.2.3", post = hub::post, secret = { TOKEN }, clock = { NOW })

class LogPollerTest {
    @Test
    fun nothing_wanted_is_one_empty_poll() = runTest {
        val hub = FakeLogHub(204 to "")
        assertEquals(LogPoll.Idle, poller(ringWithAnHour(), hub).pollOnce())
        assertEquals(listOf("{}"), hub.bodies)
    }

    @Test
    fun a_want_is_answered_with_the_lines_of_its_window() = runTest {
        val hub = FakeLogHub(200 to """{"want":{"id":"w1","minutes":15}}""", 204 to "")
        assertEquals(LogPoll.Sent(2), poller(ringWithAnHour(), hub).pollOnce())
        assertEquals(2, hub.bodies.size)
        val upload = Json.parseToJsonElement(hub.bodies[1]).jsonObject
        assertEquals("w1", upload["id"]!!.jsonPrimitive.content)
        assertEquals("fleet-mobile 1.2.3", upload["app"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("2026-10-17T10:10:40.123Z  WARN t: five minutes ago", "2026-10-17T10:15:40.123Z  ERROR t: just now"),
            upload["lines"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun a_refusal_or_an_older_hub_stops_the_polls_for_this_run() = runTest {
        for (status in listOf(401, 403, 404)) {
            val hub = FakeLogHub(status to "")
            val p = poller(ringWithAnHour(), hub)
            assertEquals(LogPoll.Stop, p.pollOnce())
            assertTrue(p.stopped)
            assertEquals(LogPoll.Stop, p.pollOnce())
            assertEquals(1, hub.bodies.size, "nothing is posted after a stop ($status)")
        }
        // A refused upload stops too.
        val hub = FakeLogHub(200 to """{"want":{"id":"w1","minutes":5}}""", 403 to "")
        assertEquals(LogPoll.Stop, poller(ringWithAnHour(), hub).pollOnce())
    }

    @Test
    fun no_answer_or_an_odd_one_is_tried_again_next_tick() = runTest {
        val down = FakeLogHub().apply { fail = true }
        val p = poller(ringWithAnHour(), down)
        assertEquals(LogPoll.Retry, p.pollOnce())
        assertFalse(p.stopped)
        assertEquals(LogPoll.Retry, poller(ringWithAnHour(), FakeLogHub(502 to "bad gateway")).pollOnce())
        assertEquals(LogPoll.Retry, poller(ringWithAnHour(), FakeLogHub(200 to "not json")).pollOnce())
        assertNull(wantOf("""{"want":{"minutes":5}}"""))
        assertEquals(LogWant("w", 1), wantOf("""{"want":{"id":"w","minutes":0}}"""))
    }

    @Test
    fun an_upload_masks_this_devices_token_and_fits_the_hubs_caps() {
        val masked = logUpload("w", listOf("echo ${TOKEN.uppercase()} back"), "a", TOKEN)
        assertFalse(TOKEN in masked.body.lowercase(), masked.body)

        val many = List(2500) { "line $it" }
        val capped = logUpload("w", many, "a")
        assertEquals(LOG_MAX_LINES, capped.lines)
        assertTrue("\"line 2499\"" in capped.body && "\"line 499\"" !in capped.body)

        val wide = List(LOG_MAX_LINES) { "y".repeat(LOG_LINE_MAX_CHARS) }
        val fitted = logUpload("w", wide, "a")
        assertTrue(utf8Size(fitted.body) < LOG_BODY_MAX)
        assertTrue(fitted.lines in 1 until LOG_MAX_LINES)
    }

    @Test
    fun the_poller_asks_every_thirty_seconds_only_while_connected() = runTest {
        val hub = FakeLogHub()
        val p = poller(ringWithAnHour(), hub)
        var connected = false
        val job = launch { p.run { connected } }
        advanceTimeBy(LOG_POLL_MS + 1)
        assertEquals(0, hub.bodies.size)
        connected = true
        advanceTimeBy(LOG_POLL_MS)
        runCurrent()
        assertEquals(1, hub.bodies.size)
        advanceTimeBy(LOG_POLL_MS)
        runCurrent()
        assertEquals(2, hub.bodies.size)
        job.cancel()
    }

    @Test
    fun the_client_posts_to_slash_log_with_its_token_and_reads_the_status() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            if ((request.body as TextContent).text == "{}") {
                respond("""{"want":{"id":"w9","minutes":60}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond("", HttpStatusCode.NoContent)
            }
        }
        val client = HubClient(HttpClient(engine), "https://fleet.example.com", "tok-phone")
        val p = LogPoller(ringWithAnHour(), "fleet-mobile 1.2.3", post = { client.postLog(it) }, clock = { NOW })
        assertEquals(LogPoll.Sent(3), p.pollOnce())
        assertEquals(listOf("/log", "/log"), seen.map { it.url.encodedPath })
        assertEquals("Bearer tok-phone", seen[0].headers[HttpHeaders.Authorization])

        // A 401 is this route's "no", not a signed-out device: it comes back as a status.
        val refusing = HubClient(HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) }), "https://fleet.example.com", "tok-phone")
        assertEquals(401, refusing.postLog("{}").first)
    }
}
