package dev.claudefleet.mobile.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val HUB = "https://fleet.example.com"
private const val TOKEN = "clt_5f3a9c1e7b2d4a86"

/**
 * What the app will read off the wire, and what it will not.
 *
 * Everything else in this repository treats the hub as trusted — you paired
 * with it — and that is the right default for *what it says*. It is the wrong
 * default for *how much it says*. Between the phone and the hub sit a reverse
 * proxy, whatever the operator put in front of it, and on a LAN hub a plain
 * `http` connection that anything on the network can write into. None of those
 * is the hub, and none of them is bounded by anything the hub decided.
 *
 * So the rule here is the one already applied to time — `HUB_CALL_TIMEOUT_MS`,
 * `EVENTS_IDLE_TIMEOUT_MS`, `MAX_RECONNECT_DELAY` all exist because a wait with
 * no ceiling is a hang — applied to bytes. A read with no ceiling is a phone
 * that stops, and on a phone the process is killed rather than slowed: an
 * `OutOfMemoryError` from a 2 GB `data:` line is not a banner someone can
 * dismiss, it is the app disappearing while an agent waits for an answer.
 *
 * Each limit below fails the *connection*, not the app: a `HubError` is what
 * every one of these becomes, and `FleetRepository.follow` already knows how to
 * reconnect with backoff and resync on `ready`. That is why the ceilings can be
 * set where a legitimate hub will never reach them without having to be right
 * about the largest reasonable payload — being wrong costs a reconnect.
 */
class WireLimitsTest {

    // ---- the frame reader ----

    /**
     * A frame whose `data:` never ends.
     *
     * SSE terminates a frame with a blank line. Nothing on the wire guarantees
     * one arrives: a hub wedged mid-write, a proxy that buffers and dies, or
     * anything at all injecting into a LAN `http` stream produces `data:` lines
     * that keep coming. The reader joins them into one `StringBuilder`, so
     * without a ceiling the phone's memory is whatever the other end feels like
     * sending.
     */
    @Test
    fun a_frame_that_never_terminates_is_refused_rather_than_buffered() {
        val reader = SseFrameReader()
        reader.accept("event: session:updated")

        val line = "data: " + "x".repeat(4096)
        val refusal = assertFailsWith<HubError.TooLarge> {
            // Far more than the cap, and far less than the memory an unbounded
            // reader would have taken by the time anyone noticed.
            repeat(MAX_SSE_FRAME_CHARS / 4096 + 8) { reader.accept(line) }
        }

        assertEquals(MAX_SSE_FRAME_CHARS, refusal.limit, "the refusal names the ceiling it passed")
        assertEquals(SSE_FRAME, refusal.what, "…and what it was that overran")
    }

    /**
     * Exactly at the ceiling, and exactly one past it.
     *
     * The `>` was mutable to `>=` with the suite green: the test below uses
     * `MAX - 16`, so nothing ever sat on the edge, and a ceiling that refuses
     * *at* its stated value rather than beyond it would have dropped a frame
     * the documentation says is allowed — and dropped the connection with it,
     * since an oversized frame is a fault rather than a skip.
     */
    @Test
    fun the_frame_ceiling_is_exactly_where_it_says_it_is() {
        val atLimit = SseFrameReader()
        atLimit.accept("event: session:updated")
        val exact = "y".repeat(MAX_SSE_FRAME_CHARS)
        atLimit.accept("data: $exact")
        assertEquals(exact, atLimit.accept("")?.data, "a frame of exactly the ceiling is allowed")

        val oneOver = SseFrameReader()
        oneOver.accept("event: session:updated")
        assertFailsWith<HubError.TooLarge>("one character past it is not") {
            oneOver.accept("data: " + "y".repeat(MAX_SSE_FRAME_CHARS + 1))
        }
    }

    /** A frame right up against the ceiling is still delivered. */
    @Test
    fun a_large_but_bounded_frame_still_arrives() {
        val reader = SseFrameReader()
        reader.accept("event: session:updated")
        val payload = "y".repeat(MAX_SSE_FRAME_CHARS - 16)
        reader.accept("data: $payload")

        val frame = reader.accept("")

        assertEquals(payload, frame?.data, "the ceiling must not cut a legitimate frame short")
    }

    /**
     * The ceiling is per frame, not per connection.
     *
     * A healthy stream runs for hours and carries thousands of frames. If the
     * count were cumulative the connection would die on a timer, which is the
     * opposite of the intent — and is the kind of bug a test that only ever
     * sends one frame does not see.
     */
    @Test
    fun the_ceiling_resets_with_each_frame() {
        val reader = SseFrameReader()
        val chunk = "z".repeat(MAX_SSE_FRAME_CHARS / 2)

        repeat(8) {
            reader.accept("event: session:updated")
            reader.accept("data: $chunk")
            val frame = reader.accept("")
            assertEquals(chunk, frame?.data)
        }
    }

    /**
     * A frame the reader refused leaves nothing behind.
     *
     * The reader is reused for the life of the connection in the request-scoped
     * case, and a refusal that kept the half-frame in its buffer would hand the
     * next frame the previous one's bytes glued to the front — the same class of
     * mistake as a reused luminance array keeping the last camera frame.
     */
    @Test
    fun a_refused_frame_does_not_bleed_into_the_next_one() {
        val reader = SseFrameReader()
        reader.accept("event: session:updated")
        assertFailsWith<HubError.TooLarge> {
            val line = "data: " + "x".repeat(4096)
            repeat(MAX_SSE_FRAME_CHARS / 4096 + 8) { reader.accept(line) }
        }

        reader.accept("event: host:probed")
        reader.accept("""data: {"alias":"local"}""")
        val frame = reader.accept("")

        assertEquals("host:probed", frame?.event)
        assertEquals("""{"alias":"local"}""", frame?.data)
    }

