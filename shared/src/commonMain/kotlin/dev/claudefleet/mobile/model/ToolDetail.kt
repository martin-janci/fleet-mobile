package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One tool call's input and result, read on demand with `session_tool_detail`
 * when a person expands the call's row — the hub's `ToolDetail`, the same
 * shape the desktop's `toolDetail` reads.
 *
 * Every text is capped at 8 000 characters by the hub (ending in "…" when
 * cut), so nothing here is unbounded.
 */
@Serializable
data class ToolDetail(
    val id: String = "",
    val name: String = "",
    /** The call's input as pretty-printed JSON. */
    val input: String = "",
    /** Edit / MultiEdit / Write only. */
    val edit: EditDetail? = null,
    /** Bash only: the full command. */
    val command: String? = null,
    /** Null while the call is still waiting for its result. */
    val result: String? = null,
    @SerialName("is_error") val isError: Boolean = false,
)

/**
 * What an Edit, MultiEdit or Write changed. A MultiEdit's edits arrive joined
 * by `"\n…\n"` in both halves; a Write has an empty [old].
 */
@Serializable
data class EditDetail(
    @SerialName("file_path") val filePath: String = "",
    val old: String = "",
    val new: String = "",
)
