package dev.claudefleet.mobile.log

import dev.claudefleet.mobile.epochMillis
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.concurrent.Volatile

/*
 * The phone's side of `POST /log` (claude-fleet's `service::client_logs`, the
 * desktop's `LogPoller`): every [LOG_POLL_MS] while connected, ask whether
 * the self debug agent wants this app's log; when it does, send the lines of
 * the window it names.
 *
 * - poll `{}` → `204` nothing wanted, `200 {"want":{"id","minutes"}}` wanted;
 * - upload `{"id","lines","app"}` → `204`;
 * - `401` / `403` / `404` → an older hub or a refusal: stop for this run;
 * - anything else, or no answer → try again on the next tick.
 */

/** What one poll did. */
sealed interface LogPoll {
    /** Nothing wanted. */
    data object Idle : LogPoll

    /** Asked for, and sent this many lines. */
    data class Sent(val lines: Int) : LogPoll

    /** The hub has no `/log` or refuses this app: no more polls this run. */
    data object Stop : LogPoll

    /** No answer: the next tick tries again. */
    data object Retry : LogPoll
}

/** What the hub asked for: request [id], the last [minutes]. */
data class LogWant(val id: String, val minutes: Long)

/** One upload's body, and how many lines it carries. */
data class LogUpload(val body: String, val lines: Int)

class LogPoller(
    private val ring: LogRing,
    /** `fleet-mobile <version>`. */
    private val app: String,
    /** `POST /log` with this body: the status and the reply. Throws when the hub cannot be reached. */
    private val post: suspend (String) -> Pair<Int, String>,
    /** This device's token, masked by value in every upload as well as by shape. */
    private val secret: () -> String? = { null },
    private val clock: () -> Long = ::epochMillis,
) {
    /** The hub said stop ([LogPoll.Stop]); nothing more is posted this run. */
    @Volatile
    var stopped: Boolean = false
        private set

    suspend fun pollOnce(): LogPoll {
        if (stopped) return LogPoll.Stop
        val (status, reply) = postOrNull("{}") ?: return LogPoll.Retry
        when (status) {
            204 -> return LogPoll.Idle
            200 -> Unit
            in STOP -> return stop()
            else -> return LogPoll.Retry
        }
        val want = wantOf(reply) ?: return LogPoll.Retry
        val upload = logUpload(want.id, ring.window(want.minutes, clock()), app, secret())
        val (sent, _) = postOrNull(upload.body) ?: return LogPoll.Retry
        return when (sent) {
            in 200..299 -> LogPoll.Sent(upload.lines)
            in STOP -> stop()
            else -> LogPoll.Retry
        }
    }

    /**
     * Poll every [LOG_POLL_MS] while [connected] says the app is connected to
     * its hub, until cancelled or the hub says stop.
     */
    suspend fun run(connected: () -> Boolean) {
        while (!stopped) {
            delay(LOG_POLL_MS)
            if (!connected()) continue
            when (val r = pollOnce()) {
                LogPoll.Stop -> ring.add(LogLevel.Info, "log", "the hub takes no log from this app; not asking again")
                is LogPoll.Sent -> ring.add(LogLevel.Info, "log", "sent ${r.lines} lines to the hub")
                LogPoll.Idle, LogPoll.Retry -> Unit
            }
        }
    }

    private fun stop(): LogPoll {
        stopped = true
        return LogPoll.Stop
    }

    private suspend fun postOrNull(body: String): Pair<Int, String>? = try {
        post(body)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private companion object {
        val STOP = setOf(401, 403, 404)
    }
}

/** How often the app asks, as the desktop does (`client_logs::POLL_EVERY`). */
const val LOG_POLL_MS = 30_000L

/** The hub's body cap is 1 MiB; an upload stays under it with room for the envelope. */
const val LOG_BODY_MAX = 1_000_000

/** A poll's `200` reply, or null when it is not one. */
fun wantOf(reply: String): LogWant? {
    val want = runCatching { (json.parseToJsonElement(reply) as? JsonObject)?.get("want") as? JsonObject }.getOrNull()
        ?: return null
    val id = (want["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
    val minutes = (want["minutes"] as? JsonPrimitive)?.longOrNull ?: return null
    return LogWant(id, minutes.coerceAtLeast(1))
}

/**
 * The upload for request [id]: the last [LOG_MAX_LINES] of [lines], each at
 * most [LOG_LINE_MAX_CHARS], with [secret] masked; the oldest dropped until
 * the body is under [LOG_BODY_MAX] bytes.
 */
fun logUpload(id: String, lines: List<String>, app: String, secret: String? = null): LogUpload {
    var kept = lines.takeLast(LOG_MAX_LINES).map { line ->
        val masked = if (secret.isNullOrBlank()) line else line.replace(secret, LOG_REDACTED, ignoreCase = true)
        masked.take(LOG_LINE_MAX_CHARS)
    }
    fun encode(lines: List<String>): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("id", id)
            putJsonArray("lines") { lines.forEach { add(it) } }
            put("app", app)
        },
    )
    var body = encode(kept)
    while (kept.isNotEmpty() && utf8Size(body) >= LOG_BODY_MAX) {
        kept = kept.drop(maxOf(1, kept.size / 10))
        body = encode(kept)
    }
    return LogUpload(body, kept.size)
}
