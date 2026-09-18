package dev.claudefleet.mobile.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A session's recent exchange, as `session_conversation` returns it. */
@Serializable
data class Conversation(
    val turns: List<ConvTurn> = emptyList(),
    /** Older turns or items were dropped to fit the turn / character budget. */
    val truncated: Boolean = false,
)

/**
 * Fold a fresh read of the conversation into the one already on screen.
 *
 * `session_conversation` answers a **rolling window of the tail**, not a
 * continuation: the hub re-reads the last N turns of the transcript every time.
 * So neither of the obvious things is right. Replacing wholesale loses the turns
 * that have scrolled off the top of the hub's window but are still on the
 * screen; appending wholesale repeats every turn the two reads share.
 *
 * What is true of two reads of the same tail is that they **overlap**: the fresh
 * window's first turns are the ones already held, unless so much happened in
 * between that the windows are disjoint. So the longest overlap between the tail
 * of what is held and the head of what arrived is found, and the fresh window
 * replaces it — which also lets the bottom turn grow, since the agent is still
 * appending items to it while it works.
 *
 * A turn is known by its `at` and its `prompt` together: `at` alone collides for
 * two turns inside the same second, and `prompt` alone collides whenever the
 * same thing is asked twice. Turns with neither — assistant output whose prompt
 * lies before the read tail — are matched on that empty identity, which folds
 * two of them together; that is the price of not repeating the one that is
 * usually there, and the conversation is a view, not a ledger.
 *
 * When the windows do not overlap at all, both are kept in order and nothing is
 * invented to bridge them. [Conversation.truncated] is how the screen says there
 * may be a gap, and once set it stays set.
 */
fun Conversation.appending(fresh: Conversation): Conversation {
    // An empty read says nothing at all, including about truncation.
    if (fresh.turns.isEmpty()) return this
    if (turns.isEmpty()) return fresh

    val held = turns.map { it.identity() }
    val arrived = fresh.turns.map { it.identity() }
    var overlap = 0
    for (n in minOf(held.size, arrived.size) downTo 1) {
        if (held.subList(held.size - n, held.size) == arrived.subList(0, n)) {
            overlap = n
            break
        }
    }
    return Conversation(
        turns = turns.subList(0, turns.size - overlap) + fresh.turns,
        truncated = truncated || fresh.truncated,
    )
}

/** What tells one turn from another across two reads. See [appending]. */
private fun ConvTurn.identity(): Pair<String?, String?> = at to prompt

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
 * One item inside a turn. The hub tags these with `kind`
 * (`#[serde(tag = "kind", rename_all = "snake_case")]`), so the discriminator is
 * read in place rather than from a wrapper object.
 *
 * The discriminator is dispatched by hand rather than by the generated sealed
 * serializer, because the generated one *throws* on a tag it does not know. The
 * hub has two kinds today; the day it grows a third, an app already in someone's
 * pocket would fail the whole conversation screen on an item it could simply
 * have skipped past. [Unsupported] is that skip — the same promise
 * `ignoreUnknownKeys` already makes for an unknown *field*, kept for an unknown
 * *variant*.
 */
@Serializable(with = ConvItemSerializer::class)
sealed class ConvItem {

    /** What a screen can put on the page for this item, whatever it turns out to be. */
    abstract val label: String

    @Serializable
    @SerialName("text")
    @JsonIgnoreUnknownKeys
    data class Text(val text: String) : ConvItem() {
        override val label: String get() = text
    }

    /** A tool one-liner. [error] is set when the tool call came back failed. */
    @Serializable
    @SerialName("tool")
    @JsonIgnoreUnknownKeys
    data class Tool(val summary: String, val error: Boolean = false) : ConvItem() {
        override val label: String get() = summary
    }

    /**
     * An item whose `kind` this build of the app does not know — a variant the
     * hub grew after this app shipped, or an item with no discriminator at all.
     *
     * It carries the tag rather than dropping it, so the screen can say which
     * kind it is holding and a bug report names the thing that needs support.
     */
    @Serializable
    @SerialName("unsupported")
    @JsonIgnoreUnknownKeys
    data class Unsupported(val kind: String = "") : ConvItem() {
        override val label: String
            get() = if (kind.isBlank()) "(unsupported item)" else "(unsupported item: $kind)"
    }
}

/**
 * Chooses a [ConvItem] subtype by the `kind` the hub put in the object, and
 * falls back to [ConvItem.Unsupported] instead of throwing.
 *
 * Unlike the generated sealed serializer this leaves `kind` in the object it
 * hands on, which is how [ConvItem.Unsupported] gets to keep it; the known
 * subtypes carry [JsonIgnoreUnknownKeys] so they tolerate it regardless of how
 * lenient the caller's `Json` happens to be.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object ConvItemSerializer : JsonContentPolymorphicSerializer<ConvItem>(ConvItem::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ConvItem> =
        when ((element as? JsonObject)?.get("kind").let { it as? JsonPrimitive }?.content) {
            "text" -> ConvItem.Text.serializer()
            "tool" -> ConvItem.Tool.serializer()
            else -> ConvItem.Unsupported.serializer()
        }
}
