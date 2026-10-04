package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.ActivityProbe
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.ConversationSummary
import dev.claudefleet.mobile.model.SendPromptResult
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.ToolDetail
import dev.claudefleet.mobile.model.WaitResult
import kotlinx.coroutines.CancellationException

/**
 * The calls a session screen may make.
 *
 * Narrow on purpose. A screen handed a [dev.claudefleet.mobile.net.HubClient]
 * could call anything the hub offers and, worse, could make an authenticated
 * call that never passes through [AppSession.withClient] — which is where "a
 * 401 drops the credential and returns to Pair" lives. Handing it these
 * methods instead makes that impossible to forget rather than merely
 * discouraged, and makes the view-model tests transport-free.
 *
 * Every one of these is a tool a paired client token may call:
 * `session_conversation`, `session_tool_detail`, `session_activity` and `fleet_health` are in the hub's readonly
 * allow-list, and `send_prompt` is fleet-wide session control, which a `full`
 * client has and a `readonly` one does not (see `SessionViewModel`'s
 * `canSendPrompts`).
 */
interface SessionActions {
    /**
     * A session's recent exchange. [turns] left null keeps the hub's default
     * of 10.
     *
     * [sinceTurn] is the `turn_seq` the caller already drew: the hub then
     * answers the turns completed since, plus the one still running, instead
     * of the last ten every time. [turns] wins over it on both sides, so a
     * screen asking for a wider window still gets one.
     */
    suspend fun conversation(
        sessionId: Long,
        turns: Int? = null,
        sinceTurn: Long? = null,
        /** An earlier conversation of the session (from [conversations]); null is the current one. */
        claudeSessionId: String? = null,
    ): Conversation

    /** The Claude conversations the session has run, newest first (`session_conversations`). */
    suspend fun conversations(sessionId: Long): List<ConversationSummary>

    /**
     * One tool call's input and result (`session_tool_detail`, readonly), for
     * an expanded tool row. Only called when the hub lists the tool — see
     * [dev.claudefleet.mobile.net.HubCapabilities.toolDetail].
     */
    suspend fun toolDetail(sessionId: Long, toolUseId: String, claudeSessionId: String? = null): ToolDetail

    /** Deliver [text] to the session's REPL and submit it. */
    suspend fun sendPrompt(sessionId: Long, text: String): SendPromptResult

    /**
     * Press one key (`"Enter"` | `"Escape"` | `"C-c"`, or a digit `"1"`–`"9"`
     * that picks a dialog's option) instead of typing text.
     */
    suspend fun sendKeys(sessionId: Long, key: String): SendPromptResult

    /** The pane as it is right now (`session_activity`), to re-check a dialog before answering it. */
    suspend fun activity(sessionId: Long): ActivityProbe

    /** The visible tmux pane, capped to [maxLines] lines. */
    suspend fun capture(sessionId: Long, maxLines: Int = 40): String

    /** Block until [sessionId]'s turn counter passes [turn], or [timeoutS] elapses. */
    suspend fun waitForTurn(sessionId: Long, turn: Long, timeoutS: Int = 30): WaitResult

    /** Kill and recreate the tmux session in place — for a wedged REPL. */
    suspend fun restart(sessionId: Long)

    /**
     * `rewind_conversation`: [mode] `"rewind"` restarts this session on its
     * transcript cut before [anchorUuid]; `"fork"` starts a new session on
     * the copy, in a new worktree of [newWorktree]'s name when given.
     * Answers the row — a fork's is the new session.
     */
    suspend fun rewind(sessionId: Long, anchorUuid: String?, mode: String, newWorktree: String? = null): SessionRow

    /** Ask the session to persist its work, then arm deletion once it is clean. */
    suspend fun safeKill(sessionId: Long)

    /** Kill the session now, without waiting for it to persist anything. */
    suspend fun kill(sessionId: Long)

    /** Replace the session's tags. */
    suspend fun setTags(sessionId: Long, tags: List<String>)

    /** Set the session's friendly display name. */
    suspend fun rename(sessionId: Long, friendlyName: String)

    /**
     * Is the hub itself reachable, right now — independent of whether
     * `/events` happens to be connected.
     *
     * A dropped stream is not the same fact as an unreachable hub, and Send
     * should follow the hub, not the stream: [SessionViewModel] probes this
     * whenever [FleetState.status] is anything but `Connected`, so a phone
     * that lost `/events` but can still reach `/mcp` keeps Send enabled.
     * Never throws — every [HubError] becomes `false` — because a probe is a
     * question, not a call whose failure needs explaining on a banner.
     */
    suspend fun ping(): Boolean
}

/**
 * [SessionActions] against the paired hub, through [AppSession.withClient] so
 * that a 401 on any call drops the credential exactly once, in one place.
 */
class HubSessionActions(private val session: AppSession) : SessionActions {
    override suspend fun conversation(
        sessionId: Long,
        turns: Int?,
        sinceTurn: Long?,
        claudeSessionId: String?,
    ): Conversation = session.withClient { it.conversation(sessionId, turns, sinceTurn, claudeSessionId) }

    override suspend fun conversations(sessionId: Long): List<ConversationSummary> =
        session.withClient { it.conversations(sessionId) }

    override suspend fun toolDetail(sessionId: Long, toolUseId: String, claudeSessionId: String?): ToolDetail =
        session.withClient { it.toolDetail(sessionId, toolUseId, claudeSessionId) }

    override suspend fun sendPrompt(sessionId: Long, text: String): SendPromptResult =
        session.withClient { it.sendPrompt(sessionId, text) }

    override suspend fun sendKeys(sessionId: Long, key: String): SendPromptResult =
        session.withClient { it.sendKeys(sessionId, key) }

    override suspend fun activity(sessionId: Long): ActivityProbe =
        session.withClient { it.activity(sessionId) }

    override suspend fun capture(sessionId: Long, maxLines: Int): String =
        session.withClient { it.capture(sessionId, maxLines) }

    override suspend fun waitForTurn(sessionId: Long, turn: Long, timeoutS: Int): WaitResult =
        session.withClient { it.waitForTurn(sessionId, turn, timeoutS) }

    override suspend fun restart(sessionId: Long) =
        session.withClient { it.restart(sessionId) }

    override suspend fun rewind(sessionId: Long, anchorUuid: String?, mode: String, newWorktree: String?): SessionRow =
        session.withClient { it.rewind(sessionId, anchorUuid, mode, newWorktree) }

    override suspend fun safeKill(sessionId: Long) =
        session.withClient { it.safeKill(sessionId) }

    override suspend fun kill(sessionId: Long) =
        session.withClient { it.kill(sessionId) }

    override suspend fun setTags(sessionId: Long, tags: List<String>) =
        session.withClient { it.setTags(sessionId, tags) }

    override suspend fun rename(sessionId: Long, friendlyName: String) =
        session.withClient { it.rename(sessionId, friendlyName) }

    // `Throwable`, not `HubError`. The interface promises this never throws,
    // and `HubError` is only *most* of what can come back: a payload the
    // model cannot decode, a store that will not hand over the credential,
    // anything a client plugin raises. One of those escaping turned a probe —
    // a question whose whole contract is that it answers true or false — into
    // an unhandled failure inside the screen's own probe loop, which is the
    // one caller with no catch of its own.
    override suspend fun ping(): Boolean = try {
        // `fleetHealth()` answers the hub's version too (Settings draws it
        // through `VersionActions`); the probe is the store-open half of it.
        session.withClient { it.fleetHealth() }.dbReady
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        false
    }
}
