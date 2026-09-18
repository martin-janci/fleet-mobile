package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What `send_prompt` answers. [turnSeqBefore] is the session's turn counter as
 * it stood when the prompt was delivered — the value to wait past when
 * collecting the reply.
 */
@Serializable
data class SendPromptResult(
    val delivered: Boolean = false,
    @SerialName("session_id") val sessionId: Long,
    @SerialName("turn_seq_before") val turnSeqBefore: Long = 0,
)

/**
 * What `POST /pair` answers: the one and only time the plaintext client token
 * crosses the wire. [hub] is the hub's own idea of its base URL, which is what
 * the app should store — the URL scanned from a QR may be one of several names
 * that reach it.
 */
@Serializable
data class PairResult(
    val token: String,
    val name: String,
    /** `full` | `readonly`. */
    val mode: String,
    val hub: String,
)
