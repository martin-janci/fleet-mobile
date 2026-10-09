package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import dev.claudefleet.mobile.net.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The fleet's settings, as the hub's declarative pages describe them
 * (claude-fleet's declarative pages P6). Two answers make a screen:
 *
 *  - `list_pages` — the page specs: which settings go on which page, in what
 *    sections, under what condition. Compiled into the hub, the same for every
 *    device.
 *  - `get_settings { describe: true }` — every registered setting with its
 *    label, help, kind and bounds, unit, danger and effective value.
 *
 * A page only says *where* a setting goes; everything shown about it comes from
 * its descriptor. This is a port of the desktop's `src/lib/pages/pages.ts`
 * helpers (conditions, units, the words for a value), not a second design: the
 * phone draws the fields a page places, its notices and links, and leaves the
 * rest — data items, resources, page actions — to a desktop, as a paired
 * desktop does.
 */

/** `list_pages`: the part the phone draws. Sources, resources and actions are
 *  a desktop's and are ignored. */
@Serializable
data class PagesBundle(val pages: List<Page> = emptyList())

@Serializable
data class Page(
    val id: String,
    val title: String,
    val parent: String? = null,
    val intro: String? = null,
    val layout: String,
    /** A `review_apply` page's proposals: `settings` is the one source. */
    val review: String? = null,
    val sections: List<Section> = emptyList(),
    val tabs: List<PageTab> = emptyList(),
) {
    /** Every section, tab by tab, in order. */
    val allSections: List<Section> get() = sections + tabs.flatMap { it.sections }
}

@Serializable
data class PageTab(
    val title: String,
    @SerialName("when") val condition: Condition? = null,
    val sections: List<Section> = emptyList(),
)

@Serializable
data class Section(
    val title: String,
    val intro: String? = null,
    val collapsible: Boolean = false,
    val advanced: Boolean = false,
    /** Choice-set fields over the same options, drawn as one grid: a row per
     *  option, a column per field (claude-fleet 11.9, the notifications matrix). */
    val matrix: Boolean = false,
    @SerialName("when") val condition: Condition? = null,
    /** Tagged by `type`; read through [PageItem.of]. */
    val items: List<JsonObject> = emptyList(),
)

/** One item of a section, as the phone draws it. */
sealed interface PageItem {
    /** [readOnly]: the page shows this setting and does not edit it (its
     *  `widget` is `readonly`), whatever the device may write. */
    data class Field(
        val key: String,
        val hint: String?,
        val condition: Condition?,
        val readOnly: Boolean = false,
    ) : PageItem
    data class Notice(val tone: String, val text: String) : PageItem
    data class Link(val page: String, val label: String?) : PageItem

    /** A data item, a custom component or a page action: a desktop's. */
    data class Elsewhere(val type: String) : PageItem

    companion object {
        fun of(o: JsonObject): PageItem {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return when (val type = str("type")) {
                "field" -> Field(
                    key = str("key").orEmpty(),
                    hint = str("hint"),
                    condition = (o["when"] as? JsonObject)?.let { json.decodeFromJsonElement(Condition.serializer(), it) },
                    readOnly = str("widget") == "readonly",
                )
                "notice" -> Notice(tone = str("tone") ?: "info", text = str("text").orEmpty())
                "link" -> Link(page = str("page").orEmpty(), label = str("label"))
                else -> Elsewhere(type.orEmpty())
            }
        }
    }
}

/** A page's `when`: one form per condition, as the hub's validator holds it. */
@Serializable
data class Condition(
    val key: String? = null,
    val eq: String? = null,
    @SerialName("in") val oneOf: List<String>? = null,
    val truthy: Boolean? = null,
    val all: List<Condition>? = null,
    val any: List<Condition>? = null,
    val not: Condition? = null,
)

/** Whether [c] holds for [values]. A missing condition always holds. */
fun Condition?.holds(values: Map<String, String>): Boolean {
    val c = this ?: return true
    c.all?.let { return it.all { x -> x.holds(values) } }
    c.any?.let { return it.any { x -> x.holds(values) } }
    c.not?.let { return !it.holds(values) }
    val key = c.key ?: return true
    val v = values[key].orEmpty().trim()
    c.eq?.let { return v == it }
    c.oneOf?.let { return v in it }
    c.truthy?.let { return (v == "true") == it }
    return true
}

/** One registered setting: `get_settings { describe: true }`. */
@Serializable
data class SettingDescriptor(
    val key: String,
    val label: String,
    val help: String = "",
    val kind: SettingKind,
    val default: String = "",
    val value: String = "",
    val unit: String = "none",
    val zero: String? = null,
    val tags: List<String> = emptyList(),
    val danger: Danger = Danger(),
    val restart: String = "none",
    @SerialName("owned_by") val ownedBy: String? = null,
    @SerialName("option_labels") val optionLabels: List<List<String>> = emptyList(),
) {
    /** Changed somewhere else than here: shown, never edited. */
    val readOnlyHere: Boolean get() = ownedBy != null

    fun optionLabel(value: String): String =
        optionLabels.firstOrNull { it.firstOrNull() == value }?.getOrNull(1) ?: value
}

@Serializable
data class SettingKind(
    val type: String,
    val min: Long? = null,
    val max: Long? = null,
    val options: List<String> = emptyList(),
)

@Serializable
data class Danger(val level: String = "none", val message: String? = null) {
    val confirms: Boolean get() = level == "confirm"
}

/** Kinds the phone edits. A map, an id list or a price table: on a desktop. */
val SettingDescriptor.editableOnPhone: Boolean
    get() = kind.type in setOf("bool", "secs", "int", "choice", "text", "choice_set", "time_range")

/** The options a choice-set value holds. */
fun choiceSetOf(value: String): Set<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/** A choice-set value with [option] ticked or not, in the setting's own option order, as the hub stores it. */
fun SettingDescriptor.withChoice(value: String, option: String, on: Boolean): String {
    val held = choiceSetOf(value).let { if (on) it + option else it - option }
    return kind.options.filter { it in held }.joinToString(",")
}

private val SECS_PER: Map<String, Long> = mapOf("seconds" to 1, "minutes" to 60, "hours" to 3600, "days" to 86_400)

private val UNIT_WORDS: Map<String, String> = mapOf(
    "ms" to "ms", "seconds" to "seconds", "minutes" to "minutes", "hours" to "hours", "days" to "days",
    "percent" to "%", "kib" to "KiB", "mib" to "MiB", "tokens" to "tokens",
)

/** The unit a number is shown in, as a word; empty when it has none. */
val SettingDescriptor.unitWord: String get() = UNIT_WORDS[unit].orEmpty()

/** Stored units per shown unit: seconds shown in hours is 3600. */
fun SettingDescriptor.unitFactor(): Long = if (kind.type == "secs") SECS_PER[unit] ?: 1 else 1

/** The stored value as the number a person types. */
fun SettingDescriptor.toDisplay(raw: String): String {
    val f = unitFactor()
    if (f == 1L) return raw
    val n = raw.toDoubleOrNull() ?: return raw
    val shown = n / f
    return if (shown == kotlin.math.floor(shown)) shown.toLong().toString()
    else ((kotlin.math.round(shown * 100)) / 100).toString()
}

/** A typed number back to the stored text, or why it cannot be. The hub still
 *  owns the range check; this refuses what is not a number, a fraction of a
 *  whole-number setting, and a non-zero entry that would round to 0 ("off"). */
fun SettingDescriptor.fromDisplay(typed: String): Result<String> {
    val t = typed.trim()
    if (t.isEmpty()) return Result.failure(IllegalArgumentException("enter a number"))
    val n = t.toDoubleOrNull()
    if (n == null || n < 0 || n.isNaN() || n.isInfinite()) {
        return Result.failure(IllegalArgumentException("enter a number, 0 or more"))
    }
    val f = unitFactor()
    if (f == 1L) {
        if (n != kotlin.math.floor(n)) return Result.failure(IllegalArgumentException("enter a whole number"))
        return Result.success(n.toLong().toString())
    }
    val stored = kotlin.math.round(n * f).toLong()
    if (stored == 0L && n != 0.0) return Result.failure(IllegalArgumentException("too small: under one second"))
    return Result.success(stored.toString())
}

/** "1–365 days", "0 = never": the bounds in the shown unit. */
fun SettingDescriptor.rangeText(): String {
    val u = unitWord
    var base = when {
        kind.type == "int" && kind.min != null && kind.max != null ->
            "${kind.min}–${kind.max}${if (u.isNotEmpty()) " $u" else ""}"
        kind.type == "secs" && (kind.min ?: 0) > 0 ->
            "at least ${(kind.min ?: 0) / unitFactor()}${if (u.isNotEmpty()) " $u" else ""}"
        kind.type == "secs" -> u
        else -> ""
    }
    zero?.let { base = if (base.isEmpty()) "0 = $it" else "$base; 0 = $it" }
    return base
}

/** A value in words, as a person reads it: On / Off, an option's label, a
 *  number in its unit, "(empty)". The desktop's `valueInWords`. */
fun SettingDescriptor.inWords(value: String): String {
    if (value.isEmpty()) return if (kind.type == "time_range") "None" else "(empty)"
    return when (kind.type) {
        "bool" -> if (value == "true") "On" else "Off"
        "choice" -> optionLabel(value)
        "choice_set" -> value.split(',').joinToString(", ") { optionLabel(it.trim()) }
        "time_range" -> value.replace("-", "–")
        "secs", "int" -> toDisplay(value) + if (unitWord.isNotEmpty()) " $unitWord" else ""
        else -> value
    }
}

/** A settings proposal an agent (or a device) left for a person. */
@Serializable
data class SettingProposal(
    val id: Long,
    val at: Long = 0,
    val key: String,
    val value: String,
    val before: String = "",
    /** The key's value now: it may have moved since. */
    val current: String = "",
    val why: String? = null,
    val source: String = "agent",
    @SerialName("source_detail") val sourceDetail: String? = null,
)

/** `setting_proposals`: what waits, and whether this device may decide. */
@Serializable
data class SettingsPending(
    @SerialName("can_write") val canWrite: Boolean = false,
    val proposals: List<SettingProposal> = emptyList(),
)

@Serializable
data class SettingsDecided(
    val applied: List<Long> = emptyList(),
    val rejected: List<Long> = emptyList(),
    val failed: List<DecideFailure> = emptyList(),
)

@Serializable
data class DecideFailure(val id: Long, val error: String)

/** The Settings overview page's id: its children are the pages offered. */
const val SETTINGS_ROOT_PAGE: String = "settings"

/**
 * The pages a phone offers: the Settings pages that place at least one field,
 * plus the review page. A resource page (Trackers, Organisations) and a data
 * page are a desktop's, as they are on a paired desktop.
 */
fun offeredPages(bundle: PagesBundle): List<Page> =
    bundle.pages.filter { p ->
        p.parent == SETTINGS_ROOT_PAGE && (
            p.layout == "review_apply" ||
                (p.layout == "category" && p.allSections.any { s -> s.items.any { PageItem.of(it) is PageItem.Field } })
            )
    }

/** One write to a setting (`setting_history`, newest first): who, before and after, the proposal it applied. */
@Serializable
data class SettingWrite(
    val id: Long,
    val at: Long = 0,
    val key: String = "",
    val before: String? = null,
    val after: String = "",
    /** person, agent, operator, … — who wrote it. */
    val actor: String = "",
    @SerialName("actor_detail") val actorDetail: String? = null,
    @SerialName("proposal_id") val proposalId: Long? = null,
)
