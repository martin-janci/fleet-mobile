package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What `queue_prompt` did with one prompt (claude-fleet 5.10, deferred
 * prompts): typed now because the session was idle ([delivered]), or kept
 * for its next idle moment as the `deferred_prompts` row [queuedId].
 */
@Serializable
data class QueuePromptResult(
    @SerialName("session_id") val sessionId: Long,
    val delivered: Boolean = false,
    @SerialName("queued_id") val queuedId: Long? = null,
)

/** One prompt `queue_prompt` kept (`queued_prompts`): still waiting, or one whose typing failed. */
@Serializable
data class QueuedPrompt(
    val id: Long,
    @SerialName("session_id") val sessionId: Long,
    val body: String,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("delivered_at") val deliveredAt: Long? = null,
    val attempts: Long = 0,
    @SerialName("failed_at") val failedAt: Long? = null,
    val error: String? = null,
    @SerialName("cancelled_at") val cancelledAt: Long? = null,
) {
    /** Waiting for the session's next idle moment. */
    val waiting: Boolean get() = deliveredAt == null && failedAt == null && cancelledAt == null
}
