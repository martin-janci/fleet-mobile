package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A ticket's context card, `work { action: card }` (claude-fleet M9.2): the
 * acceptance criteria parsed from the hub's cached description, or an
 * excerpt when it has none. Read from the hub's cache only — the hub never
 * calls the tracker for it — so a key nobody cached answers with
 * [cached] false and nothing else to show.
 *
 * Every string is the tracker's text: drawn as plain text, never as markup.
 * The hub's `composer_text` (what the desktop inserts into its composer) is
 * not read: the phone does not type a ticket into a prompt.
 */
@Serializable
data class TicketCard(
    val key: String,
    val title: String = "",
    val url: String? = null,
    @SerialName("status_name") val statusName: String? = null,
    @SerialName("status_category") val statusCategory: StatusCategory? = null,
    @SerialName("org_id") val orgId: Long? = null,
    val cached: Boolean = false,
    val acceptance: List<String> = emptyList(),
    val excerpt: String? = null,
) {
    /** Whether the card has anything to say beyond the key and title. */
    val hasBody: Boolean get() = acceptance.isNotEmpty() || !excerpt.isNullOrBlank()

    /**
     * What **Copy** puts on the clipboard (claude-fleet M10.5): the key and
     * title, the status, the link, then the acceptance criteria — or the
     * excerpt when there are none. Plain text, the tracker's words as they
     * are; the phone copies a card and never sends one.
     *
     * The link is only included when it is `http(s)`, the same rule the
     * sheets' *Open in browser* follows.
     */
    val copyText: String
        get() = buildString {
            append(key)
            if (title.isNotBlank()) append(" · ").append(title.trim())
            statusName?.takeIf { it.isNotBlank() }?.let { append("\nStatus: ").append(it.trim()) }
            url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { append('\n').append(it) }
            val criteria = acceptance.map { it.trim() }.filter { it.isNotEmpty() }
            when {
                criteria.isNotEmpty() -> {
                    append("\n\nAcceptance criteria")
                    for (line in criteria) append("\n- ").append(line)
                }
                !excerpt.isNullOrBlank() -> append("\n\n").append(excerpt.trim())
            }
        }
}

/**
 * One acceptance criterion as a description writes it: its text, and whether
 * it is ticked — `[x]`, `☑`, `✅` — when the description uses checkboxes at
 * all ([checkbox]). Tracker text: drawn as plain text only.
 */
data class Criterion(val text: String, val done: Boolean = false, val checkbox: Boolean = false)

/**
 * PURE: the acceptance criteria a task's description names, in order — the
 * hub's own reading (`service::work::card::acceptance_criteria`, the one that
 * fills [TicketCard.acceptance]) carried over to the phone, so a task's
 * detail can list them as the ticket card does, with what is ticked.
 *
 * The first section headed *Acceptance criteria* (*AC*, *Definition of
 * done* …); its list items, Gherkin lines or — with neither — its plain
 * lines, up to the next heading-looking line or two blank lines. None named
 * → empty. At most [CRITERIA_MAX], each cut at [CRITERION_MAX_CHARS].
 */
fun acceptanceCriteria(text: String): List<Criterion> {
    val lines = text.lines().map { it.trimEnd('\r') }
    val start = lines.indexOfFirst { acHeading(it) != null }
    if (start < 0) return emptyList()
    val out = mutableListOf<Criterion>()
    acHeading(lines[start])?.takeIf { it.isNotEmpty() }?.let { out += Criterion(it) }
    var blankRun = 0
    for (line in lines.drop(start + 1)) {
        if (line.isBlank()) {
            blankRun++
            // Two blank lines end a section that has started.
            if (blankRun >= 2 && out.isNotEmpty()) break
            continue
        }
        blankRun = 0
        if (acHeading(line) != null || nextHeading(line)) break
        val item = listItem(line) ?: Criterion(line.trim())
        if (item.text.isNotEmpty()) out += item
        if (out.size >= CRITERIA_MAX) break
    }
    return out.map { c ->
        if (c.text.length <= CRITERION_MAX_CHARS) c else c.copy(text = c.text.take(CRITERION_MAX_CHARS - 1) + "…")
    }
}

/**
 * How far along the criteria are, in the board's words: "1 of 3 done" when
 * the description ticks them, else how many there are ("3 criteria"). Null
 * when there are none.
 */
fun acceptanceProgress(criteria: List<Criterion>): String? = when {
    criteria.isEmpty() -> null
    criteria.any { it.checkbox } -> "${criteria.count { it.done }} of ${criteria.size} done"
    criteria.size == 1 -> "1 criterion"
    else -> "${criteria.size} criteria"
}

/** At most this many criteria, each at most [CRITERION_MAX_CHARS] — the hub's caps. */
const val CRITERIA_MAX = 20
const val CRITERION_MAX_CHARS = 300

private val AC_NAMES = listOf("acceptance criteria", "acceptance criterion", "acceptance tests", "definition of done", "ac", "dod")

/** Is [line] the heading of an acceptance-criteria section? The text after it on the same line ("AC: it works"). */
private fun acHeading(line: String): String? {
    val t = line.trim().trimStart('#', '*', '_', '=', ' ').trimEnd('*', '_', ' ')
    for (name in AC_NAMES) {
        if (!t.startsWith(name, ignoreCase = true)) continue
        val rest = t.substring(name.length).trimStart()
        if (rest.isEmpty()) return ""
        if (rest.startsWith(':')) return rest.substring(1).trimStart('*', '_').trim()
    }
    return null
}

/** A Given / When / Then / And line of a Gherkin scenario. */
private fun gherkin(line: String): Boolean {
    val t = line.trimStart().lowercase()
    return listOf("given ", "when ", "then ", "and ", "but ", "scenario:").any { t.startsWith(it) }
}

private val SECTIONS = setOf(
    "description", "notes", "note", "background", "context", "summary", "steps to reproduce",
    "expected result", "actual result", "out of scope", "technical notes", "implementation notes",
    "design", "links", "attachments", "questions", "open questions", "user story",
)

/** Looks like the heading of the next section ("Notes", "Out of scope:"). */
private fun nextHeading(line: String): Boolean {
    val t = line.trim()
    if (t.startsWith('#')) return true
    val bare = t.trim('*', '_').trim()
    val lower = bare.trimEnd(':').lowercase()
    return lower in SECTIONS || (bare.endsWith(':') && bare.length <= 40 && listItem(line) == null && !gherkin(line))
}

private val TICKED = listOf("[x] ", "[X] ", "☑ ", "✅ ")
private val UNTICKED = listOf("[ ] ", "☐ ")

/** A list item without its marker (`-`, `*`, `•`, `+`, `1.`, `2)`, `[ ]`, `[x]`), or null when [line] is not one. */
private fun listItem(line: String): Criterion? {
    val whole = line.trim()
    var t = listOf("- ", "* ", "• ", "+ ").firstOrNull { whole.startsWith(it) }?.let { whole.substring(it.length) }
        ?: run {
            val digits = whole.takeWhile { it in '0'..'9' }.length
            if (digits in 1..3) {
                val r = whole.substring(digits)
                if (r.startsWith(". ") || r.startsWith(") ")) r.substring(2) else null
            } else {
                null
            }
        }
        ?: whole
    t = t.trim()
    val ticked = TICKED.firstOrNull { t.startsWith(it) }
    val unticked = UNTICKED.firstOrNull { t.startsWith(it) }
    val marker = ticked ?: unticked
    if (marker != null) t = t.substring(marker.length)
    t = t.trim()
    return if (t.length < whole.length) Criterion(t, done = ticked != null, checkbox = marker != null) else null
}
