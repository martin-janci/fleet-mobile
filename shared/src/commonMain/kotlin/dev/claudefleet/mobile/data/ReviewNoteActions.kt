package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.NoteDraft
import dev.claudefleet.mobile.model.ReviewNote
import dev.claudefleet.mobile.model.ReviewNotesSent

/** Notes on lines of a session's diff (claude-fleet review notes). */
interface ReviewNoteActions {
    suspend fun list(sessionId: Long): List<ReviewNote>

    suspend fun send(sessionId: Long, clientMsgId: String, drafts: List<NoteDraft>): ReviewNotesSent

    suspend fun resolve(sessionId: Long, noteId: Long, resolved: Boolean): ReviewNote
}

/** [ReviewNoteActions] against the paired hub, through [AppSession.withClient]. */
class HubReviewNoteActions(private val session: AppSession) : ReviewNoteActions {
    override suspend fun list(sessionId: Long): List<ReviewNote> = session.withClient { it.reviewNotes(sessionId) }

    override suspend fun send(sessionId: Long, clientMsgId: String, drafts: List<NoteDraft>): ReviewNotesSent =
        session.withClient { it.sendReviewNotes(sessionId, clientMsgId, drafts) }

    override suspend fun resolve(sessionId: Long, noteId: Long, resolved: Boolean): ReviewNote =
        session.withClient { it.resolveReviewNote(sessionId, noteId, resolved) }
}
