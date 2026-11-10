package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.ReviewRequest

/** The reviews colleagues asked of this person (claude-fleet "Ask for review…"). */
interface ReviewRequestActions {
    /** The open asks to this person, newest first. */
    suspend fun list(): List<ReviewRequest>

    /** Mark one done: it leaves the Inbox. */
    suspend fun done(id: Long): ReviewRequest
}

/** [ReviewRequestActions] against the paired hub, through [AppSession.withClient]. */
class HubReviewRequestActions(private val session: AppSession) : ReviewRequestActions {
    override suspend fun list(): List<ReviewRequest> = session.withClient { it.reviewRequests() }

    override suspend fun done(id: Long): ReviewRequest = session.withClient { it.finishReviewRequest(id) }
}
