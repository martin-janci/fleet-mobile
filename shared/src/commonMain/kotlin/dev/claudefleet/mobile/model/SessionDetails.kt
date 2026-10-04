package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry of a session's event timeline (`session_history`): a status
 * change, a prompt, a stuck, a kill, a conversation event. [at] is Unix
 * seconds; [detail] is free text, sometimes JSON, never parsed here.
 */
@Serializable
data class SessionEvent(
    val id: Long,
    @SerialName("session_id") val sessionId: Long = 0,
    val at: Long = 0,
    val kind: String = "",
    val detail: String? = null,
    @SerialName("claude_session_id") val claudeSessionId: String? = null,
)

/**
 * One fleet task (`list_tasks`): a prompt one session asked another to work
 * on. [state] is the hub's word — queued, running, done, failed, cancelled.
 */
@Serializable
data class FleetTask(
    val id: Long,
    @SerialName("requester_session_id") val requesterSessionId: Long? = null,
    @SerialName("worker_session_id") val workerSessionId: Long? = null,
    val prompt: String? = null,
    val state: String = "",
    val result: String? = null,
    val error: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("finished_at") val finishedAt: Long? = null,
) {
    /** Still going: a cancel would mean something. */
    val open: Boolean get() = state == "queued" || state == "running"
}

/**
 * One Claude conversation a session has run (`session_conversations`),
 * newest first: a `/clear`, a `/resume`, a compaction or a rewind starts a
 * new one in the same session. [current] is the one the session is in now.
 */
@Serializable
data class ConversationSummary(
    @SerialName("claude_session_id") val claudeSessionId: String,
    @SerialName("started_at") val startedAt: Long = 0,
    @SerialName("ended_at") val endedAt: Long? = null,
    /** startup, resume, clear, compact, fork, fleet, unknown. */
    @SerialName("start_source") val startSource: String = "",
    @SerialName("end_reason") val endReason: String? = null,
    val model: String? = null,
    @SerialName("first_prompt") val firstPrompt: String? = null,
    val turns: Long = 0,
    val compactions: Long = 0,
    val current: Boolean = false,
)
