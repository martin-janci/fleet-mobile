package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.SendPromptResult
import dev.claudefleet.mobile.net.HubError
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
 * `session_conversation` and `fleet_health` are in the hub's readonly
 * allow-list, and `send_prompt` is fleet-wide session control, which a `full`
 * client has and a `readonly` one does not (see `SessionViewModel`'s
 * `canSendPrompts`).
 */
interface SessionActions {
    /** A session's recent exchange. [turns] left null keeps the hub's default of 10. */
    suspend fun conversation(sessionId: Long, turns: Int? = null): Conversation

    /** Deliver [text] to the session's REPL and submit it. */
    suspend fun sendPrompt(sessionId: Long, text: String): SendPromptResult

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
    override suspend fun conversation(sessionId: Long, turns: Int?): Conversation =
        session.withClient { it.conversation(sessionId, turns) }

    override suspend fun sendPrompt(sessionId: Long, text: String): SendPromptResult =
        session.withClient { it.sendPrompt(sessionId, text) }

    override suspend fun ping(): Boolean = try {
        session.withClient { it.fleetHealth() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HubError) {
        false
    }
}
