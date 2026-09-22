package dev.claudefleet.mobile.net

import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.PairResult
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SendPromptResult
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.WaitResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Talks to one `fleet-hub`.
 *
 * Two shapes, both `POST`:
 *  - `/mcp` — a JSON-RPC `tools/call`, answered SSE-framed (see [call]).
 *  - `/pair` — the one unauthenticated route, which exchanges a pairing code
 *    for this client's own token.
 *
 * The client is deliberately thin: no retry, no caching, no state. Reconnect
 * policy belongs to the event stream and the repository above it.
 */
class HubClient(
    private val http: HttpClient,
    base: String,
    private val token: String? = null,
) {
    /** The hub's base URL, without a trailing slash. */
    val base: String = base.trimEnd('/')

    /**
     * Call a tool and hand its payload to [deserialize].
     *
     * The hub is a *stateless* streamable-HTTP MCP server: every POST is a
     * self-contained exchange with exactly one reply, so the JSON-RPC `id` is
     * a constant and nothing needs correlating. Its responses keep SSE framing
     * (rmcp's `json_response = false`) so that a 15 s keep-alive keeps flowing
     * during a long poll — hence [jsonRpcReply].
     *
     * A tool that fails does *not* come back as a JSON-RPC error: per the MCP
     * spec it is a successful result with `isError: true`, and fleet puts the
     * `E_*` code in `structuredContent`. A genuine JSON-RPC `error` means a
     * protocol failure (unknown tool, malformed arguments). Both become
     * [HubError.Tool].
     */
    suspend fun <T> call(
        tool: String,
        args: JsonObject = JsonObject(emptyMap()),
        deserialize: (JsonElement) -> T,
    ): T {
        val envelope = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", REQUEST_ID)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", tool)
                put("arguments", args)
            }
        }
        val (status, body) = send("$base/mcp", json.encodeToString(JsonObject.serializer(), envelope), authenticated = true)
        throwForStatus(status, body, base, token)

        val reply = jsonRpcReply(body)
        (reply["error"] as? JsonObject)?.let { throw rpcError(it) }
        val result = reply["result"] as? JsonObject
            ?: throw HubError.Transport(IllegalStateException("the hub's reply had neither a result nor an error"))
        if (result["isError"]?.asBooleanOrNull() == true) throw toolError(result)
        // Inside the try, not outside it: [HubError] claims to be the closed set
        // every screen branches on, and a payload that does not fit the model
        // would otherwise throw a raw SerializationException straight past them.
        // `Transport` is the right bucket — it covers a hub that "answered
        // something unintelligible" as well as one that could not be reached.
        return try {
            deserialize(payloadOf(result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HubError) {
            throw e
        } catch (t: Throwable) {
            throw HubError.Transport(t)
        }
    }

    /**
     * Redeem a pairing code for this client's own token.
     *
     * No `Authorization` header: `/pair` is how a client gets its *first*
     * credential, so it cannot be asked to present one. What stands in for the
     * token is the code — single-use, minutes long, and rate-limited per
     * address by the hub.
     */
    suspend fun pair(code: String): PairResult {
        val body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("code", code) },
        )
        val (status, text) = send("$base/pair", body, authenticated = false)
        // The CODE is passed as well as the token, and on this path it is the
        // only one that exists: `AppSession.pair` builds the client with no
        // token, so scrubbing `token` alone scrubbed nothing at all and
        // `redacted` merely capped the body.
        //
        // A pairing code is a credential — it mints a token — and a reverse
        // proxy or WAF that answers `POST /pair` with an error page echoing the
        // request body would otherwise put a live one verbatim into
        // `HubError.Http.body`, through `explain()`, onto a banner someone reads
        // and screenshots. That undoes the whole reason the code travels in the
        // URL *fragment*, which no browser sends and no access log records.
        throwForStatus(status, text, base, token, code)
        return try {
            requireShallow(text, PAIR_REPLY)
            json.decodeFromString(PairResult.serializer(), text)
        } catch (e: HubError) {
            throw e
        } catch (e: Exception) {
            throw HubError.Transport(e)
        }
    }

    /**
     * Every session across the fleet.
     *
     * `summary=false` on purpose: the default slim row drops `friendly_name`,
     * `current_activity` and `last_activity_at`, which are exactly what the
     * list draws.
     */
    suspend fun listSessions(): List<SessionRow> =
        call("list_sessions", buildJsonObject { put("summary", false) }) {
            json.decodeFromJsonElement(ListSerializer(SessionRow.serializer()), it)
        }

    suspend fun listHosts(): List<HostRow> =
        call("list_hosts") { json.decodeFromJsonElement(ListSerializer(HostRow.serializer()), it) }

    /**
     * Every project the hub knows about — what turns a session row's
     * `project_id` into a heading a person recognises.
     *
     * The hub's default `summary=true` is exactly right here: the full form
     * nests every worktree's path under every project, which is a lot of wire
     * for a list this only needs `owner`/`repo` from.
     */
    suspend fun listProjects(): List<ProjectRow> =
        call("list_projects") {
            json.decodeFromJsonElement(ListSerializer(ProjectRow.serializer()), it)
        }

    /** A session's recent exchange. [turns] left null keeps the hub's default of 10. */
    suspend fun conversation(sessionId: Long, turns: Int? = null): Conversation =
        call(
            "session_conversation",
            buildJsonObject {
                put("session_id", sessionId)
                if (turns != null) put("turns", turns)
            },
        ) { json.decodeFromJsonElement(Conversation.serializer(), it) }

    /** Deliver [text] to a session's REPL and submit it. */
    suspend fun sendPrompt(sessionId: Long, text: String): SendPromptResult =
        call(
            "send_prompt",
            buildJsonObject {
                put("session_id", sessionId)
                put("prompt", text)
            },
        ) { json.decodeFromJsonElement(SendPromptResult.serializer(), it) }

    /**
     * Press one key instead of typing text — `send_prompt` with `keys` and an
     * empty `prompt`. [key] is one of `"Enter"`, `"Escape"`, `"C-c"`, the set
     * the hub's guard accepts.
     */
    suspend fun sendKeys(sessionId: Long, key: String): SendPromptResult =
        call(
            "send_prompt",
            buildJsonObject {
                put("session_id", sessionId)
                put("prompt", "")
                put("keys", key)
            },
        ) { json.decodeFromJsonElement(SendPromptResult.serializer(), it) }

    /**
     * The visible tmux pane, capped to [maxLines] lines.
     *
     * `capture_session` answers plain text, not JSON, so [payloadOf]'s own
     * fallback — a text block that fails to parse as JSON is handed over as a
     * `JsonPrimitive` — is what carries the pane text here.
     */
    suspend fun capture(sessionId: Long, maxLines: Int = 40): String =
        call(
            "capture_session",
            buildJsonObject {
                put("session_id", sessionId)
                put("max_lines", maxLines)
            },
        ) { (it as JsonPrimitive).content }

    /** Block until [sessionId]'s turn counter passes [turn], or [timeoutS] elapses. */
    suspend fun waitForTurn(sessionId: Long, turn: Long, timeoutS: Int = 30): WaitResult =
        call(
            "wait_for_session",
            buildJsonObject {
                put("session_id", sessionId)
                put("until", "turn_gt")
                put("turn", turn)
                put("timeout_s", timeoutS)
            },
        ) { json.decodeFromJsonElement(WaitResult.serializer(), it) }

    /** Kill and recreate the tmux session in place — for a wedged REPL. */
    suspend fun restart(sessionId: Long): Unit =
        call("restart_session", buildJsonObject { put("session_id", sessionId) }) { }

    /** Ask the session to persist its work, then arm deletion once it is clean. */
    suspend fun safeKill(sessionId: Long): Unit =
        call("safe_kill_session", buildJsonObject { put("session_id", sessionId) }) { }

    /** Kill the session now, without waiting for it to persist anything. */
    suspend fun kill(sessionId: Long): Unit =
        call("kill_session", buildJsonObject { put("session_id", sessionId) }) { }

    /** Replace the session's tags. */
    suspend fun setTags(sessionId: Long, tags: List<String>): Unit =
        call(
            "set_session_tags",
            buildJsonObject {
                put("session_id", sessionId)
                put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
            },
        ) { }

    /** Set the session's friendly display name. */
    suspend fun rename(sessionId: Long, friendlyName: String): Unit =
        call(
            "set_friendly_name",
            buildJsonObject {
                put("session_id", sessionId)
                put("friendly_name", friendlyName)
            },
        ) { }

    /**
     * Is this hub reachable and its store open, right now.
     *
     * `fleet_health` is in the hub's readonly allow-list — a paired client
     * may always ask, even one that cannot `send_prompt` — which is what
     * makes it the probe [dev.claudefleet.mobile.data.SessionActions.ping]
     * uses to tell an unreachable hub from a merely-dropped `/events` stream.
     */
    suspend fun fleetHealth(): Boolean =
        call("fleet_health") { it.jsonObject["db_ready"]?.jsonPrimitive?.booleanOrNull == true }

    // ---- the wire ----

    private suspend fun send(
        url: String,
        body: String,
        authenticated: Boolean,
    ): Pair<Int, String> = try {
        val response: HttpResponse = http.post(url) {
            contentType(ContentType.Application.Json)
            // rmcp's streamable-HTTP transport requires both, and answers 406
            // without them.
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            if (authenticated && token != null) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            setBody(body)
        }
        response.status.value to response.textWithin(MAX_RESPONSE_BYTES)
    } catch (e: CancellationException) {
        throw e
    } catch (e: HubError) {
        // [textWithin] raises one, and without this line it would be wrapped
        // into a `Transport` saying the hub could not be reached — when it was
        // reached and simply said more than this app will read. Every other
        // `catch (t: Throwable)` in this file is already preceded by this
        // guard; this one was not, because until now nothing inside the block
        // could raise a [HubError].
        throw e
    } catch (t: Throwable) {
        throw HubError.Transport(t)
    }

    private fun parseObject(text: String): JsonObject = try {
        parseWire(text) as JsonObject
    } catch (e: HubError) {
        // A depth refusal is not "the hub answered something unintelligible" —
        // nothing was read at all. Wrapping it would report a reachability
        // problem for a shape problem, and hide which ceiling was hit.
        throw e
    } catch (e: Exception) {
        throw HubError.Transport(e)
    }

    /**
     * The JSON-RPC reply inside the hub's answer to one POST.
     *
     * The first frame that **carries** a reply, not merely the first frame. The
     * old single-frame reader was safe only because rmcp's stateless mode
     * happens to send exactly one message per POST — a property of someone
     * else's code that nobody is holding still for us. If it ever interleaves a
     * progress notification ahead of the response, taking frame one means taking
     * the notification and reporting "neither a result nor an error" for a call
     * that in fact succeeded.
     *
     * A server configured for plain JSON (`json_response = true`) answers the
     * object directly, with no framing at all; both shapes are accepted, so the
     * framing stays the server's business.
     */
    private fun jsonRpcReply(raw: String): JsonObject {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return parseObject(trimmed)
        for (frame in sseFrames(raw)) {
            val reply = try {
                parseWire(frame.data) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: continue
            if ("result" in reply || "error" in reply) return reply
        }
        throw HubError.Transport(
            IllegalStateException("the hub's reply had neither a result nor an error"),
        )
    }

    /**
     * The payload of a successful tool result. Fleet's tools serialize their
     * answer as JSON *inside* the first text content block; a tool that answers
     * prose (`capture_session`, `session_transcript`) puts the prose there
     * instead, which arrives as a JSON string.
     */
    private fun payloadOf(result: JsonObject): JsonElement {
        val text = (result["content"] as? JsonArray)
            ?.asSequence()
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["type"] as? JsonPrimitive)?.content == "text" }
            ?.let { (it["text"] as? JsonPrimitive)?.content }
            ?: return result["structuredContent"] ?: JsonNull
        return try {
            parseWire(text, HUB_PAYLOAD)
        } catch (e: HubError) {
            // The fallback below is for a tool that answers *prose* — text that
            // was never meant to be JSON. A document refused for its depth is
            // the opposite: it is JSON, and the refusal is the answer. Handing
            // it on as a `JsonPrimitive` would turn a ceiling into a confusing
            // deserialization failure two frames later.
            throw e
        } catch (_: Exception) {
            JsonPrimitive(text)
        }
    }

    /**
     * A tool's refusal, scrubbed.
     *
     * [HubError]'s own invariant — "any body that came off the wire goes through
     * [redacted] before it reaches one of these" — did not hold here, and this
     * is the path most likely to quote a request back: fleet's own messages
     * never contain the token, but a gateway that answers a tool call with its
     * own refusal, or a hub that grows a message naming the caller, would. The
     * code goes through too; it is wire text like any other.
     */
    private fun toolError(result: JsonObject): HubError.Tool {
        val structured = result["structuredContent"] as? JsonObject
        val code = (structured?.get("code") as? JsonPrimitive)?.content
        val message = (structured?.get("message") as? JsonPrimitive)?.content
            ?: (result["content"] as? JsonArray)
                ?.asSequence()
                ?.mapNotNull { it as? JsonObject }
                ?.firstNotNullOfOrNull { (it["text"] as? JsonPrimitive)?.content }
            ?: "the tool failed without saying why"
        return HubError.Tool(redacted(code ?: UNKNOWN_CODE, token), redacted(message, token))
    }

    private fun rpcError(error: JsonObject): HubError.Tool {
        // A coded fleet error rides in `data.code`; an rmcp protocol error has
        // only the numeric JSON-RPC code, which is then the most honest thing
        // to report rather than inventing an `E_*` name for it.
        val code = ((error["data"] as? JsonObject)?.get("code") as? JsonPrimitive)?.content
            ?: (error["code"] as? JsonPrimitive)?.content
            ?: UNKNOWN_CODE
        val message = (error["message"] as? JsonPrimitive)?.content
            ?: "the hub rejected the call"
        return HubError.Tool(redacted(code, token), redacted(message, token))
    }

    private companion object {
        /**
         * Stateless streamable HTTP: one request, one reply, nothing to
         * correlate across calls, so the id never has to vary.
         */
        const val REQUEST_ID = 1
        const val UNKNOWN_CODE = "E_UNKNOWN"
    }
}

