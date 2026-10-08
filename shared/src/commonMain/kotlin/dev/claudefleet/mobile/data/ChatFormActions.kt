package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.FormView
import kotlinx.serialization.json.JsonElement

/**
 * A session's chat form (`ask`, claude-fleet contract revision 9): read it,
 * answer it, decline it. Its own interface, as [FleetSettingsActions] is, so
 * the card's model is testable without a transport.
 */
interface ChatFormActions {
    suspend fun get(formId: String): FormView
    suspend fun answer(formId: String, values: Map<String, JsonElement>): FormView
    suspend fun decline(formId: String, note: String?): FormView
}

/** [ChatFormActions] against the paired hub. */
class HubChatFormActions(private val session: AppSession) : ChatFormActions {
    override suspend fun get(formId: String): FormView = session.withClient { it.askGet(formId) }
    override suspend fun answer(formId: String, values: Map<String, JsonElement>): FormView =
        session.withClient { it.askAnswer(formId, values) }
    override suspend fun decline(formId: String, note: String?): FormView =
        session.withClient { it.askDecline(formId, note) }
}
