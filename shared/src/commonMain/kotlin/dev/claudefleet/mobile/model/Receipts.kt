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
 *
 * **Not a `data class`, for the same reason [dev.claudefleet.mobile.store.Credentials]
 * is not.** A generated `toString()` prints every property, and [token] is the
 * first one — so this type was one string interpolation away from putting the
 * plaintext token into a log line, a crash report or an exception message. It
 * was the last type in the app with that shape, which is the whole argument for
 * changing it: the rule is only worth anything if it holds everywhere the token
 * lives.
 *
 * `copy()` and destructuring go with the `data` modifier and nothing used
 * either — checked before removing it, not assumed.
 */
@Serializable
class PairResult(
    val token: String,
    val name: String,
    /** `full` | `readonly`. */
    val mode: String,
    val hub: String,
) {
    /** Redacted on purpose. See the class comment. */
    override fun toString(): String =
        "PairResult(name=$name, mode=$mode, hub=$hub, token=<redacted>)"

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PairResult && token == other.token && name == other.name &&
                mode == other.mode && hub == other.hub)

    override fun hashCode(): Int {
        var result = token.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + hub.hashCode()
        return result
    }
}
