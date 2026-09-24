package dev.claudefleet.mobile.net

import dev.claudefleet.mobile.data.SNAPSHOT_EVENT_KINDS
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.charsets.TooLongLineException
import io.ktor.utils.io.readLineStrict
import kotlinx.io.EOFException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

/**
 * How much of one frame's joined `data:` the reader will hold, in UTF-16 code
 * units — which is what a `StringBuilder` actually costs, so it is what the
 * ceiling counts.
 *
 * **Why there is a ceiling at all.** SSE ends a frame with a blank line, and
 * nothing on the wire guarantees one ever arrives. A hub wedged mid-write, a
 * proxy that buffers and dies, or anything at all writing into a LAN `http`
 * stream produces `data:` lines that keep coming, and the reader joins them
 * into one buffer. Unbounded, the phone's memory is whatever the other end
 * feels like sending — and on a phone that is not a slow app, it is the process
 * being killed while an agent waits for an answer. The rest of this file
 * already bounds *time* for the same reason ([EVENTS_IDLE_TIMEOUT_MS],
 * [HUB_CALL_TIMEOUT_MS]); this bounds bytes.
 *
 * **Why this size.** The largest thing this route carries is one serialized
 * store row — a `SessionRow` with a long `current_activity` is a few kilobytes
 * — so 512 Ki is about two orders of magnitude of headroom. Being generous
 * costs nothing: the limit is not a guess at the largest legitimate frame, it
 * is the point past which no legitimate hub can be talking, and overshooting it
 * costs one reconnect.
 */
internal const val MAX_SSE_FRAME_CHARS: Int = 512 * 1024

/**
 * How long one line on `/events` may be, in bytes.
 *
 * A separate ceiling from [MAX_SSE_FRAME_CHARS] because it binds *earlier* and
 * on a different failure. The frame reader never sees a byte until a line ends,
 * so a stream that simply never sends `\n` is buffered by the line reader
 * underneath it and the frame ceiling is never consulted. Ktor's `readLine` has
 * no limit parameter at all; `readLineStrict` is the one that takes a ceiling,
 * and reaching for the wrong one is exactly how this goes unnoticed — the code
 * reads correctly and the limit is simply absent.
 *
 * Smaller than the frame ceiling on purpose: one line is one `data:` field,
 * and a frame may legitimately be several of them.
 */
internal const val MAX_SSE_LINE_BYTES: Int = 256 * 1024

/**
 * What an overrunning frame is called on the banner. See [HubError.TooLarge].
 *
 * An oversized frame is deliberately *not* treated like the unparseable frames
 * this reader tolerates. Those are dropped — "a malformed frame is not worth
 * tearing a live connection down for" — because a captive portal's HTML is
 * transient and the next frame is fine. A frame with no end is not that: the
 * stream is no longer carrying what the app thinks it is carrying, and the
 * honest recovery is the one
 * [dev.claudefleet.mobile.data.FleetRepository.follow] already implements for
 * every other dropped connection — back off, reconnect, and let `ready` force
 * the refetch that repairs whatever the abandoned frame would have said.
 */
internal const val SSE_FRAME = "an event frame"

/** What an overrunning line is called on the banner. See [HubError.TooLarge]. */
internal const val SSE_LINE = "a line on the event stream"

/**
 * One server-sent-event frame: the `event:` name and the joined `data:` lines.
 *
 * Both halves are kept. The reader this replaced took a finished response body,
 * returned the first frame only and threw the name away, which is exactly the
 * two things a change stream needs — and, as it turned out, one thing a
 * *request* needs too, since the first frame is not necessarily the reply.
 */
internal data class SseFrame(
    val event: String?,
    val data: String,
    /**
     * The frame's `id:` — `<generation>-<seq>` on a hub row frame — which a
     * reconnect sends back as `Last-Event-ID`. Per frame, deliberately: the
     * SSE rule that an id carries over to later frames is the browser's
     * bookkeeping, and here the app keeps its own (see
     * [dev.claudefleet.mobile.data.FleetRepository]).
     */
    val id: String? = null,
)

/**
 * The streaming half of SSE framing: fed one line at a time, it hands back a
 * frame when the blank line that ends one arrives.
 *
 * Deliberately not a parser over a whole string. `GET /events` is a body that
 * never ends on a healthy hub, so frames have to surface as they land. The
 * rules are the SSE ones, and they are the rules `axum::response::sse` writes
 * to: `:` starts a comment (the hub's 15-second heartbeat is a bare one), one
 * space after the field colon is framing rather than value, several `data:`
 * lines rejoin with newlines, and a frame carrying no data at all is not
 * dispatched.
 */
