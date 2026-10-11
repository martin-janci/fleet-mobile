package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One hit of the hub's full-text `search` (claude-fleet search phase 3): a
 * task or ticket, a session, a conversation's first prompt, a pull request,
 * a journal entry or — when the hub indexes transcripts — a passage of what
 * was said. The hub fences every hit for this device's person.
 */
@Serializable
data class SearchHit(
    /** `item`, `session`, `conversation`, `pr`, `journal` or `transcript`; a newer hub may add more. */
    val kind: String,
    /** The source row's id (a transcript passage: `<claude id>:<offset>`). */
    val ref: String,
    val title: String,
    /** `[start, end)` in UTF-16 units, what a Kotlin string indexes by. */
    @SerialName("title_marks") val titleMarks: List<List<Int>> = emptyList(),
    val snippet: String = "",
    @SerialName("snippet_marks") val snippetMarks: List<List<Int>> = emptyList(),
    /** Unix seconds. */
    val at: Long = 0,
    @SerialName("session_id") val sessionId: Long? = null,
    @SerialName("session_name") val sessionName: String? = null,
    @SerialName("host_alias") val hostAlias: String? = null,
    /** An item hit: `item:<id>`, and its key. */
    @SerialName("task_id") val taskId: String? = null,
    val key: String? = null,
)

/** What `search` answers. */
@Serializable
data class SearchPage(
    val hits: List<SearchHit> = emptyList(),
    /** The hub copies what was said into the index (an owner's setting, off by default). */
    @SerialName("transcripts_indexed") val transcriptsIndexed: Boolean = false,
)
