package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.QuickReply

/**
 * The one call the chip row may make: read the fleet's quick replies, or
 * replace them.
 *
 * Its own interface for the same reason [SessionActions] is one — a 401 has to
 * go through [AppSession.withClient] exactly once, in one place, and the view
 * models that edit chips should be testable without a transport. Narrow to a
 * single method because the hub's tool is one tool: the answer to a write is
 * the stored list, so "save" and "read back" are the same round trip.
 */
interface QuickReplyActions {
    /**
     * Read the list when [set] is null; replace it when [set] is given.
     * Answers the list as the hub stores it, which is not always the list
     * handed in: it trims, drops a second chip with the same prompt, and
     * answers its built-in defaults for an empty list.
     */
    suspend fun quickReplies(set: List<QuickReply>? = null): List<QuickReply>
}

/** [QuickReplyActions] against the paired hub, through [AppSession.withClient]. */
class HubQuickReplyActions(private val session: AppSession) : QuickReplyActions {
    override suspend fun quickReplies(set: List<QuickReply>?): List<QuickReply> =
        session.withClient { it.quickReplies(set) }
}
