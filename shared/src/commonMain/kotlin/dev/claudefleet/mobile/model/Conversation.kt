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
    /**
     * Current-conversation context size, from this same read's tail. `null`
     * when the tail carried no usage (nothing yet, or a compaction with no
     * reply since) — mirrors the hub's `ContextView` (`transcript.rs`).
     */
    val context: ConvContext? = null,
    /**
     * This conversation's timeline events, oldest first — kept raw rather
     * than modelled, since nothing on this screen renders them yet.
     */
    val events: List<JsonElement> = emptyList(),
)

/** The context size shown alongside a [Conversation] — the hub's `ContextView`. */
@Serializable
data class ConvContext(
    val tokens: Long? = null,
    val window: Long? = null,
    val pct: Double? = null,
    /** Always `false` on a value freshly read from the transcript. */
    val stale: Boolean = false,
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
    // An empty read says nothing at all, including about truncation. `this` is
    // already inside the ceiling — every conversation on a screen got there
    // through this function — so it needs no second look.
    if (fresh.turns.isEmpty()) return this
    // The first read of a screen, which takes the fresh window wholesale and so
    // has to be capped as much as a merged one: `turns` can be asked for
    // explicitly, and a ceiling that only applied once two reads had been
    // merged is one that an opening screen walks straight past.
    if (turns.isEmpty()) return fresh.withinCeiling()

    val held = turns.map { it.identity() }
    val arrived = fresh.turns.map { it.identity() }
    var overlap = 0
    for (n in minOf(held.size, arrived.size) downTo 1) {
        if (held.subList(held.size - n, held.size) == arrived.subList(0, n)) {
            overlap = n
            break
        }
    }
    // With no overlap at all, one of two things happened: the windows are
    // genuinely disjoint (a long gap between reads), or the identity
    // of some held turn *drifted* and the overlap was missed. The second is not
    // exotic — `at` for a headless turn is its first surviving assistant entry's
    // timestamp, which moves forward as the hub's 1 MB tail slides off it, and
    // `fit_last_turn` cuts an over-budget prompt from its **head** — and the
    // consequence was that the entire fresh window got appended under the held
    // one, repeating every turn they share, for the life of the screen.
    //
    // So when the splice finds nothing, each held turn is asked the direct
    // question instead: are you in the fresh window? A held turn whose identity
    // appears anywhere in `fresh` is the same turn and is dropped in favour of
    // the fresher reading. Only the turn that actually drifted survives twice,
    // which turns "the whole window is duplicated" into "one turn is", and
    // leaves a genuinely disjoint older window untouched.
    //
    // Deliberately *not* a time-boundary test (drop held turns stamped at or
    // after the first arrived turn). A drifted headless turn is re-stamped
    // to its first surviving entry, which can be **later** than the
    // prompts that follow it in the same window, so the boundary lands in the
    // wrong place and the turns after it are kept and duplicated anyway. It also
    // dropped a genuine second turn in the same second, which is a case with a
    // test of its own.
    //
    // The price of that: with `overlap == 0` this drops ANY held turn whose
    // identity appears in `fresh`, including one in the middle of
    // the held list, so held [A, B, C] against a genuinely disjoint fresh
    // [B', D] where B' has B's identity yields [A, C, B, D] — C now precedes B.
    // It takes two turns sharing an `(at, prompt)` across non-overlapping
    // windows, so it is narrow, and the failure it replaced (the whole window
    // duplicated, permanently) was worse. The trade is a possible reorder
    // instead of a certain duplicate, and it is a trade rather than a fix.
    val kept = if (overlap == 0) {
        val arrivedIdentities = arrived.toSet()
        turns.filterIndexed { index, _ -> held[index] !in arrivedIdentities }
    } else {
        turns.subList(0, turns.size - overlap)
    }
    return Conversation(
        turns = kept + fresh.turns,
        truncated = truncated || fresh.truncated,
        // Both a rolling-window read like `turns` itself, not a ledger: the
        // freshest read's own picture replaces the held one rather than being
        // merged into it, and falls back to what was already held only when
        // this particular read carried nothing (a fetch that raced ahead of
        // the hub's own context/event bookkeeping).
        context = fresh.context ?: context,
        events = fresh.events.ifEmpty { events },
    ).withinCeiling()
}

/**
 * The most turns one screen will hold.
 *
 * The merge above exists because the hub sends a **rolling window**: it re-reads
 * the last few turns each time, so the app has to keep the ones that have
 * scrolled off the hub's end but are still on the screen. The consequence, which
 * follows directly and which nothing had looked at, is that the app keeps *all*
 * of them — for as long as the screen is open — while the hub's own memory of
 * the session stays the same ten turns it always was.
 *
 * These are Claude Code sessions. They run for hours and produce hundreds of
 * turns, each carrying a prompt and every tool line under it, and the screen
 * showing one is precisely the screen somebody leaves open to watch. Nothing
 * bounded that but how long the agent worked.
 *
 * **Why this many.** The hub's default window is ten turns, so two hundred is
 * twenty windows of scrollback — far more than anyone scrolls on a phone — and
 * at a few kilobytes a turn it is comfortably under a megabyte held. The point
 * is not to guess how far back someone might look; it is that the number is
 * fixed rather than proportional to how long the session has been running.
 */
