package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One file a session sent to the hub (claude-fleet *file downloads*, contract
 * revision 7): `send_file` makes the row, a background task on the hub copies
 * the bytes off the host, and `GET /downloads/<id>` hands them over once the
 * row is [READY].
 *
 * The hub always sends `id`, `at`, `host_alias`, `path`, `name`, `size`,
 * `state` and `source`; everything else may be absent. Every field still has
 * a default here except [id], so one odd row degrades to a blank label rather
 * than failing the whole list — the same reason `ignoreUnknownKeys` lets a
 * hub grow fields.
 *
 * [state] stays a string, not an enum: a state this build has never heard of
 * reads as neither ready nor failed (nothing to fetch, nothing to blame), and
 * the row still draws.
 */
@Serializable
data class Download(
    val id: Long,
    /** When it was asked for (unix seconds). */
    val at: Long = 0,
    @SerialName("host_alias") val hostAlias: String = "",
    /** Null once the session row is gone; [sessionName] outlives it. */
    @SerialName("session_id") val sessionId: Long? = null,
    /** The tmux name, kept after the session. */
    @SerialName("session_name") val sessionName: String? = null,
    /** Absolute, on the host. */
    val path: String = "",
    /** The file's own name — what a saved copy is called. */
    val name: String = "",
    /** Bytes. Known from the hub's `stat` before the copy starts. */
    val size: Long = 0,
    /** [FETCHING] | [READY] | [FAILED]. */
    val state: String = "",
    /** Why it failed; [FAILED] only. */
    val error: String? = null,
    /** Hex SHA-256 of the hub's copy; [READY] only. */
    val sha256: String? = null,
    /** `agent` (Claude sent it) | `person` (someone picked it). */
    val source: String = "",
    val note: String? = null,
    @SerialName("ready_at") val readyAt: Long? = null,
    /** The last `GET`, by anyone; null if never. */
    @SerialName("downloaded_at") val downloadedAt: Long? = null,
    /** When the hub forgets it; null when kept until removed, or not ready yet. */
    @SerialName("expires_at") val expiresAt: Long? = null,
) {
    val isReady: Boolean get() = state == READY
    val isFetching: Boolean get() = state == FETCHING
    val isFailed: Boolean get() = state == FAILED

    companion object {
        const val FETCHING = "fetching"
        const val READY = "ready"
        const val FAILED = "failed"
    }
}

/** What `list_downloads` answers: the rows newest first, and the hub's budget. */
@Serializable
data class DownloadList(
    val downloads: List<Download> = emptyList(),
    @SerialName("total_bytes") val totalBytes: Long = 0,
    @SerialName("max_total_bytes") val maxTotalBytes: Long? = null,
    @SerialName("max_file_bytes") val maxFileBytes: Long? = null,
)

/** `remove_download`'s answer: false when the row was already gone. */
@Serializable
data class DownloadRemoved(val removed: Boolean = false)

/**
 * "48 KB", "3.2 MB" — powers of 1024, one decimal under ten, so a row reads
 * the way a file manager reads.
 */
fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val rounded = if (value < 10) {
        val tenths = (value * 10).toLong()
        "${tenths / 10}.${tenths % 10}"
    } else {
        value.toLong().toString()
    }
    return "$rounded ${units[unit]}"
}
