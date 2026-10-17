package dev.claudefleet.mobile.model

/*
 * Fleet's own prompts in Control's chat (mission control hardening,
 * claude-fleet #913 and #914): a prompt that opens with `[fleet events]`
 * (the operator feed's digest, `service/operator_feed.rs`) or `[fleet]` (a
 * note after an automatic restart, `service/auto_restore.rs`) is fleet
 * talking, not the person. The chat draws it as a folded "Fleet · N events"
 * line that opens into the list, followed by Control's reaction as usual.
 * A port of the desktop's `src/lib/fleet_events.ts`.
 */

/** `prompt_origin::FLEET_PROMPT_MARKS` in fleet-core. */
val FLEET_PROMPT_MARKS: List<String> = listOf("[fleet events]", "[fleet]")

/** Whether [prompt] opens with one of the marks (only at its head: a person quoting one mid-sentence keeps their words). */
fun isFleetPrompt(prompt: String?): Boolean {
    val t = prompt?.trimStart() ?: return false
    return FLEET_PROMPT_MARKS.any { t.startsWith(it) }
}

data class FleetEvent(
    val text: String,
    /** The session a digest line names (`… (#12) finished a turn`). */
    val sessionId: Long? = null,
)

data class FleetNote(
    /** True for the operator feed's digest, false for a restart's note. */
    val digest: Boolean,
    /** The text before the list (the digest's instructions to the agent). */
    val header: String,
    val events: List<FleetEvent>,
    /** Events the digest counted but did not list ("… and 3 more."). */
    val more: Int,
) {
    val total: Int get() = events.size + more

    /** The folded card's second line: the first event (the count is on the first line); null when none is listed. */
    val preview: String? get() = events.firstOrNull()?.text

    /** The folded line: "Fleet · 3 events", "Fleet · 1 event". */
    val line: String
        get() {
            val n = maxOf(total, 1)
            return "Fleet · $n ${if (n == 1) "event" else "events"}"
        }
}

private val MORE = Regex("""^…\s*and\s+(\d+)\s+more\.?$""")
private val SESSION_REF = Regex("""\(#(\d+)\b""")

private fun event(text: String) = FleetEvent(text, SESSION_REF.find(text)?.groupValues?.get(1)?.toLongOrNull())

/** A fleet prompt read into its events, or null for anyone else's words. */
fun parseFleetPrompt(prompt: String?): FleetNote? {
    if (!isFleetPrompt(prompt)) return null
    val t = prompt!!.trimStart()
    if (t.startsWith("[fleet events]")) {
        val header = mutableListOf<String>()
        val events = mutableListOf<FleetEvent>()
        var more = 0
        for (raw in t.removePrefix("[fleet events]").split('\n')) {
            val line = raw.trim()
            if (line.startsWith("- ")) {
                val item = line.substring(2).trim()
                val m = MORE.find(item)
                if (m != null) more += m.groupValues[1].toInt() else if (item.isNotEmpty()) events += event(item)
            } else if (line.isNotEmpty() && events.isEmpty()) {
                header += line
            }
        }
        return FleetNote(digest = true, header = header.joinToString(" "), events = events, more = more)
    }
    val text = t.removePrefix("[fleet]").trim()
    return FleetNote(digest = false, header = "", events = if (text.isEmpty()) emptyList() else listOf(event(text)), more = 0)
}
