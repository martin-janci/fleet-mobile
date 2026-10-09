package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.ConfirmRequest
import dev.claudefleet.mobile.model.ControlHandoff
import dev.claudefleet.mobile.model.OperatorStatus

/**
 * Control on the phone (redesign 9.8 and 14.7): its state without waking it,
 * and the calls it waits on a person's yes for. Waking it and its
 * conversation stay with [AgentActions] and the session tools.
 */
interface ControlActions {
    suspend fun status(): OperatorStatus
    suspend fun confirms(): List<ConfirmRequest>
    suspend fun answer(nonce: String, approved: Boolean): Boolean
    suspend fun handoffs(limit: Int): List<ControlHandoff>
}

/** [ControlActions] against the paired hub, through [AppSession.withClient]. */
class HubControlActions(private val session: AppSession) : ControlActions {
    override suspend fun status(): OperatorStatus = session.withClient { it.operatorStatus() }
    override suspend fun confirms(): List<ConfirmRequest> = session.withClient { it.mcpConfirms() }
    override suspend fun answer(nonce: String, approved: Boolean): Boolean = session.withClient { it.answerMcpConfirm(nonce, approved) }
    override suspend fun handoffs(limit: Int): List<ControlHandoff> = session.withClient { it.controlHandoffs(limit) }
}
