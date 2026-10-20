package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What `session_prompt_tune` proposed for a draft: a model's rewrite of it
 * into a clearer prompt for the session's agent, and a line on what it
 * changed. A proposal only: the phone never sends [prompt] by itself; the
 * person puts it into the composer and taps Send.
 *
 * [unchanged] is the model finding nothing to improve; [prompt] is then the
 * draft as it was.
 */
@Serializable
data class TuneAnswer(
    val prompt: String = "",
    val notes: String = "",
    val unchanged: Boolean = false,
    /** The model that wrote it, as the hub names it. */
    val model: String = "",
    /** The host whose CLI ran it. */
    @SerialName("host_alias") val hostAlias: String = "",
    /** Unix seconds it was written. */
    val at: Long = 0,
    /** The model ran past the limit and [prompt] was cut there (ends in "…"). */
    val cut: Boolean = false,
)
