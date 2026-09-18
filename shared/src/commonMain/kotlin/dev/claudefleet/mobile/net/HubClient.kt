package dev.claudefleet.mobile.net

import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.PairResult
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SendPromptResult
import dev.claudefleet.mobile.model.SessionRow
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
            json.decodeFromString(PairResult.serializer(), text)
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
        response.status.value to response.bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        throw HubError.Transport(t)
    }

    private fun parseObject(text: String): JsonObject = try {
        json.parseToJsonElement(text) as JsonObject
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
                json.parseToJsonElement(frame.data) as? JsonObject
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
        val text = (result["content"] as? kotlinx.serialization.json.JsonArray)
            ?.asSequence()
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["type"] as? JsonPrimitive)?.content == "text" }
            ?.let { (it["text"] as? JsonPrimitive)?.content }
            ?: return result["structuredContent"] ?: JsonNull
        return try {
            json.parseToJsonElement(text)
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
            ?: (result["content"] as? kotlinx.serialization.json.JsonArray)
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

private fun JsonElement.asBooleanOrNull(): Boolean? =
    (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
