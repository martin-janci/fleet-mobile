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
    /**
     * Every section, tab by tab, in order — for asking "does this page place a
     * field at all", NOT for drawing.
     *
     * Drawing from this flattened everything: `settings.work` is tabs-only
     * (Detection / Tidy-up / Retention), so its six sections ran together with
     * the three tab names gone, and a tab's own `when` was never evaluated.
     * [shownTabs] and [shownSections] are what the screen reads.
     */
    val allSections: List<Section> get() = sections + tabs.flatMap { it.sections }

    /** The tabs whose condition holds, in order; empty for a page with none. */
    fun shownTabs(values: Map<String, String>): List<PageTab> =
        tabs.filter { it.condition.holds(values) }

    /**
     * The sections to draw: the page's own, plus the selected tab's.
     *
     * One tab at a time, as the desktop's `PageView` does — it takes
     * `tabs[tab].sections` INSTEAD of `page.sections` when a page has tabs.
     * [tab] indexes [shownTabs]; out of range reads as the first.
     */
    fun shownSections(values: Map<String, String>, tab: Int = 0): List<Section> {
        val shown = shownTabs(values)
        if (shown.isEmpty()) return sections
        return sections + shown.getOrElse(tab) { shown.first() }.sections
    }
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
        /**
         * A field's `when`, or null when it cannot be read.
         *
         * `decodeFromJsonElement` throws `SerializationException` on a
         * wrong-typed member, and this runs inside composition — so a malformed
         * condition was a UI CRASH rather than an empty row. The hub's own page
         * validator owns the item shape, so this is unreachable through a
         * healthy hub; a client is still not the place to crash over it. A
         * condition that cannot be read is no condition, which is how a missing
         * one already behaves ([holds] returns true), so the field is shown
         * rather than silently hidden.
         */
        private fun cond(e: kotlinx.serialization.json.JsonElement?): Condition? {
            val o = e as? JsonObject ?: return null
            return try {
                json.decodeFromJsonElement(Condition.serializer(), o)
            } catch (_: Exception) {
                null
            }
        }

        fun of(o: JsonObject): PageItem {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return when (val type = str("type")) {
                "field" -> Field(
                    key = str("key").orEmpty(),
                    hint = str("hint"),
                    condition = cond(o["when"]),
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
    get() = kind.type in setOf("bool", "secs", "int", "choice", "text")

private val SECS_PER: Map<String, Long> = mapOf("seconds" to 1, "minutes" to 60, "hours" to 3600, "days" to 86_400)

private val UNIT_WORDS: Map<String, String> = mapOf(
    "ms" to "ms", "seconds" to "seconds", "minutes" to "minutes", "hours" to "hours", "days" to "days",
    "percent" to "%", "kib" to "KiB", "mib" to "MiB", "tokens" to "tokens",
)

/** The unit a number is shown in, as a word; empty when it has none. */
val SettingDescriptor.unitWord: String get() = UNIT_WORDS[unit].orEmpty()

/** Stored units per shown unit: seconds shown in hours is 3600. */
fun SettingDescriptor.unitFactor(): Long = if (kind.type == "secs") SECS_PER[unit] ?: 1 else 1

/**
 * The stored value as the number a person types.
 *
 * Two divergences from the desktop's `pages.ts`, which this is a port of, and
 * both were visible on a row:
 *
 *  - wholeness was tested BEFORE rounding, so `7199` seconds in hours is
 *    `1.99972…`, not whole, and the else branch's `2.0` printed as `"2.0"`
 *    where the desktop prints `"2"` (JS `String(2)`).
 *  - `kotlin.math.round` is ties-to-EVEN and JS `Math.round` is half-up, so
 *    `450` seconds in hours — exactly `0.125`, exactly `12.5` after scaling —
 *    printed `"0.12"` against the desktop's `"0.13"`.
 *
 * `floor(x + 0.5)` is half-up, and the wholeness test moves after it.
 */
fun SettingDescriptor.toDisplay(raw: String): String {
    val f = unitFactor()
    if (f == 1L) return raw
    val n = raw.toDoubleOrNull() ?: return raw
    val rounded = kotlin.math.floor(n / f * 100 + 0.5) / 100
    return if (rounded == kotlin.math.floor(rounded)) rounded.toLong().toString()
    else rounded.toString()
}

/** A typed number back to the stored text, or why it cannot be. The hub still
 *  owns the range check; this refuses what is not a number, a fraction of a
 *  whole-number setting, and a non-zero entry that would round to 0 ("off"). */
fun SettingDescriptor.fromDisplay(typed: String): Result<String> {
    val t = typed.trim()
    if (t.isEmpty()) return Result.failure(IllegalArgumentException("enter a number"))
    // `String.toDouble` accepts Kotlin's own float suffixes — `"2d"`, `"2f"`,
    // `"2D"` all parse as 2.0 — so a typo was sent to the hub as a valid value
    // where the desktop's `Number("2d")` is NaN and refuses it. On an hours
    // field "2d" silently meant two HOURS. It also parses differently on
    // Android and on Kotlin/Native, which is its own reason to pin it.
    if (!NUMERIC.matches(t)) return Result.failure(IllegalArgumentException("enter a number, 0 or more"))
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

/** What [fromDisplay] will read as a number: digits, one optional point. */
private val NUMERIC = Regex("""^\d+(\.\d+)?$""")

/** "1–365 days", "0 = never": the bounds in the shown unit. */
fun SettingDescriptor.rangeText(): String {
    val u = unitWord
    var base = when {
        kind.type == "int" && kind.min != null && kind.max != null ->
            "${kind.min}–${kind.max}${if (u.isNotEmpty()) " $u" else ""}"
        // Through `toDisplay`, not `min / unitFactor()`: both are `Long`, so
        // that division TRUNCATED — a 900 s minimum shown in hours read "at
        // least 0 hours", advertising a floor the hub refuses, on the same row
        // where the value itself printed in correct fractional hours. The
        // desktop divides in floating point (`pages.ts`) and shows 0.25.
        kind.type == "secs" && (kind.min ?: 0) > 0 ->
            "at least ${toDisplay((kind.min ?: 0).toString())}${if (u.isNotEmpty()) " $u" else ""}"
        kind.type == "secs" -> u
        else -> ""
    }
    zero?.let { base = if (base.isEmpty()) "0 = $it" else "$base; 0 = $it" }
    return base
}

/** A value in words, as a person reads it: On / Off, an option's label, a
 *  number in its unit, "(empty)". The desktop's `valueInWords`. */
fun SettingDescriptor.inWords(value: String): String {
    if (value.isEmpty()) return "(empty)"
    return when (kind.type) {
        "bool" -> if (value == "true") "On" else "Off"
        "choice" -> optionLabel(value)
        "choice_set" -> value.split(',').joinToString(", ") { optionLabel(it.trim()) }
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
            // `review == "settings"`, not the layout alone: `review_apply` is
            // also the Guides page's layout, and if that is ever parented under
            // Settings the phone would list SETTINGS proposals under the Guides
            // title. It reads `null` on an older hub, which had only the one.
            (p.layout == "review_apply" && (p.review == null || p.review == "settings")) ||
                (p.layout == "category" && p.allSections.any { s -> s.items.any { PageItem.of(it) is PageItem.Field } })
            )
    }