internal const val MAX_RETAINED_TURNS: Int = 200

/**
 * Drop the oldest turns once there are more than [MAX_RETAINED_TURNS].
 *
 * The oldest, because they are the ones furthest from what is happening now —
 * and the newest must never be the one dropped, since the live turn is what the
 * screen exists to show.
 *
 * [Conversation.truncated] is set when anything goes, which is not a new state
 * to draw: the screen already renders that flag as "Older turns are not shown",
 * and it is already how the hub says the same thing about its own window. A
 * conversation that lost turns here and one that lost them at the hub are the
 * same thing to a reader.
 */
private fun Conversation.withinCeiling(): Conversation =
    if (turns.size <= MAX_RETAINED_TURNS) {
        this
    } else {
        copy(turns = turns.takeLast(MAX_RETAINED_TURNS), truncated = true)
    }

private fun ConvTurn.identity(): Pair<String?, String?> = at to prompt

/**
 * The one rule for "did the tail move": how many turns there are, and when
 * the last one's assistant output was last updated. Turn count alone misses
 * the ordinary case of the live turn growing new items while the agent keeps
 * working, without a new turn ever starting — [SessionViewModel.runGeneration]
 * (`tailGrew`) and [dev.claudefleet.mobile.ui.SessionScreen]'s own
 * auto-scroll `LaunchedEffect` both need that same fact, and used to compute
 * it by hand in two places that had to agree and nothing made them.
 */
fun Conversation.tailMarker(): Pair<Int, String?> = turns.size to turns.lastOrNull()?.endedAt

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
 * day the hub grows a kind past the seven modelled here, an app already in
 * someone's pocket would fail the whole conversation screen on an item it
 * could simply have skipped past. [Unsupported] is that skip — the same
 * promise `ignoreUnknownKeys` already makes for an unknown *field*, kept for
 * an unknown *variant*.
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
     * A `Task` / `Agent` call, kept apart from other tools so its final text
     * can be shown without cramming a subagent transcript into the tool
     * one-liner — mirrors the hub's `ConvItem::Subagent` (`transcript.rs`).
     */
    @Serializable
    @SerialName("subagent")
    @JsonIgnoreUnknownKeys
    data class Subagent(
        val id: String? = null,
        /** `"Task"` or `"Agent"`. */
        val name: String = "",
        @SerialName("agent_type") val agentType: String? = null,
        val description: String? = null,
        val result: String? = null,
        val error: Boolean = false,
        val at: String? = null,
        @SerialName("ended_at") val endedAt: String? = null,
        val done: Boolean = false,
    ) : ConvItem() {
        override val label: String get() = description?.takeIf { it.isNotBlank() } ?: name
    }

    /** A compaction (`system/compact_boundary`) — the hub's `ConvItem::Compact`. */
    @Serializable
    @SerialName("compact")
    @JsonIgnoreUnknownKeys
    data class Compact(
        val trigger: String? = null,
        @SerialName("pre_tokens") val preTokens: Long? = null,
        val summary: String? = null,
    ) : ConvItem() {
        override val label: String get() = "Compacted"
    }

    /**
     * A slash command the user ran; `output` is the following
     * `<local-command-stdout>` / `<local-command-stderr>` — the hub's
     * `ConvItem::Command`.
     */
    @Serializable
    @SerialName("command")
    @JsonIgnoreUnknownKeys
    data class Command(
        val name: String = "",
        val args: String? = null,
        val output: String? = null,
    ) : ConvItem() {
        override val label: String get() = "/$name"
    }

    /**
     * A `<task-notification>` user entry: a background agent, command,
     * monitor or workflow reporting in — the hub's `ConvItem::Notification`.
     */
    @Serializable
    @SerialName("notification")
    @JsonIgnoreUnknownKeys
    data class Notification(
        @SerialName("task_id") val taskId: String? = null,
        @SerialName("tool_use_id") val toolUseId: String? = null,
        /** `completed` | `failed` | `stopped` | `killed`; `null` on a mid-stream event. */
        val status: String? = null,
        val summary: String? = null,
        val result: String? = null,
        @SerialName("output_file") val outputFile: String? = null,
        /** Monitor's streamed line. */
        val event: String? = null,
        val at: String? = null,
    ) : ConvItem() {
        override val label: String
            get() = summary?.takeIf { it.isNotBlank() } ?: status?.takeIf { it.isNotBlank() } ?: "notification"
    }

    /** `[Request interrupted by user]` — the hub's `ConvItem::Interrupt`. */
    @Serializable
    @SerialName("interrupt")
    @JsonIgnoreUnknownKeys
    data class Interrupt(
        @SerialName("during_tool") val duringTool: Boolean = false,
    ) : ConvItem() {
        override val label: String get() = "Interrupted"
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
            "subagent" -> ConvItem.Subagent.serializer()
            "compact" -> ConvItem.Compact.serializer()
            "command" -> ConvItem.Command.serializer()
            "notification" -> ConvItem.Notification.serializer()
            "interrupt" -> ConvItem.Interrupt.serializer()
            else -> ConvItem.Unsupported.serializer()
        }
}
