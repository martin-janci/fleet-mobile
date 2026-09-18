package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.SendPromptResult

/**
 * The two calls a session screen may make.
 *
 * Narrow on purpose. A screen handed a [dev.claudefleet.mobile.net.HubClient]
 * could call anything the hub offers and, worse, could make an authenticated
 * call that never passes through [AppSession.withClient] — which is where "a
 * 401 drops the credential and returns to Pair" lives. Handing it these two
 * methods instead makes that impossible to forget rather than merely
 * discouraged, and makes the view-model tests transport-free.
 *
 * Both are tools a paired client token may call: `session_conversation` is in
 * the hub's readonly allow-list, and `send_prompt` is fleet-wide session
 * control, which a `full` client has and a `readonly` one does not (see
 * `SessionViewModel`'s `canSendPrompts`).
 */
interface SessionActions {
    /** A session's recent exchange. [turns] left null keeps the hub's default of 10. */
    suspend fun conversation(sessionId: Long, turns: Int? = null): Conversation

    /** Deliver [text] to the session's REPL and submit it. */
    suspend fun sendPrompt(sessionId: Long, text: String): SendPromptResult
}

/**
 * [SessionActions] against the paired hub, through [AppSession.withClient] so
 * that a 401 on either call drops the credential exactly once, in one place.
 */
class HubSessionActions(private val session: AppSession) : SessionActions {
    override suspend fun conversation(sessionId: Long, turns: Int?): Conversation =
        session.withClient { it.conversation(sessionId, turns) }

    override suspend fun sendPrompt(sessionId: Long, text: String): SendPromptResult =
        session.withClient { it.sendPrompt(sessionId, text) }
}
