package dev.claudefleet.mobile.net

import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.PairResult
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.QuickReply
import dev.claudefleet.mobile.model.ResumePlan
import dev.claudefleet.mobile.model.SendPromptResult
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.TicketCard
import dev.claudefleet.mobile.model.Today
import dev.claudefleet.mobile.model.TrackerRow
import dev.claudefleet.mobile.model.WaitResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
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
import kotlinx.serialization.json.JsonObjectBuilder
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
 * Three shapes, all `POST`:
 *  - `/mcp/json` — a JSON-RPC `tools/call` answered as plain
 *    `application/json`, which is what an ordinary call uses (see [call]).
 *  - `/mcp` — the same call answered SSE-framed. Kept for long polls, and as
 *    the fallback for a hub too old to have the JSON mount.
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
     * a constant and nothing needs correlating.
     *
     * **Which mount.** `/mcp` answers `text/event-stream`, and neither Caddy
     * nor Cloudflare will compress that — so on that mount nothing this app
     * fetches is ever compressed. Measured against the hub this was written
     * for: the three calls the repository makes on a cold start are 61 335 B
     * over `/mcp` and about 9 550 B over `/mcp/json`. An ordinary call
     * therefore goes to the JSON mount; only a [FRAMED_TOOLS] long poll needs
     * the framing, because its 15 s keep-alive is what holds the connection
     * open. A hub without the JSON mount answers 404 once and [framedOnly]
     * pins this client to `/mcp` for good — [jsonRpcReply] reads both shapes
     * either way.
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
        val params = buildJsonObject {
            put("name", tool)
            put("arguments", args)
        }
        val deadline = if (tool in LIFECYCLE_TOOLS) HUB_LIFECYCLE_TIMEOUT_MS else null
        val framed = tool in FRAMED_TOOLS || tool in LIFECYCLE_TOOLS
        val result = rpc("tools/call", params, framed, deadline)
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
     * The tools this hub serves *this* token, and the `action` values each
     * one's schema enumerates — `tools/list`, plain MCP on the same mount and
     * the same auth as a call.
     *
     * The hub filters the list per caller (`present::visible_to`), so a
     * readonly token is simply not shown `work_link`: what comes back is what
     * this app may call, which is the gate additive features hang on rather
     * than the wire contract (the contract refuses whole connections; a
     * missing tool should only hide a button).
     *
     * A tool whose `action` is a free string has no entry in
     * [ToolCatalog.actions] — "unknown", not "none": the caller then learns
     * an action is missing only from the hub's `E_INVALID` refusal.
     */
    suspend fun toolCatalog(): ToolCatalog {
        val result = rpc("tools/list", JsonObject(emptyMap()), framed = false, requestTimeoutMs = null)
        val tools = (result["tools"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val names = tools.mapNotNull { (it["name"] as? JsonPrimitive)?.content }.toSet()
        val actions = tools.mapNotNull { tool ->
            val name = (tool["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val enum = (
                ((tool["inputSchema"] as? JsonObject)?.get("properties") as? JsonObject)
                    ?.get("action") as? JsonObject
                )?.get("enum") as? JsonArray ?: return@mapNotNull null
            name to enum.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        }.toMap()
        return ToolCatalog(names, actions)
    }

    /**
     * One JSON-RPC exchange, answered with its `result` object. [call] and
     * [toolCatalog] differ only in method and what they read out of it.
     *
     * A tool that fails does *not* come back as a JSON-RPC error: per the MCP
     * spec it is a successful result with `isError: true`; [call] reads that.
     */
    private suspend fun rpc(
        method: String,
        params: JsonObject,
        framed: Boolean,
        requestTimeoutMs: Long?,
    ): JsonObject {
        val envelope = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", REQUEST_ID)
            put("method", method)
            put("params", params)
        }
        val payload = json.encodeToString(JsonObject.serializer(), envelope)
        var (status, body) = send(mountFor(framed), payload, authenticated = true, requestTimeoutMs = requestTimeoutMs)
        if (status == 404 && !framedOnly) {
            // The hub predates the JSON mount. Note it once and never ask
            // again: a 404 here is a property of the hub, not of the call.
            framedOnly = true
            val retried = send("$base/mcp", payload, authenticated = true, requestTimeoutMs = requestTimeoutMs)
            status = retried.first
            body = retried.second
        }
        throwForStatus(status, body, base, token)

        val reply = jsonRpcReply(body)
        (reply["error"] as? JsonObject)?.let { throw rpcError(it) }
        return reply["result"] as? JsonObject
            ?: throw HubError.Transport(IllegalStateException("the hub's reply had neither a result nor an error"))
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
     *
     * `view=phone` is the hub's named projection for this app: the columns it
     * reads and not the other half of the row (session ids, account ids, token
     * counters). The hub owns that list and widens it when a screen starts
     * reading a new column. A hub that predates the view ignores the key, and
     * `summary=false` still gets it full rows.
     */
    suspend fun listSessions(): List<SessionRow> =
        call("list_sessions", buildJsonObject { put("summary", false); put("view", "phone") }) {
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
    /**
     * A session's conversation.
     *
     * Two things this asks the hub NOT to send, because the app has nowhere
     * to put them:
     *
     * - `events_limit = 0`. The reply carries the conversation's timeline —
     *   compactions, `/clear`, ops — and [Conversation] has `turns` and
     *   `truncated`, so all of it was parsed and dropped on every poll. A hub
     *   too old to accept zero clamps it to one, which is the same answer
     *   minus the saving, never an error.
     * - [sinceTurn], when the caller knows the `turn_seq` it already drew:
     *   the hub then sends the turns completed since, plus the one still
     *   running, instead of the last ten every time. An older hub ignores the
     *   parameter and answers the full window, so a caller must read what
     *   came back rather than assume what it asked for.
     */
    suspend fun conversation(
        sessionId: Long,
        turns: Int? = null,
        sinceTurn: Long? = null,
    ): Conversation =
        call(
            "session_conversation",
            buildJsonObject {
                put("session_id", sessionId)
                if (turns != null) put("turns", turns)
                if (sinceTurn != null) put("since_turn", sinceTurn)
                put("events_limit", 0)
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

    /**
     * The fleet's quick replies — the chip row every composer draws — read
     * (`set` omitted) or replaced whole (`set` given).
     *
     * One tool for both directions, and the answer is the stored list either
     * way: a write needs no follow-up read to find out what the hub made of
     * it, which matters because the hub normalises (trims, drops a duplicate
     * prompt, and answers the built-in defaults for an empty list).
     *
     * `set` is omitted rather than sent as null for a read: a present-but-null
     * `set` is "replace with nothing" to a stricter reader than today's, and
     * the difference between those two is a fleet's whole chip row.
     */
    suspend fun quickReplies(set: List<QuickReply>? = null): List<QuickReply> =
        call(
            "quick_replies",
            buildJsonObject {
                if (set != null) {
                    put("set", json.encodeToJsonElement(ListSerializer(QuickReply.serializer()), set))
                }
            },
        ) { json.decodeFromJsonElement(ListSerializer(QuickReply.serializer()), it) }

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
     * Create a Claude Code session on [hostAlias] in project [projectId].
     *
     * `name` goes out empty: that is how the hub is asked to mint the tmux
     * name itself (`fill_session_name`), the same way the desktop's dialog
     * does, so a session made here is named like every other one. A blank
     * [newWorktree], [baseBranch] or [friendlyName] is left out rather than
     * sent empty — absent is what "the project root", "the default branch"
     * and "derive a label" mean on the hub.
     *
     * This may clone the repository onto the host first, so it runs under
     * [HUB_LIFECYCLE_TIMEOUT_MS] rather than the ordinary deadline; see
     * [LIFECYCLE_TOOLS].
     */
    suspend fun newSession(
        hostAlias: String,
        projectId: Long,
        newWorktree: String? = null,
        baseBranch: String? = null,
        friendlyName: String? = null,
    ): SessionRow =
        call(
            "new_session",
            buildJsonObject {
                put("host_alias", hostAlias)
                put("project_id", projectId)
                put("name", "")
                newWorktree?.takeIf { it.isNotBlank() }?.let { put("new_worktree", it) }
                baseBranch?.takeIf { it.isNotBlank() }?.let { put("base_branch", it) }
                friendlyName?.takeIf { it.isNotBlank() }?.let { put("friendly_name", it) }
            },
        ) { json.decodeFromJsonElement(SessionRow.serializer(), it) }

    // ---- the work graph: `work` reads, `work_link` decides ----
    //
    // Every wrapper names its action outright, so `ToolsTheAppMayCallTest`
    // sees one tool per call site. None of these is ever called unless
    // `HubCapabilities` says the tool (and, for a decision, the action) is
    // there — see `FleetState.capabilities`.

    /** Tickets from the hub's tracker cache for one view: `mine`, `sprint`, `recent`. Never a tracker call. */
    suspend fun workTickets(view: String): List<Ticket> =
        call("work", buildJsonObject { put("action", "tickets"); put("view", view) }) {
            json.decodeFromJsonElement(ListSerializer(Ticket.serializer()), it)
        }

    /** The connected trackers — empty on a hub with the work graph and no tracker. */
    suspend fun workTrackers(): List<TrackerRow> =
        call("work", buildJsonObject { put("action", "trackers") }) {
            json.decodeFromJsonElement(ListSerializer(TrackerRow.serializer()), it)
        }

    /** One ticket by key or pasted URL: the cache, else one live fetch by the hub. */
    suspend fun workLookup(keyOrUrl: String): Ticket {
        val reference = keyOrUrl.trim()
        return call(
            "work",
            buildJsonObject {
                put("action", "lookup")
                put(if (reference.contains("://")) "url" else "key", reference)
            },
        ) { json.decodeFromJsonElement(Ticket.serializer(), it) }
    }

    /** What resuming [key] would do, and where. */
    suspend fun workResumePlan(key: String): ResumePlan =
        call("work", buildJsonObject { put("action", "resume_plan"); put("key", key) }) {
            json.decodeFromJsonElement(ResumePlan.serializer(), it)
        }

    /**
     * The Today digest (claude-fleet M9.1): sessions active since [since]
     * (unix seconds — the phone sends local midnight) by their work, and what
     * shipped. A read over rows the hub already has; never a tracker call.
     */
    suspend fun workToday(since: Long): Today =
        call("work", buildJsonObject { put("action", "today"); put("since", since) }) {
            json.decodeFromJsonElement(Today.serializer(), it)
        }

    /** A ticket's context card (claude-fleet M9.2): its acceptance criteria, from the hub's cache only. */
    suspend fun workCard(key: String): TicketCard =
        call("work", buildJsonObject { put("action", "card"); put("key", key) }) {
            json.decodeFromJsonElement(TicketCard.serializer(), it)
        }

    /** The organisations this token can see (claude-fleet M5), with their trackers. */
    suspend fun workOrgs(): List<OrgDetail> =
        call("work", buildJsonObject { put("action", "orgs") }) {
            json.decodeFromJsonElement(ListSerializer(OrgDetail.serializer()), it)
        }

    /** Accept a suggestion: it becomes the session's work. Answers the updated row. */
    suspend fun confirmWork(sessionId: Long, linkId: Long): SessionRow =
        workLink("confirm", sessionId) { put("link_id", linkId) }

    /** "Not this": a sticky rejection of one suggestion. Answers the updated row. */
    suspend fun rejectWork(sessionId: Long, linkId: Long): SessionRow =
        workLink("reject", sessionId) { put("link_id", linkId) }

    /** Clear a live link. Answers the updated row. */
    suspend fun unlinkWork(sessionId: Long, linkId: Long): SessionRow =
        workLink("unlink", sessionId) { put("link_id", linkId) }

    /**
     * Ask the session's Claude to write a handover for its work (claude-fleet
     * M9.3). Asynchronous on the hub: the prompt is typed into the idle
     * session and this answers at once; the note is stored when the turn
     * stops, and `handover_*` timeline frames say how it went.
     */
    suspend fun handoverWork(sessionId: Long): SessionRow = workLink("handover", sessionId) {}

    /** Set the session's work by item id (a looked-up ticket) or by bare key. */
    suspend fun linkWork(sessionId: Long, itemId: Long? = null, key: String? = null): SessionRow =
        workLink("link", sessionId) {
            if (itemId != null) put("item_id", itemId) else put("key", key.orEmpty())
        }

    /**
     * Start work on a ticket: the hub resolves it, refuses a duplicate with
     * `E_EXISTS` naming the live session, names the worktree and creates the
     * session. [projectId] null lets the hub pick the project that last worked
     * on the key's prefix. No brief: the phone never edits one.
     */
    suspend fun startWork(key: String, hostAlias: String, projectId: Long? = null): SessionRow =
        call(
            "work_link",
            buildJsonObject {
                put("action", "start")
                put("key", key)
                put("host_alias", hostAlias)
                projectId?.let { put("project_id", it) }
            },
        ) { json.decodeFromJsonElement(SessionRow.serializer(), it) }

    /** Resume past work on [key] — [mode] `last` continues the last conversation. */
    suspend fun resumeWork(key: String, mode: String = "last", hostAlias: String? = null): SessionRow =
        call(
            "work_link",
            buildJsonObject {
                put("action", "resume")
                put("key", key)
                put("mode", mode)
                hostAlias?.let { put("host_alias", it) }
            },
        ) { json.decodeFromJsonElement(SessionRow.serializer(), it) }

    private suspend fun workLink(
        action: String,
        sessionId: Long,
        extra: JsonObjectBuilder.() -> Unit,
    ): SessionRow =
        call(
            "work_link",
            buildJsonObject {
                put("action", action)
                put("session_id", sessionId)
                extra()
            },
        ) { json.decodeFromJsonElement(SessionRow.serializer(), it) }

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

    /**
     * Set once, when a hub answers 404 on `/mcp/json`. Not a cache needing
     * invalidation: a hub does not grow the mount while this client object
     * lives, and the client is rebuilt whenever the hub it points at changes.
     */
    private var framedOnly: Boolean = false

    /** `/mcp` for a long poll, a lifecycle call or a hub with no JSON mount; `/mcp/json` otherwise. */
    private fun mountFor(framed: Boolean): String =
        if (framedOnly || framed) "$base/mcp" else "$base/mcp/json"

    private suspend fun send(
        url: String,
        body: String,
        authenticated: Boolean,
        requestTimeoutMs: Long? = null,
    ): Pair<Int, String> = try {
        val response: HttpResponse = http.post(url) {
            // Only the whole-request deadline moves. The socket timeout stays
            // the ordinary one, because on the framed mount the hub's 15 s
            // keep-alive is what keeps the socket from going idle.
            if (requestTimeoutMs != null) timeout { requestTimeoutMillis = requestTimeoutMs }
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
        return HubError.Tool(
            redacted(code ?: UNKNOWN_CODE, token),
            redacted(message, token),
            structured?.get("details")?.redactedStrings(token),
        )
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
        return HubError.Tool(
            redacted(code, token),
            redacted(message, token),
            (error["data"] as? JsonObject)?.get("details")?.redactedStrings(token),
        )
    }

    private companion object {
        /**
         * Stateless streamable HTTP: one request, one reply, nothing to
         * correlate across calls, so the id never has to vary.
         */
        const val REQUEST_ID = 1

        /**
         * Tools whose reply is a long poll, and so must keep the SSE mount:
         * the hub holds the request open for up to ten minutes and the 15 s
         * keep-alive comment is the only thing stopping a proxy, a tunnel or
         * a phone's NAT from dropping it. The app does not call any of these
         * yet; the set exists so that adding one cannot silently take its
         * keep-alive away.
         */
        val FRAMED_TOOLS = setOf(
            "wait_for_session",
            "wait_for_task",
            "wait_for_attention",
            "run_prompt",
        )
        /**
         * Tools the hub bounds at its `LIFECYCLE_CAP` (300 s) rather than a
         * quick round trip — creating a session may clone a repository onto
         * the host first. They ride the framed mount for its keep-alive, like
         * [FRAMED_TOOLS], and get [HUB_LIFECYCLE_TIMEOUT_MS] instead of the
         * ordinary deadline, which would give up on a clone that is going fine.
         *
         * `work_link` is here whole, not per action: `start` and `resume`
         * create a session (a worktree, perhaps a clone) and the hub bounds
         * the tool at `Deadline::Lifecycle`. A quick `confirm` riding the
         * same mount costs nothing but the framing.
         */
        val LIFECYCLE_TOOLS = setOf("new_session", "work_link")
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
 * The deadline for a [HubClient] call the hub itself bounds at five minutes
 * (`LIFECYCLE_CAP`). Half a minute above it, so the hub's own timeout — which
 * comes back as an answer — always lands before this one, which can only say
 * the connection went.
 */
internal const val HUB_LIFECYCLE_TIMEOUT_MS = 330_000L

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

/**
 * A tool refusal's `details`, with every string in it scrubbed like the
 * message is. It is wire text like any other, and it lands on [HubError.Tool],
 * whose `toString` a crash report may print.
 */
private fun JsonElement.redactedStrings(token: String?): JsonElement? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) JsonPrimitive(redacted(content, token)) else this
    is JsonArray -> JsonArray(mapNotNull { it.redactedStrings(token) ?: JsonNull })
    is JsonObject -> JsonObject(mapValues { (_, v) -> v.redactedStrings(token) ?: JsonNull })
}

private fun JsonElement.asBooleanOrNull(): Boolean? =
    (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
