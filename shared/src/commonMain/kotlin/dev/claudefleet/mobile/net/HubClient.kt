package dev.claudefleet.mobile.net

import dev.claudefleet.mobile.model.ActivityProbe
import dev.claudefleet.mobile.model.AccountRow
import dev.claudefleet.mobile.model.AccountUsageSnapshot
import dev.claudefleet.mobile.model.FormView
import dev.claudefleet.mobile.model.RepoTree
import dev.claudefleet.mobile.model.FileDiff
import dev.claudefleet.mobile.model.FileContent
import dev.claudefleet.mobile.model.CommitDetail
import dev.claudefleet.mobile.model.Commit
import dev.claudefleet.mobile.model.AgentInstall
import dev.claudefleet.mobile.model.ConfirmRequest
import dev.claudefleet.mobile.model.OperatorStatus
import dev.claudefleet.mobile.model.ChangedFile
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.ConversationSummary
import dev.claudefleet.mobile.model.Download
import dev.claudefleet.mobile.model.DownloadList
import dev.claudefleet.mobile.model.DownloadRemoved
import dev.claudefleet.mobile.model.PullRequestList
import dev.claudefleet.mobile.model.FleetTask
import dev.claudefleet.mobile.model.GithubRepo
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.HubHealth
import dev.claudefleet.mobile.model.MoveOutcome
import dev.claudefleet.mobile.model.moveOutcomeOf
import dev.claudefleet.mobile.model.HostWorktrees
import dev.claudefleet.mobile.model.RestoreReport
import dev.claudefleet.mobile.model.LostCandidate
import dev.claudefleet.mobile.model.MultiStart
import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.MemberGrants
import dev.claudefleet.mobile.model.MemberRemoved
import dev.claudefleet.mobile.model.OrgMemberRow
import dev.claudefleet.mobile.model.PagesBundle
import dev.claudefleet.mobile.model.PairResult
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.QuickReply
import dev.claudefleet.mobile.model.TidyReport
import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.Routine
import dev.claudefleet.mobile.model.DebugDevice
import dev.claudefleet.mobile.model.DebugDeviceList
import dev.claudefleet.mobile.model.DeviceOutput
import dev.claudefleet.mobile.model.RoutineDetail
import dev.claudefleet.mobile.model.RoutineRun
import dev.claudefleet.mobile.model.MissionCard
import dev.claudefleet.mobile.model.MissionDetail
import dev.claudefleet.mobile.model.StartOutcome
import dev.claudefleet.mobile.model.TidyApplyItem
import dev.claudefleet.mobile.model.TidyApplied
import dev.claudefleet.mobile.model.ReopenedWork
import dev.claudefleet.mobile.model.PastWorkSummary
import dev.claudefleet.mobile.model.RepairReport
import dev.claudefleet.mobile.model.NewBgSessionResult
import dev.claudefleet.mobile.model.ResumePlan
import dev.claudefleet.mobile.model.SendPromptResult
import dev.claudefleet.mobile.model.SessionEvent
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.UsageReport
import dev.claudefleet.mobile.model.SettingDescriptor
import dev.claudefleet.mobile.model.SettingWrite
import dev.claudefleet.mobile.model.SettingsDecided
import dev.claudefleet.mobile.model.SettingsPending
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.TicketCard
import dev.claudefleet.mobile.model.ToolDetail
import dev.claudefleet.mobile.model.Today
import dev.claudefleet.mobile.model.TrackerRow
import dev.claudefleet.mobile.model.WaitResult
import dev.claudefleet.mobile.model.BatchResult
import dev.claudefleet.mobile.model.OrgImpact
import dev.claudefleet.mobile.model.ReviewPage
import dev.claudefleet.mobile.model.RulePreview
import dev.claudefleet.mobile.model.SessionTasks
import dev.claudefleet.mobile.model.TaskDetail
import dev.claudefleet.mobile.model.WorkDecision
import dev.claudefleet.mobile.model.WorkRule
import dev.claudefleet.mobile.model.WorkRuleDraft
import dev.claudefleet.mobile.model.WorkTask
import dev.claudefleet.mobile.model.WorkTreeFilters
import dev.claudefleet.mobile.model.WorkTreePage
import dev.claudefleet.mobile.model.WorkView
import dev.claudefleet.mobile.model.WorkViewDraft
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.readByteArray
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
        val deadline = when (tool) {
            in LONG_POLL_TOOLS -> HUB_LONG_POLL_TIMEOUT_MS
            in LIFECYCLE_TOOLS -> HUB_LIFECYCLE_TIMEOUT_MS
            else -> null
        }
        val framed = tool in FRAMED_TOOLS || tool in LIFECYCLE_TOOLS || tool in LONG_POLL_TOOLS
        val result = rpc("tools/call", params, framed, deadline)
        if (result["isError"]?.asBooleanOrNull() == true) throw toolError(result)
        // Inside the try, not outside it: [HubError] claims to be the closed set
        // every screen branches on, and a payload that does not fit the model
        // would otherwise throw a raw SerializationException straight past them.
        // `Transport` is the right bucket — it covers a hub that "answered
        // something unintelligible" as well as one that could not be reached.
        return try {
            // Off the caller's dispatcher, because on Android the caller is
            // very often the *UI* one: a composition's `rememberCoroutineScope`
            // carries `AndroidUiDispatcher.Main`, which not only runs the work
            // on the main thread but resumes it on a frame callback. And this
            // is not a little work — [payloadOf] depth-scans and parses the
            // payload string the MCP result nests, then [deserialize] walks
            // that tree into data classes, which for `list_sessions` on a
            // fleet of dozens is measured in frames, not microseconds.
            //
            // Here rather than at each call site so it holds for every caller,
            // including the ones that legitimately run on the UI scope (a
            // screen's own action handlers).
            withContext(Dispatchers.Default) { deserialize(payloadOf(result)) }
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
        fun JsonObject.properties() = (this["inputSchema"] as? JsonObject)?.get("properties") as? JsonObject
        val actions = tools.mapNotNull { tool ->
            val name = (tool["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val enum = (tool.properties()?.get("action") as? JsonObject)?.get("enum") as? JsonArray
                ?: return@mapNotNull null
            name to enum.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        }.toMap()
        val params = tools.mapNotNull { tool ->
            val name = (tool["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val properties = tool.properties() ?: return@mapNotNull null
            name to properties.keys
        }.toMap()
        return ToolCatalog(names, actions, params)
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

        // The envelope pass, off the caller's dispatcher for the same reason
        // the payload pass is — see [call]. This one parses the whole reply
        // text, so on a large answer it is the more expensive of the two.
        val reply = withContext(Dispatchers.Default) { jsonRpcReply(body) }
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
        claudeSessionId: String? = null,
    ): Conversation =
        call(
            "session_conversation",
            buildJsonObject {
                put("session_id", sessionId)
                if (turns != null) put("turns", turns)
                if (sinceTurn != null) put("since_turn", sinceTurn)
                if (claudeSessionId != null) put("claude_session_id", claudeSessionId)
                put("events_limit", 0)
            },
        ) { json.decodeFromJsonElement(Conversation.serializer(), it) }

    /**
     * One tool call's input and result, for its expanded row — read only when
     * a person asks, since a conversation read carries one-liners only.
     *
     * Additive: a hub that does not list `session_tool_detail` in `tools/list`
     * is never asked (see [HubCapabilities.toolDetail]). [claudeSessionId]
     * looks in an earlier conversation of the session; null means the
     * current one.
     */
    suspend fun toolDetail(
        sessionId: Long,
        toolUseId: String,
        claudeSessionId: String? = null,
    ): ToolDetail =
        call(
            "session_tool_detail",
            buildJsonObject {
                put("session_id", sessionId)
                put("tool_use_id", toolUseId)
                if (claudeSessionId != null) put("claude_session_id", claudeSessionId)
            },
        ) { json.decodeFromJsonElement(ToolDetail.serializer(), it) }

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
     * empty `prompt`. [key] is one of `"Enter"`, `"Escape"`, `"C-c"` or, from
     * [HUB_VERSION_DIGIT_KEYS], a digit `"1"`–`"9"` that picks that option of
     * a dialog — the set the hub's guard accepts.
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
     * What the session's pane shows right now (`session_activity`, readonly):
     * one capture, seconds old, where the row's `pending_input` is up to a
     * reconcile tick old. Read immediately before a dialog answer goes out.
     */
    suspend fun activity(sessionId: Long): ActivityProbe =
        call(
            "session_activity",
            buildJsonObject { put("session_id", sessionId) },
        ) { json.decodeFromJsonElement(ActivityProbe.serializer(), it) }

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

    /**
     * Copy the session's transcript up to [anchorUuid] (a turn's
     * `prompt_uuid`; null keeps all of it) into a new conversation:
     * [mode] `"rewind"` restarts this session on the copy, `"fork"` starts a
     * new session on it — in a new worktree of [newWorktree]'s name when one
     * is given. The original transcript is never changed. Answers the row:
     * a fork's is the new session.
     */
    suspend fun rewind(sessionId: Long, anchorUuid: String?, mode: String, newWorktree: String?): SessionRow =
        call(
            "rewind_conversation",
            buildJsonObject {
                put("session_id", sessionId)
                put("mode", mode)
                if (anchorUuid != null) put("anchor_uuid", anchorUuid)
                if (newWorktree != null) put("new_worktree", newWorktree)
            },
        ) { json.decodeFromJsonElement(SessionRow.serializer(), it) }

    /**
     * ESTIMATED usage (`usage_report`, readonly): totals, per host, per UTC
     * day and per session. [sinceSecs] narrows to what changed in that many
     * seconds; null is the hub's default, the last 30 days.
     */
    suspend fun usageReport(sinceSecs: Long? = null): UsageReport =
        call("usage_report", buildJsonObject { if (sinceSecs != null) put("since_secs", sinceSecs) }) {
            json.decodeFromJsonElement(UsageReport.serializer(), it)
        }

    /** The Claude accounts seen across the fleet's hosts (`list_accounts`, readonly). */
    suspend fun listAccounts(): List<AccountRow> =
        call("list_accounts") { json.decodeFromJsonElement(ListSerializer(AccountRow.serializer()), it) }

    /** Every account's last usage reading (`account_usage`, readonly). */
    suspend fun accountUsage(): List<AccountUsageSnapshot> =
        call("account_usage") { json.decodeFromJsonElement(ListSerializer(AccountUsageSnapshot.serializer()), it) }

    /** A review session in [sourceSessionId]'s worktree, seeded with [prompt] (`spawn_review`); answers its row. */
    suspend fun spawnReview(sourceSessionId: Long, prompt: String): SessionRow =
        call(
            "spawn_review",
            buildJsonObject {
                put("source_session_id", sourceSessionId)
                put("prompt", prompt)
            },
        ) { json.decodeFromJsonElement(SessionRow.serializer(), it) }

    /** Make a session's directory a healthy worktree with its pane running there (`repair_session`). */
    suspend fun repairSession(sessionId: Long): RepairReport =
        call("repair_session", buildJsonObject { put("session_id", sessionId) }) {
            json.decodeFromJsonElement(RepairReport.serializer(), it)
        }

    /** A supervised headless Claude session on [hostAlias] with [prompt] (`new_bg_session`). */
    suspend fun newBgSession(hostAlias: String, name: String, prompt: String): NewBgSessionResult =
        call(
            "new_bg_session",
            buildJsonObject {
                put("host_alias", hostAlias)
                put("name", name)
                put("prompt", prompt)
            },
        ) { json.decodeFromJsonElement(NewBgSessionResult.serializer(), it) }

    /**
     * Move a session to [targetHost] (`move_session`), carrying its
     * uncommitted and unpushed work and its Claude state. [dryRun] previews
     * and changes nothing; [whenIdle] waits for the session to go idle
     * first; [cancelWait] ends such a wait. The hub may ask for a desktop
     * confirmation (`E_CONFIRM_REQUIRED`).
     */
    suspend fun moveSession(
        sessionId: Long,
        targetHost: String,
        keepSource: Boolean = false,
        dryRun: Boolean = false,
        whenIdle: Boolean = false,
        cancelWait: Boolean = false,
    ): MoveOutcome =
        call(
            "move_session",
            buildJsonObject {
                put("session_id", sessionId)
                put("target_host_alias", targetHost)
                put("keep_source", keepSource)
                put("dry_run", dryRun)
                put("when", if (cancelWait) "cancel" else if (whenIdle) "idle" else "now")
            },
        ) { moveOutcomeOf(it) { strategy, element -> json.decodeFromJsonElement(strategy, element) } }

    /** Re-probe a host's reachability and versions (`probe_host`); answers its row. */
    suspend fun probeHost(alias: String): HostRow =
        call("probe_host", buildJsonObject { put("alias", alias) }) { json.decodeFromJsonElement(HostRow.serializer(), it) }

    /**
     * Restore the sessions [alias] lost to a reboot or a tmux restart
     * (`restore_host_sessions`): [dryRun] answers the plan alone.
     */
    suspend fun restoreHostSessions(alias: String, dryRun: Boolean): RestoreReport =
        call(
            "restore_host_sessions",
            buildJsonObject {
                put("host_alias", alias)
                put("dry_run", dryRun)
            },
        ) { json.decodeFromJsonElement(RestoreReport.serializer(), it) }

    /** Conversations on [alias] fleet has no live pane for (`discover_lost_sessions`, readonly). */
    suspend fun discoverLostSessions(alias: String): List<LostCandidate> =
        call("discover_lost_sessions", buildJsonObject { put("host_alias", alias) }) {
            json.decodeFromJsonElement(ListSerializer(LostCandidate.serializer()), it)
        }

    /** Kill and rebuild a session in its worktree, resuming its conversation (`recreate_session`) — a ghost too. */
    suspend fun recreateSession(sessionId: Long): SessionRow =
        call("recreate_session", buildJsonObject { put("session_id", sessionId) }) {
            json.decodeFromJsonElement(SessionRow.serializer(), it)
        }

    /**
     * A plain shell beside a session (`new_shell_session`): an interactive
     * login shell in the same project and worktree, no agent. Read with
     * [capture], typed into with [sendPrompt] and [sendKeys].
     */
    suspend fun newShellSession(hostAlias: String, projectId: Long, worktreeId: Long?, name: String): SessionRow =
        call(
            "new_shell_session",
            buildJsonObject {
                put("host_alias", hostAlias)
                put("project_id", projectId)
                put("name", name)
                worktreeId?.let { put("worktree_id", it) }
            },
        ) { json.decodeFromJsonElement(SessionRow.serializer(), it) }

    /**
     * Archive a session (`work_link { action: archive }`): its work links are
     * stamped archived, so it leaves the work board; opening it again from
     * the desktop un-archives it.
     */
    suspend fun archiveSession(sessionId: Long): Unit =
        call(
            "work_link",
            buildJsonObject {
                put("action", "archive")
                put("session_id", sessionId)
            },
        ) { }

    /**
     * Start the hub's fleet-agent install job on [alias] (`install_agent`,
     * claude-fleet 4.9). Returns the job at once; [agentInstalls] follows it.
     * Called only where the hub lists the tool to this token.
     */
    suspend fun installAgent(alias: String): AgentInstall =
        call("install_agent", buildJsonObject { put("alias", alias) }) { json.decodeFromJsonElement(AgentInstall.serializer(), it) }

    /** fleet-agent install jobs, newest first (`agent_installs`); only [alias]'s when given. */
    suspend fun agentInstalls(alias: String? = null): List<AgentInstall> =
        call("agent_installs", buildJsonObject { alias?.let { put("alias", it) } }) {
            json.decodeFromJsonElement(ListSerializer(AgentInstall.serializer()), it)
        }

    /** Delete a ghost's row for good (`dismiss_ghost_session`). */
    suspend fun dismissGhost(sessionId: Long): Unit =
        call("dismiss_ghost_session", buildJsonObject { put("session_id", sessionId) }) { }

    /** A session's changed files, git status in its worktree (`repo_changes`, readonly). */
    suspend fun repoChanges(sessionId: Long): List<ChangedFile> =
        call("repo_changes", buildJsonObject { put("session_id", sessionId) }) {
            json.decodeFromJsonElement(ListSerializer(ChangedFile.serializer()), it)
        }

    /** A session's worktree files (`repo_tree`, readonly). */
    suspend fun repoTree(sessionId: Long): RepoTree =
        call("repo_tree", buildJsonObject { put("session_id", sessionId) }) {
            json.decodeFromJsonElement(RepoTree.serializer(), it)
        }

    /** One worktree file's contents, capped (`repo_file`, readonly). */
    suspend fun repoFile(sessionId: Long, path: String): FileContent =
        call(
            "repo_file",
            buildJsonObject {
                put("session_id", sessionId)
                put("path", path)
            },
        ) { json.decodeFromJsonElement(FileContent.serializer(), it) }

    /** One worktree file's diff against HEAD (`repo_diff`, readonly). */
    suspend fun repoDiff(sessionId: Long, path: String): FileDiff =
        call(
            "repo_diff",
            buildJsonObject {
                put("session_id", sessionId)
                put("path", path)
            },
        ) { json.decodeFromJsonElement(FileDiff.serializer(), it) }

    /** The worktree's commit log, newest first (`repo_log`, readonly); [skip] pages back. */
    suspend fun repoLog(sessionId: Long, limit: Int = 50, skip: Int = 0): List<Commit> =
        call(
            "repo_log",
            buildJsonObject {
                put("session_id", sessionId)
                put("limit", limit)
                put("skip", skip)
            },
        ) { json.decodeFromJsonElement(ListSerializer(Commit.serializer()), it) }

    /** One commit and its files (`repo_commit`, readonly). */
    suspend fun repoCommit(sessionId: Long, hash: String): CommitDetail =
        call(
            "repo_commit",
            buildJsonObject {
                put("session_id", sessionId)
                put("hash", hash)
            },
        ) { json.decodeFromJsonElement(CommitDetail.serializer(), it) }

    /** One file's diff in one commit (`repo_commit_diff`, readonly). */
    suspend fun repoCommitDiff(sessionId: Long, hash: String, path: String): FileDiff =
        call(
            "repo_commit_diff",
            buildJsonObject {
                put("session_id", sessionId)
                put("hash", hash)
                put("path", path)
            },
        ) { json.decodeFromJsonElement(FileDiff.serializer(), it) }

    /** The Claude conversations a session has run, newest first (`session_conversations`, readonly). */
    suspend fun conversations(sessionId: Long): List<ConversationSummary> =
        call("session_conversations", buildJsonObject { put("session_id", sessionId) }) {
            json.decodeFromJsonElement(ListSerializer(ConversationSummary.serializer()), it)
        }

    /** A session's event timeline, newest first (`session_history`, readonly). */
    suspend fun sessionHistory(sessionId: Long, limit: Int = 100): List<SessionEvent> =
        call(
            "session_history",
            buildJsonObject {
                put("session_id", sessionId)
                put("limit", limit)
            },
        ) { json.decodeFromJsonElement(ListSerializer(SessionEvent.serializer()), it) }

    /** Sessions sharing this one's project and worktree (`related_sessions`, readonly). */
    suspend fun relatedSessions(sessionId: Long): List<SessionRow> =
        call("related_sessions", buildJsonObject { put("session_id", sessionId) }) {
            json.decodeFromJsonElement(ListSerializer(SessionRow.serializer()), it)
        }

    /** Fleet tasks, newest first (`list_tasks`, readonly). */
    suspend fun listTasks(limit: Int = 100): List<FleetTask> =
        call("list_tasks", buildJsonObject { put("limit", limit) }) {
            json.decodeFromJsonElement(ListSerializer(FleetTask.serializer()), it)
        }

    /** Cancel a queued or running task (`cancel_task`); the worker session keeps running. */
    suspend fun cancelTask(taskId: Long): Unit =
        call("cancel_task", buildJsonObject { put("task_id", taskId) }) { }

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

    // ---- the fleet's settings (claude-fleet declarative pages P6) ----
    //
    // The hub answers these to a person's own paired device (a client bound
    // to no org). Reads for either mode; `set_setting` and the decision are
    // writes, which the hub takes only from a device its operator trusts —
    // `setting_proposals` says whether this one is (`can_write`).

    /** The page specs: which settings go on which page. */
    suspend fun listPages(): PagesBundle =
        call("list_pages") { json.decodeFromJsonElement(PagesBundle.serializer(), it) }

    /** Every registered setting with its metadata and effective value. */
    suspend fun describeSettings(): List<SettingDescriptor> =
        call("get_settings", buildJsonObject { put("describe", true) }) {
            json.decodeFromJsonElement(ListSerializer(SettingDescriptor.serializer()), it)
        }

    /**
     * Change one setting, as the person holding this device; answers every
     * effective value. The hub validates it, and refuses an untrusted device
     * with `E_FORBIDDEN` naming the command that trusts it.
     */
    suspend fun setSetting(key: String, value: String): Map<String, String> =
        call(
            "set_setting",
            buildJsonObject {
                put("key", key)
                put("value", value)
            },
        ) { json.decodeFromJsonElement(MapSerializer(String.serializer(), String.serializer()), it) }

    /** Proposals waiting for review, and whether this device may decide them. */
    suspend fun settingProposals(): SettingsPending =
        call("setting_proposals") { json.decodeFromJsonElement(SettingsPending.serializer(), it) }

    /** Apply [accept], reject [reject]; each is decided on its own. */
    suspend fun decideSettingProposals(accept: List<Long>, reject: List<Long>): SettingsDecided =
        call(
            "decide_setting_proposals",
            buildJsonObject {
                put("accept", json.encodeToJsonElement(ListSerializer(Long.serializer()), accept))
                put("reject", json.encodeToJsonElement(ListSerializer(Long.serializer()), reject))
            },
        ) { json.decodeFromJsonElement(SettingsDecided.serializer(), it) }

    // ---- chat forms (claude-fleet contract revision 9, `ask`) ----

    /** One form: its spec, why the agent asks, and its state. */
    suspend fun askGet(formId: String): FormView =
        call("ask", buildJsonObject { put("get", formId) }) { json.decodeFromJsonElement(FormView.serializer(), it) }

    /**
     * Answer [formId] with [values], field name to value; the hub checks them
     * against the form and refuses with `E_INVALID` naming each field's
     * problem. A secret's value goes to a file on the session's host, never
     * into the transcript.
     */
    suspend fun askAnswer(formId: String, values: Map<String, JsonElement>): FormView =
        call(
            "ask",
            buildJsonObject {
                put("answer", formId)
                put("values", JsonObject(values))
            },
        ) { json.decodeFromJsonElement(FormView.serializer(), it) }

    /** Decline [formId], with an optional [note] the agent reads. */
    suspend fun askDecline(formId: String, note: String?): FormView =
        call(
            "ask",
            buildJsonObject {
                put("decline", formId)
                if (!note.isNullOrBlank()) put("note", note)
            },
        ) { json.decodeFromJsonElement(FormView.serializer(), it) }

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
        /** An existing worktree of the project to start in, rather than its main checkout. */
        worktreeId: Long? = null,
        /** Resume this Claude conversation rather than start a new one (`discover_lost_sessions`). */
        resumeClaudeSessionId: String? = null,
        /** The tmux name to ask for; blank lets the hub pick one. */
        name: String = "",
    ): SessionRow =
        call(
            "new_session",
            buildJsonObject {
                put("host_alias", hostAlias)
                put("project_id", projectId)
                put("name", name)
                worktreeId?.let { put("worktree_id", it) }
                resumeClaudeSessionId?.let { put("resume_claude_session_id", it) }
                newWorktree?.takeIf { it.isNotBlank() }?.let { put("new_worktree", it) }
                baseBranch?.takeIf { it.isNotBlank() }?.let { put("base_branch", it) }
                friendlyName?.takeIf { it.isNotBlank() }?.let { put("friendly_name", it) }
            },
        ) { json.decodeFromJsonElement(SessionRow.serializer(), it) }

    /**
     * Find the hub's agent session — the desktop's ✦ — or start it, and
     * return its row. Starting it writes the agent's project and MCP config on
     * the hub's machine and launches Claude there, so it rides
     * [LIFECYCLE_TOOLS] like [newSession].
     */
    suspend fun ensureOperator(): SessionRow =
        call("ensure_operator") { json.decodeFromJsonElement(SessionRow.serializer(), it) }

    /** Whether Control can take a message, without waking it (`operator_status`, readonly). */
    suspend fun operatorStatus(): OperatorStatus =
        call("operator_status") { json.decodeFromJsonElement(OperatorStatus.serializer(), it) }

    /** The calls waiting on a person's yes, oldest first (`mcp_confirms`, redesign 9.2). */
    suspend fun mcpConfirms(): List<ConfirmRequest> =
        call("mcp_confirms") { json.decodeFromJsonElement(ListSerializer(ConfirmRequest.serializer()), it) }

    /** Approve or deny one waiting call; false when it was already answered or has expired. */
    suspend fun answerMcpConfirm(nonce: String, approved: Boolean): Boolean =
        call(
            "answer_mcp_confirm",
            buildJsonObject {
                put("nonce", nonce)
                put("approved", approved)
            },
        ) { (it as? JsonPrimitive)?.booleanOrNull ?: false }

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

    /**
     * Add a project on [hostAlias] (`add_project`): clone [cloneUrl], adopt
     * the checkout at [folderPath] (the hub's own `local` host only), or make
     * a new repository [owner]/[repo] — on GitHub too when [createRemote],
     * which the hub refuses once with a token ([confirm]) to send back.
     * Answers the project row.
     */
    suspend fun addProject(
        hostAlias: String,
        cloneUrl: String? = null,
        folderPath: String? = null,
        owner: String? = null,
        repo: String? = null,
        createRemote: Boolean = false,
        confirm: String? = null,
    ): ProjectRow =
        call(
            "add_project",
            buildJsonObject {
                put("host_alias", hostAlias)
                put(
                    "source",
                    buildJsonObject {
                        if (cloneUrl != null) {
                            put("kind", "clone")
                            put("url", cloneUrl)
                        } else if (folderPath != null) {
                            put("kind", "folder")
                            put("path", folderPath)
                        } else {
                            put("kind", "new")
                            put("owner", owner.orEmpty())
                            put("repo", repo.orEmpty())
                            put("create_remote", createRemote)
                            confirm?.let { put("confirm", it) }
                        }
                    },
                )
            },
        ) { json.decodeFromJsonElement(ProjectRow.serializer(), it) }

    /** Repositories `gh` on [hostAlias] can see (`list_github_repos`, readonly). */
    suspend fun listGithubRepos(hostAlias: String): List<GithubRepo> =
        call("list_github_repos", buildJsonObject { put("host_alias", hostAlias) }) {
            json.decodeFromJsonElement(ListSerializer(GithubRepo.serializer()), it)
        }

    /** A project's worktrees as they are on [hostAlias] (`list_host_worktrees`, readonly). */
    suspend fun listHostWorktrees(hostAlias: String, projectId: Long): HostWorktrees =
        call(
            "list_host_worktrees",
            buildJsonObject {
                put("host_alias", hostAlias)
                put("project_id", projectId)
            },
        ) { json.decodeFromJsonElement(HostWorktrees.serializer(), it) }

    /** Delete a worktree on its host (`delete_worktree`, no --force); refused while a session lives in it. */
    suspend fun deleteWorktree(worktreeId: Long): Unit =
        call("delete_worktree", buildJsonObject { put("worktree_id", worktreeId) }) { }

    /** One setting's writes, newest first (`setting_history`, a person's own device). */
    suspend fun settingHistory(key: String, limit: Int = 30): List<SettingWrite> =
        call(
            "setting_history",
            buildJsonObject {
                put("key", key)
                put("limit", limit)
            },
        ) { json.decodeFromJsonElement(ListSerializer(SettingWrite.serializer()), it) }

    /** What Tidy-up suggests (`work { action: tidy }`). */
    suspend fun workTidy(): TidyReport =
        call("work", buildJsonObject { put("action", "tidy") }) { json.decodeFromJsonElement(TidyReport.serializer(), it) }

    /** Work that came back after it was done (`work { action: reopened }`). */
    suspend fun workReopened(): List<ReopenedWork> =
        call("work", buildJsonObject { put("action", "reopened") }) {
            json.decodeFromJsonElement(ListSerializer(ReopenedWork.serializer()), it)
        }

    /** Apply Tidy-up choices (`work_link { action: tidy_apply }`); a kill among them may need a desktop confirmation. */
    suspend fun workTidyApply(items: List<TidyApplyItem>): TidyApplied =
        call(
            "work_link",
            buildJsonObject {
                put("action", "tidy_apply")
                put("items", json.encodeToJsonElement(ListSerializer(TidyApplyItem.serializer()), items))
            },
        ) { json.decodeFromJsonElement(TidyApplied.serializer(), it) }

    /** Clear an item's "reopened" (`work_link { action: dismiss }`). */
    suspend fun workDismissReopened(itemId: Long): Unit =
        call(
            "work_link",
            buildJsonObject {
                put("action", "dismiss")
                put("item_id", itemId)
            },
        ) { }

    /** A summary of a past session on [key], written by Claude and kept in its journal (`work_link { action: summarize }`). */
    suspend fun workSummarize(key: String, linkId: Long): PastWorkSummary =
        call(
            "work_link",
            buildJsonObject {
                put("action", "summarize")
                put("key", key)
                put("link_id", linkId)
            },
        ) { json.decodeFromJsonElement(PastWorkSummary.serializer(), it) }

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

    // ---- missions (claude-fleet orchestration O1–O8) ----

    /** The missions this token may see (`work { action: missions }`). */
    suspend fun workMissions(): List<Mission> =
        call("work", buildJsonObject { put("action", "missions") }) {
            json.decodeFromJsonElement(ListSerializer(Mission.serializer()), it)
        }

    /** One mission with its members, graph and loop (`work { action: mission }`). */
    suspend fun workMission(missionId: Long): MissionDetail =
        call("work", buildJsonObject { put("action", "mission"); put("mission_id", missionId) }) {
            json.decodeFromJsonElement(MissionDetail.serializer(), it)
        }

    /** Take the mission's next steps, or only the one [step] names (`run:12`) — a person's press. */
    suspend fun startMission(missionId: Long, step: String? = null): StartOutcome =
        call(
            "work_link",
            buildJsonObject {
                put("action", "mission_start")
                put("mission_id", missionId)
                step?.let { put("step", it) }
            },
        ) { json.decodeFromJsonElement(StartOutcome.serializer(), it) }

    /** Apply ([ok]) or dismiss a card of the confirm queue; a question is answered with [note]. */
    suspend fun decideMissionCard(cardId: Long, ok: Boolean, note: String? = null): MissionCard =
        call(
            "work_link",
            buildJsonObject {
                put("action", "card_decide")
                put("card_id", cardId)
                put("ok", ok)
                note?.takeIf { it.isNotBlank() }?.let { put("note", it.trim()) }
            },
        ) { json.decodeFromJsonElement(MissionCard.serializer(), it) }

    /** Pause or resume one mission (`mission_state`), under its [expectedVersion]. */
    suspend fun setMissionState(missionId: Long, state: String, expectedVersion: Long): Mission =
        call(
            "work_link",
            buildJsonObject {
                put("action", "mission_state")
                put("mission_id", missionId)
                put("status", state)
                put("expected_version", expectedVersion)
            },
        ) { json.decodeFromJsonElement(Mission.serializer(), it) }

    /** Pause every active mission this token may change and end their grants; answers their ids. */
    suspend fun pauseAllMissions(): List<Long> =
        call("work_link", buildJsonObject { put("action", "missions_pause_all") }) {
            json.decodeFromJsonElement(ListSerializer(Long.serializer()), it)
        }

    // ---- routines (claude-fleet redesign 8.5) ----
    //
    // `routines` is a write tool on the hub, so it is not listed for a
    // readonly token, and none of these is called unless `tools/list` names
    // it (`HubCapabilities.routines`). Its `action` is a free string.

    /** Every routine this person may see. */
    suspend fun routines(): List<Routine> =
        call("routines", buildJsonObject { put("action", "list") }) {
            json.decodeFromJsonElement(ListSerializer(Routine.serializer()), it)
        }

    /** One routine with its last runs (newest first) and whether this person may change it. */
    suspend fun routine(routineId: Long): RoutineDetail =
        call(
            "routines",
            buildJsonObject {
                put("action", "get")
                put("routine_id", routineId)
            },
        ) { json.decodeFromJsonElement(RoutineDetail.serializer(), it) }

    /** A routine's runs, newest first, at most [limit]. */
    suspend fun routineRuns(routineId: Long, limit: Int? = null): List<RoutineRun> =
        call(
            "routines",
            buildJsonObject {
                put("action", "runs")
                put("routine_id", routineId)
                limit?.let { put("limit", it) }
            },
        ) { json.decodeFromJsonElement(ListSerializer(RoutineRun.serializer()), it) }

    /** Turn a routine on or off; answers the routine as it now stands. */
    suspend fun setRoutineEnabled(routineId: Long, enabled: Boolean): Routine =
        call(
            "routines",
            buildJsonObject {
                put("action", "set_enabled")
                put("routine_id", routineId)
                put("enabled", enabled)
            },
        ) { json.decodeFromJsonElement(Routine.serializer(), it) }

    // ---- debug devices (claude-fleet contract revision 10) ----
    //
    // `debug_devices` is not readonly on the hub, so a readonly token is not
    // served it, and nothing here is called unless `tools/list` names it
    // (`HubCapabilities.debugDevices`). `device` takes the row's id.

    /** The test phones this person may see, and each host's last scan; [refresh] rescans hosts not scanned lately. */
    suspend fun debugDevices(refresh: Boolean = false): DebugDeviceList =
        call(
            "debug_devices",
            buildJsonObject {
                put("action", "list")
                if (refresh) put("refresh", true)
            },
        ) { json.decodeFromJsonElement(DebugDeviceList.serializer(), it) }

    /** Scan every host for attached and running devices now. What it found comes back through [debugDevices]. */
    suspend fun scanDebugDevices() {
        call("debug_devices", buildJsonObject { put("action", "scan") }) { it }
    }

    /** Hold [deviceId] for this device (`client:<name>`) so sessions keep off it. */
    suspend fun claimDebugDevice(deviceId: Long, note: String? = null): DebugDevice =
        deviceCall("claim", deviceId) { note?.let { put("note", it) } }

    /** Let go of [deviceId], whoever held it; a person may release any claim. */
    suspend fun releaseDebugDevice(deviceId: Long): DebugDevice = deviceCall("release", deviceId)

    /** Shut down an emulator or simulator. */
    suspend fun shutdownDebugDevice(deviceId: Long): DebugDevice = deviceCall("shutdown", deviceId)

    /** Start a stopped emulator or simulator; answers the hub's word for its state. */
    suspend fun bootDebugDevice(deviceId: Long): String =
        call(
            "debug_devices",
            buildJsonObject {
                put("action", "boot")
                put("device", deviceId.toString())
            },
        ) { (it as? JsonObject)?.get("state")?.let { s -> (s as? JsonPrimitive)?.content } ?: "" }

    /** The device's last [lines] log lines. */
    suspend fun debugDeviceLogs(deviceId: Long, lines: Int): DeviceOutput =
        call(
            "debug_devices",
            buildJsonObject {
                put("action", "logs")
                put("device", deviceId.toString())
                put("lines", lines)
            },
        ) { json.decodeFromJsonElement(DeviceOutput.serializer(), it) }

    private suspend fun deviceCall(
        action: String,
        deviceId: Long,
        more: JsonObjectBuilder.() -> Unit = {},
    ): DebugDevice =
        call(
            "debug_devices",
            buildJsonObject {
                put("action", action)
                put("device", deviceId.toString())
                more()
            },
        ) { json.decodeFromJsonElement(DebugDevice.serializer(), it) }

    // ---- org members (claude-fleet `org_admin`, company administration phase D) ----
    //
    // Only the member actions: the hub answers an org's admins (or the hub
    // owner) and refuses anyone else, and an admin cannot change their own
    // membership or the hub owner's. Nothing here pairs, binds or renames.

    /** The org's live members, admins first. */
    suspend fun orgMembers(orgId: Long): List<OrgMemberRow> =
        memberCall("list_members", orgId) { json.decodeFromJsonElement(ListSerializer(OrgMemberRow.serializer()), it) }

    /** Give [personId] [role] (`admin` / `member` / `viewer`) in [orgId]; answers the members after. */
    suspend fun setOrgMember(orgId: Long, personId: Long, role: String): List<OrgMemberRow> =
        memberCall("set_member", orgId, {
            put("person_id", personId)
            put("role", role)
        }) { json.decodeFromJsonElement(ListSerializer(OrgMemberRow.serializer()), it) }

    /** How many of [orgId]'s sessions are shared with [personId], to watch and to drive. */
    suspend fun memberGrants(orgId: Long, personId: Long): MemberGrants =
        memberCall("member_grants", orgId, { put("person_id", personId) }) { json.decodeFromJsonElement(MemberGrants.serializer(), it) }

    /** Turn [personId]'s drive shares on [orgId]'s sessions into watch shares; answers how many. */
    suspend fun narrowMemberGrants(orgId: Long, personId: Long): Int =
        memberCall("narrow_member_grants", orgId, { put("person_id", personId) }) { count(it, "narrowed") }

    /** Take [personId] out of [orgId]; their shares there go too unless [keepGrants]. */
    suspend fun removeOrgMember(orgId: Long, personId: Long, keepGrants: Boolean): MemberRemoved =
        memberCall("remove_member", orgId, {
            put("person_id", personId)
            if (keepGrants) put("keep_grants", true)
        }) { json.decodeFromJsonElement(MemberRemoved.serializer(), it) }

    private fun count(answer: JsonElement, key: String): Int =
        ((answer as? JsonObject)?.get(key) as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    private suspend fun <T> memberCall(
        action: String,
        orgId: Long,
        more: JsonObjectBuilder.() -> Unit = {},
        decode: (JsonElement) -> T,
    ): T =
        call(
            "org_admin",
            buildJsonObject {
                put("action", action)
                put("org_id", orgId)
                more()
            },
            decode,
        )

    // ---- the Work view (claude-fleet M14) ----

    /**
     * One page of the Work tree. [filters] travel as a JSON object and only
     * when one is set; [cursor] is the previous page's opaque `next_cursor`,
     * valid only with the same filters. [perTask] caps the sessions listed
     * under each task (0–50).
     */
    suspend fun workTree(
        filters: WorkTreeFilters = WorkTreeFilters(),
        cursor: String? = null,
        limit: Int? = null,
        perTask: Int? = null,
    ): WorkTreePage =
        call(
            "work",
            buildJsonObject {
                put("action", "tree")
                val f = filters.normalized()
                if (f != WorkTreeFilters()) put("filters", json.encodeToJsonElement(WorkTreeFilters.serializer(), f))
                cursor?.let { put("cursor", it) }
                limit?.let { put("limit", it) }
                perTask?.let { put("per_task", it) }
            },
        ) { json.decodeFromJsonElement(WorkTreePage.serializer(), it) }

    /**
     * Pull requests (claude-fleet redesign 6.4, `prs { list }`): every PR a
     * session's branch has had, newest first, only those whose opening
     * session this token may see. [state] is `open`, `merged`, `closed` or
     * `all`.
     */
    suspend fun listPullRequests(state: String = "open", limit: Int? = null): PullRequestList =
        call(
            "prs",
            buildJsonObject {
                put("action", "list")
                put("state", state)
                limit?.let { put("limit", it) }
            },
        ) { json.decodeFromJsonElement(PullRequestList.serializer(), it) }

    /** One task with every session it has (and their evidence), where its org and group come from. */
    suspend fun workTask(taskId: String): TaskDetail =
        call("work", buildJsonObject { put("action", "task"); put("task_id", taskId) }) {
            json.decodeFromJsonElement(TaskDetail.serializer(), it)
        }

    /** Every link of one session — live, suggested, rejected and ended — with its task. */
    suspend fun workSessionTasks(sessionId: Long): SessionTasks =
        call("work", buildJsonObject { put("action", "session_tasks"); put("session_id", sessionId) }) {
            json.decodeFromJsonElement(SessionTasks.serializer(), it)
        }

    /** The review inbox: suggestions and conflicts, page by page. */
    suspend fun workReview(cursor: String? = null, limit: Int? = null): ReviewPage =
        call(
            "work",
            buildJsonObject {
                put("action", "review")
                cursor?.let { put("cursor", it) }
                limit?.let { put("limit", it) }
            },
        ) { json.decodeFromJsonElement(ReviewPage.serializer(), it) }

    /** The placement rules. */
    suspend fun workRules(): List<WorkRule> =
        call("work", buildJsonObject { put("action", "rules") }) {
            json.decodeFromJsonElement(ListSerializer(WorkRule.serializer()), it)
        }

    /** What saving [rule] would move, before it is saved. */
    suspend fun workRulePreview(rule: WorkRuleDraft): RulePreview =
        call(
            "work",
            buildJsonObject {
                put("action", "rule_preview")
                put("rule", json.encodeToJsonElement(WorkRuleDraft.serializer(), rule))
            },
        ) { json.decodeFromJsonElement(RulePreview.serializer(), it) }

    /** The saved views this token sees. */
    suspend fun workViews(): List<WorkView> =
        call("work", buildJsonObject { put("action", "views") }) {
            json.decodeFromJsonElement(ListSerializer(WorkView.serializer()), it)
        }

    /** What moving local task [taskId] to org [orgId] (`0` = none) would change, and the token to do it. */
    suspend fun workOrgImpact(taskId: String, orgId: Long): OrgImpact =
        call(
            "work",
            buildJsonObject {
                put("action", "org_impact")
                put("task_id", taskId)
                put("org_id", orgId)
            },
        ) { json.decodeFromJsonElement(OrgImpact.serializer(), it) }

    /**
     * Accept a suggestion: it becomes the session's work. Answers the updated
     * row. [primary] false (claude-fleet M14) confirms it as a *secondary*
     * link; null leaves the hub's default, which takes the primary.
     * [expectedVersion] is the link's `link_version`: a mismatch is refused
     * with `E_CONFLICT` and changes nothing.
     */
    suspend fun confirmWork(
        sessionId: Long,
        linkId: Long,
        primary: Boolean? = null,
        expectedVersion: Long? = null,
    ): SessionRow =
        workLink("confirm", sessionId) {
            put("link_id", linkId)
            primary?.let { put("primary", it) }
            expectedVersion?.let { put("expected_version", it) }
        }

    /** "Not this": a sticky rejection of one suggestion. Answers the updated row. */
    suspend fun rejectWork(sessionId: Long, linkId: Long, expectedVersion: Long? = null): SessionRow =
        workLink("reject", sessionId) {
            put("link_id", linkId)
            expectedVersion?.let { put("expected_version", it) }
        }

    /** Clear a live link. Answers the updated row. */
    suspend fun unlinkWork(sessionId: Long, linkId: Long, expectedVersion: Long? = null): SessionRow =
        workLink("unlink", sessionId) {
            put("link_id", linkId)
            expectedVersion?.let { put("expected_version", it) }
        }

    /**
     * Make [linkId] the session's primary link (claude-fleet M14): a
     * compare-and-set on the current primary, [expectedPrimary] its link id
     * (`0` = none). Another device having moved it first answers `E_CONFLICT`
     * naming the current one. Never ends or deletes another link.
     */
    suspend fun setPrimaryWork(sessionId: Long, linkId: Long, expectedPrimary: Long? = null): SessionRow =
        workLink("set_primary", sessionId) {
            put("link_id", linkId)
            expectedPrimary?.let { put("expected_primary", it) }
        }

    /** Undo a person's confirm / reject: the link goes back to a suggestion. */
    suspend fun reconsiderWork(sessionId: Long, linkId: Long, expectedVersion: Long? = null): SessionRow =
        workLink("reconsider", sessionId) {
            put("link_id", linkId)
            expectedVersion?.let { put("expected_version", it) }
        }

    /** Keep a conflict (cross-org, unavailable ticket) on purpose: it leaves the review inbox. */
    suspend fun ackWork(sessionId: Long, linkId: Long, expectedVersion: Long? = null): SessionRow =
        workLink("ack", sessionId) {
            put("link_id", linkId)
            expectedVersion?.let { put("expected_version", it) }
        }

    /**
     * Several review decisions in one call (at most 100). Each is checked on
     * its own against scope and version, so the answer is per item: some may
     * succeed while others are refused.
     */
    suspend fun decideWorkBatch(decisions: List<WorkDecision>): BatchResult =
        call(
            "work_link",
            buildJsonObject {
                put("action", "decide_batch")
                put("decisions", json.encodeToJsonElement(ListSerializer(WorkDecision.serializer()), decisions))
            },
        ) { json.decodeFromJsonElement(BatchResult.serializer(), it) }

    /**
     * Put [taskId] in the group labelled [group] — a local placement only;
     * fleet never edits a tracker. An empty [group] clears the placement.
     * [expectedVersion] is the task's `placement_version` (`0` = "I expect
     * none"), so two devices placing at once cannot overwrite each other.
     */
    suspend fun placeWork(taskId: String, group: String, expectedVersion: Long, note: String? = null): WorkTask =
        call(
            "work_link",
            buildJsonObject {
                put("action", "place")
                put("task_id", taskId)
                put("group", group)
                note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
                put("expected_version", expectedVersion)
            },
        ) { json.decodeFromJsonElement(WorkTask.serializer(), it) }

    /**
     * Move a local task to org [orgId] (`0` = none). Only with the
     * `impact_token` of a fresh [workOrgImpact]: the hub recomputes the impact
     * and refuses with `E_CONFLICT` when it changed.
     */
    suspend fun assignWorkOrg(taskId: String, orgId: Long, impactToken: String): WorkTask =
        call(
            "work_link",
            buildJsonObject {
                put("action", "assign_org")
                put("task_id", taskId)
                put("org_id", orgId)
                put("impact_token", impactToken)
            },
        ) { json.decodeFromJsonElement(WorkTask.serializer(), it) }

    /** Create or change a placement rule. */
    suspend fun saveWorkRule(rule: WorkRuleDraft): WorkRule =
        call(
            "work_link",
            buildJsonObject {
                put("action", "rule_save")
                put("rule", json.encodeToJsonElement(WorkRuleDraft.serializer(), rule))
            },
        ) { json.decodeFromJsonElement(WorkRule.serializer(), it) }

    suspend fun deleteWorkRule(ruleId: Long, expectedVersion: Long? = null) {
        call(
            "work_link",
            buildJsonObject {
                put("action", "rule_delete")
                put("rule_id", ruleId)
                expectedVersion?.let { put("expected_version", it) }
            },
        ) { }
    }

    /** Save a view: new without an id, else changed under its version. */
    suspend fun saveWorkView(view: WorkViewDraft): WorkView =
        call(
            "work_link",
            buildJsonObject {
                put("action", "view_save")
                put("view", json.encodeToJsonElement(WorkViewDraft.serializer(), view.copy(filters = view.filters.normalized().copy(group = null))))
            },
        ) { json.decodeFromJsonElement(WorkView.serializer(), it) }

    suspend fun deleteWorkView(viewId: Long) {
        call(
            "work_link",
            buildJsonObject {
                put("action", "view_delete")
                put("view_id", viewId)
            },
        ) { }
    }

    /**
     * Ask the session's Claude to write a handover for its work (claude-fleet
     * M9.3). Asynchronous on the hub: the prompt is typed into the idle
     * session and this answers at once; the note is stored when the turn
     * stops, and `handover_*` timeline frames say how it went.
     */
    suspend fun handoverWork(sessionId: Long): SessionRow = workLink("handover", sessionId) {}

    /**
     * **Name this work…**: new local work (a title, no ticket) linked to the
     * session (claude-fleet M11.1). [key] is left out when null or blank.
     * Answers the updated row.
     */
    suspend fun nameWork(sessionId: Long, title: String, key: String? = null): SessionRow =
        workLink("name", sessionId) {
            put("title", title)
            key?.trim()?.takeIf { it.isNotEmpty() }?.let { put("key", it) }
        }

    /**
     * Rename a local work item. The hub answers the item; nothing on the phone
     * reads it — the rows that show the item change by their own
     * `session:updated` frames.
     */
    suspend fun renameWorkItem(itemId: Long, title: String) {
        call(
            "work_link",
            buildJsonObject {
                put("action", "name")
                put("item_id", itemId)
                put("title", title)
            },
        ) { }
    }

    /**
     * Set the session's work by item id (a looked-up ticket) or by bare key.
     * [primary] false (claude-fleet M14) adds a *secondary* link and leaves
     * the primary alone; null is the hub's default, which takes the primary.
     *
     * [shareAcrossOrgs] is the one place the phone passes `force_cross_org`:
     * a link the person chose to share across organisations after the hub
     * refused it and the phone said why (D15, as the owner narrowed it on
     * 2026-09-28 — a start, multi-start or resume never forces).
     */
    suspend fun linkWork(
        sessionId: Long,
        itemId: Long? = null,
        key: String? = null,
        primary: Boolean? = null,
        expectedVersion: Long? = null,
        shareAcrossOrgs: Boolean = false,
    ): SessionRow =
        workLink("link", sessionId) {
            if (itemId != null) put("item_id", itemId) else put("key", key.orEmpty())
            primary?.let { put("primary", it) }
            expectedVersion?.let { put("expected_version", it) }
            if (shareAcrossOrgs) put(FORCE_CROSS_ORG, true)
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

    /**
     * Start work on [key] in several repositories at once (claude-fleet M9.6,
     * `work_link start { project_ids }`): one sibling session per project on
     * [hostAlias], all on one branch name. The answer says, per project, what
     * started, what was skipped and what was refused.
     *
     * Never with `force_cross_org`: a repository the org rule refuses comes
     * back in [MultiStart.failed], and the phone says so and stops there
     * (decision D15). No brief, as with [startWork].
     */
    suspend fun startWorkMany(key: String, hostAlias: String, projectIds: List<Long>): MultiStart =
        call(
            "work_link",
            buildJsonObject {
                put("action", "start")
                put("key", key)
                put("host_alias", hostAlias)
                putJsonArray("project_ids") { projectIds.forEach { add(it) } }
            },
        ) { json.decodeFromJsonElement(MultiStart.serializer(), it) }

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
     * Is this hub reachable and its store open, right now — and which version
     * is it running.
     *
     * `fleet_health` is in the hub's readonly allow-list — a paired client
     * may always ask, even one that cannot `send_prompt` — which is what
     * makes it the probe [dev.claudefleet.mobile.data.SessionActions.ping]
     * uses to tell an unreachable hub from a merely-dropped `/events` stream.
     * The same answer carries [HubHealth.version], which is why Settings has
     * a hub version to show beside the app's without a second tool.
     */
    suspend fun fleetHealth(): HubHealth =
        call("fleet_health") { json.decodeFromJsonElement(HubHealth.serializer(), it) }

    // ---- file downloads (claude-fleet contract revision 7) ----
    //
    // `list_downloads` is a read; `send_file` and `remove_download` are
    // writes the hub refuses a readonly token (and does not list for one).
    // None is called unless `tools/list` names it — see
    // `HubCapabilities.downloads`.

    /** The hub's copies, newest first. [sessionId] narrows to one session; [limit] caps the rows. */
    suspend fun listDownloads(sessionId: Long? = null, limit: Int? = null): DownloadList =
        call(
            "list_downloads",
            buildJsonObject {
                sessionId?.let { put("session_id", it) }
                limit?.let { put("limit", it) }
            },
        ) { json.decodeFromJsonElement(DownloadList.serializer(), it) }

    /**
     * Ask the hub to copy [path] off [sessionId]'s host. Answers the new row
     * at once, in state `fetching`; `download:changed` says when it is ready.
     * [path] is absolute or relative to the session's working directory. A
     * blank [note] is left out.
     */
    suspend fun sendFile(sessionId: Long, path: String, note: String? = null): Download =
        call(
            "send_file",
            buildJsonObject {
                put("session_id", sessionId)
                put("path", path)
                note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
            },
        ) { json.decodeFromJsonElement(Download.serializer(), it) }

    /** Forget one copy on the hub. False when it was already gone. */
    suspend fun removeDownload(id: Long): Boolean =
        call("remove_download", buildJsonObject { put("id", id) }) {
            json.decodeFromJsonElement(DownloadRemoved.serializer(), it).removed
        }

    /**
     * Stream `GET /downloads/<id>` into [sink], hashing as it goes.
     *
     * Not a tool call, and deliberately outside both of a call's ceilings: a
     * file is up to the hub's `downloads.max_file_mb` (100 MB by default), so
     * the bytes are never held whole — not [MAX_RESPONSE_BYTES], which bounds
     * what is read into a `String`, and not the client-wide request deadline,
     * which would give up on a large file over a slow link that is going fine.
     * The socket timeout stays bounded, so a connection gone half-open still
     * fails.
     *
     * What it checks, with what the hub said: the byte count against
     * `Content-Length` (or, without one, against [expectedSize], the row's
     * `size`), and the digest against `X-Fleet-Sha256` when it is sent. A
     * mismatch is [HubError.Damaged]; the caller deletes what it wrote.
     *
     * `404` — the row is not visible to this token, not `ready`, or gone — is
     * [HubError.Tool] with [DOWNLOAD_GONE], in words a person can act on.
     */
    suspend fun downloadFile(
        id: Long,
        sink: Sink,
        expectedSize: Long? = null,
        onProgress: (received: Long, total: Long?) -> Unit = { _, _ -> },
    ): FetchedFile = try {
        http.prepareGet("$base/downloads/$id") {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HUB_CALL_TIMEOUT_MS
            }
        }.execute { response ->
            val status = response.status.value
            if (status == 404) {
                throw HubError.Tool(DOWNLOAD_GONE, "That file is no longer on the hub: it expired, was removed, or is not ready yet.")
            }
            if (status !in 200..299) throwForStatus(status, response.textWithin(MAX_RESPONSE_BYTES), base, token)
            val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val limit = declared ?: expectedSize
            val expectedSha = response.headers[SHA256_HEADER]?.trim()?.lowercase()?.takeIf { it.length == 64 }
            val channel = response.bodyAsChannel()
            val hash = Sha256()
            val buffer = ByteArray(DOWNLOAD_CHUNK)
            var received = 0L
            while (true) {
                val n = channel.readAvailable(buffer, 0, buffer.size)
                if (n < 0) break
                if (n == 0) continue
                received += n
                if (limit != null && received > limit) throw HubError.Damaged("more bytes than the hub announced ($limit)")
                hash.update(buffer, 0, n)
                sink.write(buffer, 0, n)
                onProgress(received, limit)
            }
            sink.flush()
            if (limit != null && received != limit) {
                throw HubError.Damaged("$received of $limit bytes arrived")
            }
            val digest = hash.hex()
            if (expectedSha != null && digest != expectedSha) throw HubError.Damaged("its checksum does not match the hub's")
            FetchedFile(bytes = received, sha256 = digest)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HubError) {
        throw e
    } catch (t: Throwable) {
        throw HubError.Transport(t)
    }

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
         * same mount costs nothing but the framing. `ensure_operator` is
         * `Deadline::Lifecycle` on the hub too: the first call starts Claude.
         */
        val LIFECYCLE_TOOLS = setOf(
            "new_session",
            "work_link",
            "ensure_operator",
            // Every other tool this app calls that the hub bounds at
            // `Deadline::Lifecycle` (`TOOL_POLICIES` in its `guard.rs`): each
            // runs on a host over SSH, some for minutes — a restore of many
            // sessions, a recreate, a move. Left on the ordinary deadline they
            // gave up at 45 s on work the hub was still doing.
            "rewind_conversation",
            "spawn_review",
            "recreate_session",
            "repair_session",
            "new_bg_session",
            "restore_host_sessions",
            "discover_lost_sessions",
            "probe_host",
            "usage_report",
            "delete_worktree",
            "move_session",
            "new_shell_session",
            // Routines (redesign 8.9): `Deadline::Lifecycle` on the hub,
            // because `run_now` starts a session.
            "routines",
        )

        /**
         * Tools the hub bounds at its `LONG_POLL_CAP` (660 s): `add_project`
         * may clone a large repository. [HUB_LONG_POLL_TIMEOUT_MS] is half a
         * minute above that, for the same reason as [HUB_LIFECYCLE_TIMEOUT_MS].
         */
        val LONG_POLL_TOOLS = setOf(
            "add_project",
            // `ask` is the hub's `Deadline::LongPoll` too: an answer that
            // carries a secret is written to the session's host over SSH.
            "ask",
            // Debug devices: `Deadline::LongPoll` on the hub. A scan, a boot
            // or a log read runs over SSH on the device's host.
            "debug_devices",
        )
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

/**
 * The org rule's override (claude-fleet M5). Named once, here, and sent only
 * by [HubClient.linkWork]: `ToolsTheAppMayCallTest` holds every other source
 * to never writing it.
 */
internal const val FORCE_CROSS_ORG = "force_cross_org"

/** What [HubClient.downloadFile] wrote: how many bytes, and their SHA-256 (hex). */
data class FetchedFile(val bytes: Long, val sha256: String)

/**
 * The code a `404` on `GET /downloads/<id>` becomes: the row is gone,
 * expired, not ready, or not this token's to see — the hub does not say
 * which, and to a person they are one thing.
 */
const val DOWNLOAD_GONE = "E_NOTFOUND"

/** The hub's digest of the file it serves, hex. */
internal const val SHA256_HEADER = "X-Fleet-Sha256"

/** How much of a download is read per step: enough to keep a fast link busy, small enough for any heap. */
private const val DOWNLOAD_CHUNK = 64 * 1024

internal const val HUB_CALL_TIMEOUT_MS = 45_000L
internal const val HUB_CONNECT_TIMEOUT_MS = 15_000L

/**
 * The deadline for a [HubClient] call the hub itself bounds at five minutes
 * (`LIFECYCLE_CAP`). Half a minute above it, so the hub's own timeout — which
 * comes back as an answer — always lands before this one, which can only say
 * the connection went.
 */
internal const val HUB_LIFECYCLE_TIMEOUT_MS = 330_000L

/** The deadline for a call the hub bounds at `LONG_POLL_CAP` (660 s): half a minute above it. */
internal const val HUB_LONG_POLL_TIMEOUT_MS = 690_000L

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