internal class SseFrameReader {
    private var event: String? = null
    private var id: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /**
     * The frame [line] completed, or null if it did not complete one.
     *
     * @throws HubError.TooLarge if this line would take the frame past
     *   [MAX_SSE_FRAME_CHARS]. The buffer is cleared first, so the reader is
     *   usable again and the refused frame cannot bleed into the next one.
     */
    fun accept(line: String): SseFrame? {
        // A CRLF proxy leaves the carriage return on a line the reader split on
        // '\n'; it is framing, not payload, and a stray \r inside JSON is the
        // kind of thing that fails much later and much less clearly.
        val text = line.trimEnd('\r')
        if (text.isEmpty()) return dispatch()
        if (text.startsWith(":")) return null
        val colon = text.indexOf(':')
        val field = if (colon < 0) text else text.substring(0, colon)
        val value = when {
            colon < 0 -> ""
            text.length > colon + 1 && text[colon + 1] == ' ' -> text.substring(colon + 2)
            else -> text.substring(colon + 1)
        }
        when (field) {
            "event" -> event = value.ifEmpty { null }
            "id" -> id = value.ifEmpty { null }
            "data" -> {
                // Before appending, not after: the point is never to hold the
                // oversized string, so a check that runs once it is already in
                // the buffer would be a report rather than a limit.
                val joined = if (hasData) 1 else 0
                if (data.length + joined + value.length > MAX_SSE_FRAME_CHARS) {
                    reset()
                    throw HubError.TooLarge(SSE_FRAME, MAX_SSE_FRAME_CHARS)
                }
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            // `retry:` and anything the hub grows later: skipped, and
            // pointedly not treated as the end of the frame.
            else -> Unit
        }
        return null
    }

    /**
     * The frame left buffered when the input ran out, for a **complete response
     * body** that ended without its terminating blank line.
     *
     * The live stream must not call this: there, a partial frame means the
     * connection was cut mid-payload, and half a JSON object is not a fact.
     */
    fun flush(): SseFrame? = dispatch()

    private fun dispatch(): SseFrame? {
        if (!hasData) {
            reset()
            return null
        }
        val frame = SseFrame(event, data.toString(), id)
        reset()
        return frame
    }

    private fun reset() {
        event = null
        id = null
        data.clear()
        hasData = false
    }
}

/** What one frame of `GET /events` means. */
sealed interface HubEvent {
    /**
     * The first frame of every stream: the hub's version and the kinds this
     * subscription will actually carry. It is also the signal to resync — the
     * snapshot may have missed anything that happened while the app was away.
     *
     * [contract] is the hub's wire-contract revision, or null when it names
     * none at all (every hub released before the mechanism existed) — see
     * [contractVerdict], which is what turns this into a trust decision.
     * [dev.claudefleet.mobile.data.FleetRepository.follow] is where that
     * decision is made; this class only carries the raw number.
     */
    data class Ready(
        val version: String?,
        val kinds: List<String>,
        val contract: Int? = null,
        /**
         * The hub's own unix second, as it stamped this frame. Null from a hub
         * that does not send one.
         *
         * Every relative time this app draws — "2 m", "idle 3 h", the whole
         * reason the list is worth opening — is `hubTimestamp - deviceClock`.
         * A device clock that is wrong makes all of them wrong together, with
         * nothing on screen to say so: a phone a few minutes behind shows
         * "just now" for the entire fleet, and one ahead shows a session that
         * is working as hours idle, which is a reading somebody acts on.
         */
        val now: Long? = null,
        /**
         * Whether the hub honoured the `Last-Event-ID` this connection was
         * opened with and will replay what was missed. `false` — the gap was
         * longer than its history, or the hub restarted — and null, from a
         * hub that cannot resume at all, both mean re-list.
         */
        val resumed: Boolean? = null,
    ) : HubEvent

    /**
     * The hub's subscriber ring overflowed and [skipped] events were lost. The
     * hub closes the stream right after. The picture now has a hole in it, so
     * the only honest recovery is a full refetch.
     */
    data class Lagged(val skipped: Long) : HubEvent

