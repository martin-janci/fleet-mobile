package dev.claudefleet.mobile.model

/*
 * The autonomy dial (mission control hardening, claude-fleet #914): one
 * chip instead of three settings. Each position sets the mission loop's
 * ceiling (`orchestrator.max_level`), whether Control's chat goes on with
 * the recommended option and after how long (`control.auto_continue`,
 * `control.auto_continue_minutes`) and whether Control hears what changed
 * (`control.react_to_events`), together, through `set_setting`. A mix the
 * dial does not name reads as Custom. A port of the desktop's
 * `src/lib/autonomy.ts`.
 */

const val KEY_MAX_LEVEL = "orchestrator.max_level"
const val KEY_AUTO_CONTINUE = "control.auto_continue"
const val KEY_AUTO_CONTINUE_MINUTES = "control.auto_continue_minutes"
const val KEY_REACT_TO_EVENTS = "control.react_to_events"

/** The settings the dial reads and writes. */
val AUTONOMY_KEYS: List<String> = listOf(KEY_MAX_LEVEL, KEY_AUTO_CONTINUE, KEY_AUTO_CONTINUE_MINUTES, KEY_REACT_TO_EVENTS)

enum class Autonomy(
    val label: String,
    /** What it allows, one line (the board's words). */
    val blurb: String,
    val maxLevel: Int,
    val autoContinue: Boolean,
    /** Only read when [autoContinue]. */
    val minutes: Int,
    val reactToEvents: Boolean,
) {
    ASK("Ask first", "Proposes every step and waits. Still hears its sessions and tells you what happened.", 1, false, 10, true),
    BRIEF(
        "Within your brief",
        "Does what you handed it: answers its sessions, nudges, retries, and goes on with a recommended choice after 10 minutes. Asks for anything new.",
        2,
        true,
        10,
        true,
    ),
    AUTOPILOT(
        "Autopilot",
        "Also creates a mission's tasks and goes on with a recommended choice after 2 minutes. Never tags, deploys or deletes.",
        3,
        true,
        2,
        true,
    ),
}

private fun bool(v: String?): Boolean? = when (v?.trim()?.lowercase()) {
    "true", "1", "on", "yes" -> true
    "false", "0", "off", "no" -> false
    else -> null
}

/** Where the settings put the dial; null for a mix it does not name (Custom). */
fun autonomyOf(values: Map<String, String>): Autonomy? {
    val level = values[KEY_MAX_LEVEL]?.trim()?.toIntOrNull()
    val ac = bool(values[KEY_AUTO_CONTINUE])
    val minutes = values[KEY_AUTO_CONTINUE_MINUTES]?.trim()?.toIntOrNull()
    val react = bool(values[KEY_REACT_TO_EVENTS])
    return Autonomy.entries.firstOrNull { p ->
        p.maxLevel == level && p.autoContinue == ac && (!p.autoContinue || p.minutes == minutes) && p.reactToEvents == react
    }
}

/** The chip's word: the position's label, or "Custom". */
fun autonomyWord(a: Autonomy?): String = a?.label ?: "Custom"

/** The writes a position makes, in order; a value already set is skipped. */
fun autonomyWrites(target: Autonomy, values: Map<String, String>): List<Pair<String, String>> = buildList {
    add(KEY_MAX_LEVEL to target.maxLevel.toString())
    add(KEY_AUTO_CONTINUE to target.autoContinue.toString())
    if (target.autoContinue) add(KEY_AUTO_CONTINUE_MINUTES to target.minutes.toString())
    add(KEY_REACT_TO_EVENTS to target.reactToEvents.toString())
}.filter { (k, v) -> values[k] != v }
