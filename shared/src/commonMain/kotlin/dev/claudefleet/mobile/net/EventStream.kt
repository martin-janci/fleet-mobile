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
import io.ktor.utils.io.readLine
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
 * One server-sent-event frame: the `event:` name and the joined `data:` lines.
 *
 * Both halves are kept. The reader this replaced took a finished response body,
 * returned the first frame only and threw the name away, which is exactly the
 * two things a change stream needs — and, as it turned out, one thing a
 * *request* needs too, since the first frame is not necessarily the reply.
 */
internal data class SseFrame(val event: String?, val data: String)

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
    private val data = StringBuilder()
    private var hasData = false

    /** True while a frame has been started but not yet terminated. */
    val partial: Boolean get() = hasData || event != null

    /** The frame [line] completed, or null if it did not complete one. */
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
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            // `id:`, `retry:` and anything the hub grows later: skipped, and
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
        val frame = SseFrame(event, data.toString())
        reset()
        return frame
    }

    private fun reset() {
        event = null
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
     */
    data class Ready(val version: String?, val kinds: List<String>) : HubEvent

    /**
     * The hub's subscriber ring overflowed and [skipped] events were lost. The
     * hub closes the stream right after. The picture now has a hole in it, so
     * the only honest recovery is a full refetch.
     */
    data class Lagged(val skipped: Long) : HubEvent

    /** A row change: `session:updated`, `host:probed`, and the rest. */
    data class Row(val name: String, val payload: JsonElement) : HubEvent
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
        json.parseToJsonElement(frame.data) as? JsonObject ?: return null
    } catch (_: Exception) {
        return null
    }
    return when (name) {
        READY -> HubEvent.Ready(
            version = fields.text("version"),
            kinds = (fields["kinds"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty(),
        )
        LAGGED -> HubEvent.Lagged(
            fields["skipped"]?.let { runCatching { (it as JsonPrimitive).long }.getOrNull() } ?: 0L,
        )
        else -> HubEvent.Row(name, fields)
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
     */
    fun connect(): Flow<HubEvent>
}

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

    override fun connect(): Flow<HubEvent> = channelFlow {
        val url = buildString {
            append(base).append("/events")
            if (kinds.isNotEmpty()) append("?kinds=").append(kinds.joinToString(","))
        }
        try {
            http.prepareGet(url) {
                header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                // A live stream has no natural end, so it gets no timeout: the
                // client-wide default from `withHubTimeouts()` would otherwise
                // tear this down the first time the hub goes quiet for longer
                // than a call is normally allowed to take.
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
            }.execute { response ->
                val status = response.status.value
                if (status !in 200..299) throwForStatus(status, response.bodyAsText(), base, token)
                val body = response.bodyAsChannel()
                val reader = SseFrameReader()
                while (true) {
                    val line = body.readLine() ?: break
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
