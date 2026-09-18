package dev.claudefleet.mobile.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** A session's recent exchange, as `session_conversation` returns it. */
@Serializable
data class Conversation(
    val turns: List<ConvTurn> = emptyList(),
    /** Older turns or items were dropped to fit the turn / character budget. */
    val truncated: Boolean = false,
)

/** One turn: the human prompt that opened it and what the agent said or did. */
@Serializable
data class ConvTurn(
    /** Null for assistant output whose prompt lies before the read tail. */
    val prompt: String? = null,
    /** ISO timestamp of the prompt (else of the first assistant entry). */
    val at: String? = null,
    /** ISO timestamp of the turn's latest assistant entry. */
    @SerialName("ended_at") val endedAt: String? = null,
    val items: List<ConvItem> = emptyList(),
)

/**
 * One item inside a turn. The hub tags these internally with `kind`
 * (`#[serde(tag = "kind", rename_all = "snake_case")]`), so the discriminator
 * is read in place rather than from a wrapper object.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class ConvItem {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ConvItem()

    /** A tool one-liner. [error] is set when the tool call came back failed. */
    @Serializable
    @SerialName("tool")
    data class Tool(val summary: String, val error: Boolean = false) : ConvItem()
}