    // ---- the live stream ----

    /**
     * An oversized frame on `/events` becomes an ordinary dropped connection.
     *
     * Not a crash and not a silent skip: `FleetRepository.follow` already
     * reconnects on a [HubError] with growing backoff, and the `ready` frame on
     * the way back in forces the full refetch that repairs whatever the dropped
     * frame would have carried. The recovery already existed; this just routes
     * into it.
     */
    @Test
    fun an_oversized_frame_on_the_stream_fails_as_a_hub_error() = runTest {
        val flood = buildString {
            append("event: session:updated\n")
            repeat(MAX_SSE_FRAME_CHARS / 4096 + 8) { append("data: ").append("x".repeat(4096)).append('\n') }
        }
        val stream = HubEventStream(
            HttpClient(
                MockEngine { respond(flood, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) },
            ),
            HUB,
            TOKEN,
        )

        val refusal = assertFailsWith<HubError.TooLarge> { collectAll(stream) }
        assertEquals(SSE_FRAME, refusal.what)
    }

    /**
     * A single line with no newline in it at all.
     *
     * Distinct from the frame ceiling above and reachable before it: the frame
     * reader never sees a byte until a line ends, so a stream that sends no
     * `\n` is buffered by the *line* reader underneath it. Ktor's `readLine`
     * has no limit parameter — `readLineStrict` is the one that takes a ceiling,
     * and using the wrong one is the whole of this bug.
     */
    @Test
    fun a_line_that_never_ends_is_refused_rather_than_buffered() = runTest {
        val noNewlineEver = "data: " + "x".repeat(MAX_SSE_LINE_BYTES + 8192)
        val stream = HubEventStream(
            HttpClient(
                MockEngine {
                    respond(noNewlineEver, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                },
            ),
            HUB,
            TOKEN,
        )

        val refusal = assertFailsWith<HubError.TooLarge> { collectAll(stream) }
        assertEquals(SSE_LINE, refusal.what, "the line reader is what must have refused, not the frame reader")
        assertEquals(MAX_SSE_LINE_BYTES, refusal.limit)
    }

    // ---- the tool call ----

    /**
     * A tool reply larger than any the hub can produce.
     *
     * `session_conversation` is the biggest thing the app asks for and the hub
     * bounds it server-side — a rolling tail of roughly a megabyte, which is why
     * `Conversation.appending` has to deal with turns sliding off the top. The
     * ceiling here sits well above that, so it cannot be reached by a hub doing
     * its job, and well below the point where a phone is in trouble.
     *
     * A `HubError` rather than an `OutOfMemoryError`: the screen says the call
     * failed and keeps the conversation it already had, which is what every
     * other failed read does.
     */
    @Test
    fun a_reply_larger_than_the_ceiling_fails_as_a_hub_error() = runTest {
        val client = HubClient(
            HttpClient(
                MockEngine {
                    respond(
                        "x".repeat(MAX_RESPONSE_BYTES + 4096),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            ),
            HUB,
            TOKEN,
        )

        // `HubError` alone would NOT do here, and finding that out is what a
        // mutation is for: eight megabytes of `x` is also unparseable JSON, so
        // lifting the ceiling leaves the call failing anyway — as
        // `Transport("neither a result nor an error")` — and an assertion that
        // only asked for a `HubError` stayed green while the limit it was
        // written to protect had been deleted.
        val refusal = assertFailsWith<HubError.TooLarge> { client.call("list_sessions") { it } }
        assertEquals(HUB_REPLY, refusal.what)
        assertEquals(MAX_RESPONSE_BYTES, refusal.limit)
    }

    /** And a reply that fits is read in full, byte for byte. */
    @Test
    fun a_reply_under_the_ceiling_is_read_whole() = runTest {
        val payload = "n".repeat(64_000)
        val quoted = """[{"id":1,"tmux_name":"$payload"}]"""
        val framed = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":" +
            "[{\"type\":\"text\",\"text\":${escape(quoted)}}]}}\n\n"

        val client = HubClient(
            HttpClient(
                MockEngine {
                    respond(framed, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                },
            ),
            HUB,
            TOKEN,
        )

        val rows = client.listSessions()
        assertEquals(1, rows.size)
        assertEquals(payload, rows[0].tmuxName, "a big-but-legal reply must not be truncated")
    }

    /**
     * The ceiling counts what arrived, not what the sender declared.
     *
     * A `Content-Length` is a promise from whoever wrote the headers, and the
     * body is written by whoever holds the socket. Bounding on the header would
     * bound nothing: this reply carries no length at all — which is what a
     * chunked response looks like, and what the hub's own SSE-framed replies
     * are — so a check that consulted the header would have had nothing to read
     * and would have let the whole thing through.
     */
    @Test
    fun the_ceiling_holds_for_a_reply_that_declares_no_length() = runTest {
        val client = HubClient(
            HttpClient(
                MockEngine {
                    respond(
                        ByteReadChannel(ByteArray(MAX_RESPONSE_BYTES + 4096) { 'x'.code.toByte() }),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            ),
            HUB,
            TOKEN,
        )

        val refusal = assertFailsWith<HubError.TooLarge> { client.call("list_sessions") { it } }
        assertEquals(HUB_REPLY, refusal.what)
    }

    private fun escape(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private suspend fun collectAll(stream: EventStream) {
        val seen = mutableListOf<HubEvent>()
        stream.connect().collect { seen += it }
    }

    @Suppress("unused")
    private fun unusedJsonElement(e: JsonElement) = e
}