    /** A row change: `session:updated`, `host:probed`, and the rest. */
    data class Row(
        val name: String,
        val payload: JsonElement,
        /** The frame's `id:`, for resuming after it; null from a hub that sends none. */
        val id: String? = null,
    ) : HubEvent
}

/**
 * Give a frame its meaning. Pure, and separately tested from the transport.
 *
 * Returns null for anything that cannot be acted on — an unnamed frame, or one
 * whose data is not a JSON **object**. A malformed frame is not worth tearing a
 * live connection down for.
 *
 * The object check is not pedantry. Every payload this route sends is a
 * serialized struct (`RowChange::payload()`), and `parseToJsonElement` is
 * happier than it looks: a bare `<html>` from a captive portal or a proxy comes
 * back as a perfectly good JSON literal, which would then have sailed through
 * as a `ready` frame naming no version.
 */
internal fun frameToEvent(frame: SseFrame): HubEvent? {
    val name = frame.event ?: return null
    val fields = try {
        parseWire(frame.data, SSE_FRAME) as? JsonObject ?: return null
    } catch (e: HubError) {
        // Dropping an unparseable frame is deliberate policy — see this
        // function's KDoc, and the captive portal it was written for. A frame
        // refused for its *depth* is not that: it is the same class of fault as
        // one refused for its size, and gets the same recovery, a reconnect
        // and a `ready` resync. Returning null here would leave the row quietly
        // stale instead, for as long as the connection lasted.
        throw e
    } catch (_: Exception) {
        return null
    }
    return when (name) {
        READY -> HubEvent.Ready(
            version = fields.text("version"),
            kinds = (fields["kinds"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty(),
            // A key that is absent stays null — every hub released before the
            // contract mechanism sends none, and null is what
            // [contractVerdict] trusts. A key that is PRESENT and unreadable
            // is the opposite fact and must not collapse into the same
            // value, so it becomes [UNREADABLE_CONTRACT] and is refused.
            contract = fields["contract"]?.let {
                (it as? JsonPrimitive)?.content?.toIntOrNull() ?: UNREADABLE_CONTRACT
            },
            // Unreadable and absent are the same answer here, unlike
            // `contract` above: both mean "no usable reading", and the
            // fallback for both is the device's own clock.
            now = (fields["now"] as? JsonPrimitive)?.content?.toLongOrNull(),
            resumed = (fields["resumed"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
        )
        LAGGED -> HubEvent.Lagged(
            fields["skipped"]?.let { runCatching { (it as JsonPrimitive).long }.getOrNull() } ?: 0L,
        )
        else -> HubEvent.Row(name, fields, frame.id)
    }
}

private const val READY = "ready"
private const val LAGGED = "lagged"

private fun JsonObject?.text(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

/**
 * Every frame in a **complete** response body, in order and with its name.
 *
 * The request-scoped counterpart to the live reader above, sharing the same
 * framing so there is one implementation of it. The difference is the tail: a
 * response body that ends without its terminating blank line still carries a
 * whole frame, where the same thing on a live stream means a cut connection.
 */
internal fun sseFrames(raw: String): List<SseFrame> {
    val reader = SseFrameReader()
    val frames = raw.lineSequence().mapNotNull(reader::accept).toMutableList()
    reader.flush()?.let(frames::add)
    return frames
}

/**
 * A subscription to one hub's change stream.
 *
 * An interface so the repository's reconnect policy can be tested without a
 * socket, and so a screen can be driven from a recording.
 */
interface EventStream {
    /**
     * Open one connection. The flow ends when the hub closes the stream and
     * fails with a [HubError] when the hub refuses or cannot be reached;
     * reconnecting is the caller's business, not this flow's.
     *
     * [lastEventId] is the `id:` of the last frame the caller applied; the hub
     * replays what came after it and says so in `ready` (`resumed`). Null
     * asks for no replay.
     */
    fun connect(lastEventId: String? = null): Flow<HubEvent>
}

/**
 * How long `/events` may go without receiving a byte before the connection is
 * presumed dead.
 *
 * The hub writes a comment-only heartbeat (`:`) every 15 s, which this reader
 * discards without counting it as a frame; 45 s tolerates two missed
 * heartbeats before giving up, so an ordinary GC pause or a slow network hop
 * does not flap the connection, but a genuinely half-open TCP stream — one
 * where the OS still thinks the socket is up but nothing is arriving — is
 * caught within three heartbeat intervals rather than never. Nothing else
 * detects this: there is no OkHttp `pingInterval`, and the request's own
 * deadline is infinite because a live stream has no natural end.
 *
 * The timeout surfaces the same way any other dropped connection does: the
 * engine throws, [HubEventStream.connect]'s catch-all wraps it in
 * [HubError.Transport], and [dev.claudefleet.mobile.data.FleetRepository.follow]
 * reconnects with its existing backoff.
 */
internal const val EVENTS_IDLE_TIMEOUT_MS = 45_000L

/**
 * `GET /events` over HTTP, frame by frame.
 *
 * No `Logging` plugin is installed here and none may be: this request carries
 * the client's bearer token, and a Ktor logger at `HEADERS` or above would put
 * it in a log line.
 */
class HubEventStream(
    private val http: HttpClient,
    base: String,
    private val token: String? = null,
    /**
     * The `?kinds=` filter. Defaults to `SNAPSHOT_EVENT_KINDS` itself rather
     * than to a second copy of its contents, which is what this was: two
     * literals kept in step by a test on each side is a convention, not a
     * mechanism. Subscribing to more kinds than the snapshot applies
     * spends a phone's radio on frames that get dropped, and to fewer leaves
     * rows quietly stale — and neither shows up until someone edits a list.
     */
    private val kinds: List<String> = SNAPSHOT_EVENT_KINDS,
) : EventStream {

    /** The hub's base URL, without a trailing slash. */
    val base: String = base.trimEnd('/')

    override fun connect(lastEventId: String?): Flow<HubEvent> = channelFlow {
        val url = buildString {
            append(base).append("/events")
            if (kinds.isNotEmpty()) append("?kinds=").append(kinds.joinToString(","))
        }
        try {
            http.prepareGet(url) {
                header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                if (lastEventId != null) header("Last-Event-ID", lastEventId)
                // A live stream has no natural end, so the request itself gets
                // no deadline — the client-wide default from `withHubTimeouts()`
                // would otherwise tear this down the first time the hub goes
                // quiet for longer than a call is normally allowed to take.
                // The socket timeout stays bounded, though: it is the only
                // thing that notices a connection gone half-open (a dead TCP
                // stream nothing else here detects — see [EVENTS_IDLE_TIMEOUT_MS]).
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = EVENTS_IDLE_TIMEOUT_MS
                }
            }.execute { response ->
                val status = response.status.value
                if (status !in 200..299) throwForStatus(status, response.bodyAsText(), base, token)
                val body = response.bodyAsChannel()
                val reader = SseFrameReader()
                while (true) {
                    // `readLineStrict`, not `readLine`: the latter takes no
                    // limit at all and will assemble a line as long as whatever
                    // is on the other end cares to send. See [MAX_SSE_LINE_BYTES].
                    //
                    // Its two failures are deliberately NOT treated alike.
                    // Collapsing them would have changed what a cut connection
                    // means as a side effect of adding a size limit, which is
                    // the sort of thing that goes unnoticed because both
                    // outcomes happen to reconnect.
                    val line = try {
                        body.readLineStrict(MAX_SSE_LINE_BYTES.toLong())
                    } catch (_: TooLongLineException) {
                        // One line past the ceiling: a fault. Nothing that
                        // belongs on this route is remotely this long.
                        throw HubError.TooLarge(SSE_LINE, MAX_SSE_LINE_BYTES)
                    } catch (_: EOFException) {
                        // The body ended part-way through a line — the stream
                        // was cut, not closed. Every complete frame before it
                        // has already been sent; the half-line is dropped for
                        // the same reason the half-frame below is, and the flow
                        // ends the way a clean close ends it, leaving `follow()`
                        // to reconnect and `ready` to refetch. That is what
                        // `readLine` did for free, and it is kept on purpose —
                        // `a_body_cut_off_mid_frame_yields_only_the_complete_frames`
                        // is the test that says so.
                        break
                    } ?: break
                    val frame = reader.accept(line) ?: continue
                    frameToEvent(frame)?.let { send(it) }
                }
                // A frame the connection was cut in the middle of is dropped:
                // half a JSON payload is not a fact, and the reconnect refetches.
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HubError) {
            throw e
        } catch (t: Throwable) {
            throw HubError.Transport(t)
        }
    }
}

/**
 * Turn a response status into the one closed set the app branches on.
 *
 * Shared by [HubClient] and [HubEventStream] so that a 401 on the stream and a
 * 401 on a tool call cannot come to mean different things — and so that the
 * scrubbing below happens in one place rather than at each call site. [secret]
 * is this client's bearer token, which is what a proxy's error page is liable
 * to quote back at us.
 */
internal fun throwForStatus(status: Int, body: String, hub: String, vararg secrets: String?) {
    when {
        // The 401 body does not come in at all: nothing reads it, and it is the
        // single most likely place for the token to be echoed. See [Unauthorized].
        status == 401 -> throw HubError.Unauthorized()
        status == 403 -> throw HubError.Forbidden(redacted(body, *secrets), hub)
        status in 200..299 -> Unit
        else -> throw HubError.Http(status, redacted(body, *secrets))
    }
}
