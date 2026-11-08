package dev.claudefleet.mobile.model

/*
 * A person's prompt as the conversation shows it (owner report 2026-11-06):
 * the words they typed, with what the desktop put in front of them drawn as
 * chips instead of raw text. The phone writes none of these heads itself,
 * but it shows conversations sent from the desktop, Control's above all:
 *
 * - the composer's context line: `[context] the person is looking at
 *   session "…" on host …`;
 * - a mission thread's prefix: `About mission #4 "Payments v2":`;
 * - a dashboard's "Change with the agent": the dashboard as a fleet-ui
 *   block and the instructions to save it, ending in `Change it: ` or
 *   `Change the widget "…": `.
 *
 * Each is recognised only at the head of the prompt and only in the exact
 * shape the desktop writes, so a person who types something alike keeps
 * their words. A port of the desktop's `src/lib/prompt_parts.ts`: change
 * both together.
 */

sealed interface PromptChip {
    data class Session(val name: String, val host: String, val branch: String?) : PromptChip
    data class Host(val host: String) : PromptChip
    data class Mission(val id: Long, val name: String) : PromptChip
    data class Dashboard(val title: String, val widget: String?, val copy: Boolean) : PromptChip

    /** The chip's words. */
    val label: String
        get() = when (this) {
            is Session -> listOfNotNull(name, host, branch).joinToString(" · ")
            is Host -> host
            is Mission -> "Mission $name"
            is Dashboard -> {
                val what = if (copy) "Dashboard “$title” (your copy)" else "Dashboard “$title”"
                if (widget != null) "$what › $widget" else what
            }
        }

    /** What the chip says it is, for a screen reader. */
    val about: String
        get() = when (this) {
            is Session -> "You were looking at this session"
            is Host -> "You were looking at this host"
            is Mission -> "About this mission"
            is Dashboard -> if (widget != null) "A change to this widget of the dashboard" else "A change to this dashboard"
        }
}

data class PromptParts(val chips: List<PromptChip>, /** What the person typed. */ val text: String)

private val SESSION_CONTEXT = Regex(
    """^\[context\] the person is looking at session "(.*)" on host (\S+?)(?: \(branch (.+)\))?\. "this one" and "here" mean that session unless they say otherwise\.(?:\n\n|$)""",
)
private val HOST_CONTEXT = Regex(
    """^\[context\] the person is looking at host (\S+) with no session selected\. "here" means that host\.(?:\n\n|$)""",
)
private val MISSION = Regex("""^About mission #(\d+) "(.*)":(?:\n\n|$)""")
private val DASHBOARD = Regex(
    """^(?:My pinned dashboard "(.*)", as it is now:|The built-in dashboard "(.*)", to make my own copy of \(keep the id [A-Za-z0-9._:-]+\):)\n\n```fleet-ui\n[\s\S]*?\n```\n\n""" +
        """(?:Save the change with `dashboards \{ pin \}` under the same id, then answer with the `dashboard` block\.|Answer with a `dashboard` block with the same id\.) (?:Change the widget "(.*)": |Change it: )""",
)

/** [prompt] with the desktop's own head lines read into chips. */
fun promptParts(prompt: String): PromptParts {
    val chips = mutableListOf<PromptChip>()
    var rest = prompt
    while (true) {
        val m = SESSION_CONTEXT.find(rest)?.also {
            val g = it.groups
            chips += PromptChip.Session(g[1]!!.value, g[2]!!.value, g[3]?.value)
        } ?: HOST_CONTEXT.find(rest)?.also {
            chips += PromptChip.Host(it.groupValues[1])
        } ?: MISSION.find(rest)?.also {
            chips += PromptChip.Mission(it.groupValues[1].toLong(), it.groupValues[2])
        } ?: DASHBOARD.find(rest)?.also {
            val g = it.groups
            val copy = g[2] != null
            chips += PromptChip.Dashboard((if (copy) g[2] else g[1])?.value.orEmpty(), g[3]?.value, copy)
        } ?: break
        rest = rest.substring(m.value.length)
    }
    return PromptParts(chips, if (chips.isEmpty()) prompt else rest.trim())
}
