package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.log.civilFromDays

/*
 * "Plan first" (claude-fleet `docs/plan-first.md`, `src/lib/plan_first.ts`):
 * a switch on New session that tells the agent to write a plan file and stop,
 * and a "Plan ready" card on the session once the agent says so and the file
 * it names is among the session's changes. No phase engine and nothing
 * advances by itself: the person reads the plan and says go.
 *
 * Nothing records that a session was started this way. The agent's own words
 * are the signal ("Plan ready" and the file's name, since the last prompt),
 * so the card shows on every device, for a session started anywhere, and
 * goes away with the next prompt whoever sent it. The words here are the
 * desktop's, so a session started on either reads alike.
 */

/** What the agent is told when Plan first is on. [today] is `YYYY-MM-DD`. */
fun planFirstBrief(today: String): String = listOf(
    "Plan first. For the task in this session, do not change any code yet:",
    "1. Research how things work today: read the code and docs the task touches. Describe what is there; do not propose anything in this step.",
    "2. Write the plan to docs/plans/$today-<short-slug>.md and do not commit it: the goal in two sentences, what you found, " +
        "the steps as small vertical slices (each one ends with a check that shows it works: a test, a command, a screen), " +
        "and open questions for me at the end. Decide nothing I have to decide; list it as a question.",
    "3. Stop. Say \"Plan ready\" with the file's path and wait; say it again each time you change the plan. " +
        "Start the work only after I say the plan is approved, and follow the plan as approved.",
).joinToString("\n")

/** The first message with the Plan first paragraph under it; the paragraph alone when nothing was typed. */
fun withPlanFirst(message: String, today: String): String {
    val typed = message.trimEnd()
    return if (typed.isEmpty()) planFirstBrief(today) else "$typed\n\n${planFirstBrief(today)}"
}

/** The prompt Approve sends. */
fun planApprovedPrompt(path: String): String =
    "The plan in $path is approved. Go ahead and implement it, step by step, running each step's check before the next."

/** What "Ask for changes…" starts the composer with. */
fun planChangesPrefix(path: String): String =
    "Change the plan in $path, then say \"Plan ready\" again (do not start the work yet): "

/** [draft] with [planChangesPrefix] in front of it, once: what was typed stays after it. */
fun planChangesDraft(draft: String, path: String): String {
    val prefix = planChangesPrefix(path)
    return if (draft.startsWith(prefix)) draft else prefix + draft
}

/** The plan file's date prefix: the local day of [nowSeconds], `YYYY-MM-DD`. */
fun planDate(nowSeconds: Long, utcOffsetSeconds: Int): String {
    val (year, month, day) = civilFromDays(localEpochDay(nowSeconds, utcOffsetSeconds))
    fun two(n: Long) = n.toString().padStart(2, '0')
    return "${year.toString().padStart(4, '0')}-${two(month)}-${two(day)}"
}

/** "Plan ready", as the instructions ask for it ("the plan is ready" too). */
private val SAYS_READY = Regex("""\bplan\s+(is\s+)?ready\b""", RegexOption.IGNORE_CASE)

/**
 * The agent's "Plan ready": what it said since the last prompt, and when that
 * prompt was, which tells one announcement from the next.
 */
data class PlanSaid(val text: String, val at: String)

/**
 * What the agent said since the last prompt, when that says its plan is
 * ready; null otherwise. Turns no prompt opened (a notification, a
 * compaction) belong to the prompt before them.
 */
fun planSaid(turns: List<ConvTurn>): PlanSaid? {
    if (turns.isEmpty()) return null
    var from = turns.lastIndex
    while (from > 0 && turns[from].prompt.isNullOrEmpty()) from--
    val text = turns.subList(from, turns.size)
        .flatMap { turn -> turn.items.filterIsInstance<ConvItem.Text>().map { it.text } }
        .joinToString("\n")
    return if (SAYS_READY.containsMatchIn(text)) PlanSaid(text, turns[from].at.orEmpty()) else null
}

/** A Markdown file directly in `docs/plans/` (or the older `docs/superpowers/plans/`), at any depth of the checkout. */
private val PLAN_PATH = Regex("""(^|/)docs/(superpowers/)?plans/[^/]+\.md$""", RegexOption.IGNORE_CASE)

/** New in the worktree: added or untracked. */
private val FRESH = setOf("A", "?")

/** Written by the session: new, edited, renamed or copied. Never deleted or in conflict. */
private val WRITTEN = FRESH + setOf("M", "R", "C")

/**
 * The plan [reply] announces, from the session's [changes]: a new or edited
 * plan file whose name the reply says. With several, a new one before an
 * edited one, then the last by path (the date prefix sorts the newest last).
 */
fun planFile(changes: List<ChangedFile>, reply: String): String? {
    val plans = changes.filter { c ->
        PLAN_PATH.containsMatchIn(c.path) && gitStatusLetter(c.status) in WRITTEN && reply.contains(c.path.substringAfterLast('/'))
    }
    val fresh = plans.filter { gitStatusLetter(it.status) in FRESH }
    return fresh.ifEmpty { plans }.maxOfOrNull { it.path }
}