/** Shared by the client and, later, the event stream. */
internal val json = Json {
    // A hub that grows a field must not break an app already in someone's pocket.
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * The one place a platform's bare engine (`HttpClient(OkHttp)`, `HttpClient(Darwin)`)
 * gets the app's timeout policy, so both pick it up the same way.
 *
 * Ktor's `HttpTimeout` plugin has to be installed explicitly — without it, an
 * engine's own defaults apply, and OkHttp's default read timeout (10 s) is
 * shorter than the hub's 15 s `/events` keep-alive comment. [HubClient.call]
 * is exposed to the same clock: its `/mcp` replies keep the identical SSE
 * framing so a keep-alive flows during a long poll, so a plain per-engine
 * tweak would leave tool calls exposed even after fixing the stream.
 *
 * [HUB_CALL_TIMEOUT_MS] is generous-but-finite — comfortably above the
 * keep-alive interval, so one missed heartbeat does not fail a call, but a
 * hub that has actually gone away still surfaces as an error rather than
 * hanging forever. `/events` itself has no natural end and overrides the
 * request deadline to infinite per request, while keeping its own bounded
 * idle-socket timeout — see [HubEventStream.connect] and
 * `EVENTS_IDLE_TIMEOUT_MS`.
 */
internal fun HttpClient.withHubTimeouts(): HttpClient = config {
    install(HttpTimeout) {
        requestTimeoutMillis = HUB_CALL_TIMEOUT_MS
        connectTimeoutMillis = HUB_CONNECT_TIMEOUT_MS
        socketTimeoutMillis = HUB_CALL_TIMEOUT_MS
    }
}

internal const val HUB_CALL_TIMEOUT_MS = 45_000L
internal const val HUB_CONNECT_TIMEOUT_MS = 15_000L

/**
 * How much of one reply this app will read, in bytes.
 *
 * The timeouts above bound how long a call may take; this bounds how much it
 * may deliver, and the reasoning is the same. A reply is read whole into a
 * `String` before anything looks at it, so without a ceiling the phone's memory
 * is whatever the far end decides to send — and on a phone the failure is not a
 * slow screen but the process being killed.
 *
 * **Why this size.** `session_conversation` is much the largest thing the app
 * asks for, and the hub bounds it server-side at roughly a megabyte of
 * transcript tail — which is why `Conversation.appending` has to cope with
 * turns sliding off the top at all. Eight megabytes is therefore several times
 * more than a hub doing its job can produce, which is the property that
 * matters: the ceiling is not an estimate of the largest legitimate reply, it
 * is the point past which nothing legitimate is happening.
 *
 * It is also not the hub this defends against so much as everything between:
 * the reverse proxy the design assumes, whatever the operator put in front of
 * it, and — on a LAN hub reached over plain `http` — anything on the network
 * able to write into the connection.
 */
internal const val MAX_RESPONSE_BYTES: Int = 8 * 1024 * 1024

/** What an overrunning reply is called on the banner. See [HubError.TooLarge]. */
internal const val HUB_REPLY = "a reply from the hub"

/** The JSON a tool's answer is wrapped in, inside the envelope's text block. */
internal const val HUB_PAYLOAD = "the nesting in a tool's answer"

/** The `POST /pair` reply — the one document carrying a token in the clear. */
internal const val PAIR_REPLY = "the nesting in the pairing reply"

/**
 * The reply's text, refusing anything past [limit].
 *
 * Reads at most `limit + 1` bytes and fails on the extra one, rather than
 * asking how long the body claims to be. A `Content-Length` is a promise from
 * whoever wrote the headers and the body is written by whoever holds the
 * socket: a chunked reply carries no length at all, and one that understates
 * itself is the cheapest possible way past a check that believes it. What is
 * counted here is what actually arrived.
 */
internal suspend fun HttpResponse.textWithin(limit: Int): String {
    val bytes = bodyAsChannel().readBuffer(limit.toLong() + 1).readByteArray()
    if (bytes.size > limit) throw HubError.TooLarge(HUB_REPLY, limit)
    return bytes.decodeToString()
}

private fun JsonElement.asBooleanOrNull(): Boolean? =
    (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
