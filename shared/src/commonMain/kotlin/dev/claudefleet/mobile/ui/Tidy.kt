package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.TidyApplyItem
import dev.claudefleet.mobile.model.TidyApplyResult
import dev.claudefleet.mobile.model.TidyCandidate

/**
 * Tidy-up's rules, held to the desktop's `tidy.ts`: what a candidate may be
 * turned into, what is suggested, and what is ticked to begin with.
 */
enum class TidyChoice(val wire: String, val label: String) {
    SafeKill("safe_kill", "Safe kill"),
    Kill("kill", "Kill"),
    Archive("archive", "Archive only"),
    Snooze("snooze", "Snooze 7 d"),
    Never("never", "Never for this work"),
    Keep("keep", "Keep $KEEP_DAYS d"),
}

/** How long Keep (and Snooze) hold a session out of Tidy-up. */
const val KEEP_DAYS: Int = 7

private val REASON_ORDER = listOf("done_idle", "pr_merged_idle", "not_planned", "duplicate_worktree", "ghost_expiring", "idle_unlinked")

private val REASON_LABELS = mapOf(
    "done_idle" to "Done and idle",
    "pr_merged_idle" to "PR merged, idle",
    "not_planned" to "Won't do / duplicate",
    "duplicate_worktree" to "Duplicate on one worktree",
    "ghost_expiring" to "Lost session about to expire",
    "idle_unlinked" to "Idle, no work linked",
)

internal fun tidyReasonLabel(reason: String): String = REASON_LABELS[reason] ?: reason.replace('_', ' ')

/**
 * One failed apply in words: the session's name rather than its row id, and
 * the action as the menu said it rather than its wire value.
 */
internal fun tidyFailureLine(sessionId: Long, error: String?, action: String, candidates: List<TidyCandidate>): String {
    val c = candidates.firstOrNull { it.sessionId == sessionId }
    val name = c?.let { it.label?.takeIf { l -> l.isNotBlank() } ?: it.tmuxName } ?: "a session no longer listed"
    val what = TidyChoice.entries.firstOrNull { it.wire == action }?.label ?: action.replace('_', ' ')
    return "$name (${error ?: what})"
}

/** What [c] may become: its suggested kill, then what its link allows (archive, snooze, never), or keep. */
internal fun tidyChoices(c: TidyCandidate): List<TidyChoice> = buildList {
    if (c.action == "safe_kill") add(TidyChoice.SafeKill)
    if (c.action == "kill") add(TidyChoice.Kill)
    if (c.linkId != null) {
        if (c.action != "resume_or_expire" && !c.archived) add(TidyChoice.Archive)
        add(TidyChoice.Snooze)
        add(TidyChoice.Never)
    } else if (c.action == "safe_kill" || c.action == "kill") {
        add(TidyChoice.Keep)
    }
}

/** The suggested choice: the hub's own action when it is one, else the first offered. */
internal fun tidyDefault(c: TidyCandidate): TidyChoice? {
    val choices = tidyChoices(c)
    return choices.firstOrNull { it.wire == c.action } ?: choices.firstOrNull()
}

/** Ticked to begin with — never a lost session about to expire, never one merely idle and unlinked. */
internal fun tidyPreselected(c: TidyCandidate): Boolean =
    c.action != "resume_or_expire" && c.reason != "idle_unlinked" && tidyDefault(c) != null

/** The candidates by reason, in the desktop's order. */
internal fun tidyGroups(candidates: List<TidyCandidate>): List<Pair<String, List<TidyCandidate>>> =
    candidates.groupBy { it.reason }.entries
        .sortedBy { REASON_ORDER.indexOf(it.key).let { i -> if (i < 0) 99 else i } }
        .map { it.key to it.value }

/** What to send for the ticked candidates, each with its chosen (or suggested) action. */
internal fun tidyItems(
    candidates: List<TidyCandidate>,
    ticked: Set<Long>,
    chosen: Map<Long, TidyChoice>,
): List<TidyApplyItem> = candidates.mapNotNull { c ->
    if (c.sessionId !in ticked) return@mapNotNull null
    val choice = chosen[c.sessionId] ?: tidyDefault(c) ?: return@mapNotNull null
    TidyApplyItem(
        sessionId = c.sessionId,
        action = choice.wire,
        linkId = c.linkId,
        days = KEEP_DAYS.takeIf { choice == TidyChoice.Snooze || choice == TidyChoice.Keep },
    )
}

/** An applied choice that **Undo** can take back: an archive (`unarchive` restores the link). */
internal fun tidyUndoable(item: TidyApplyItem): Boolean = item.action == TidyChoice.Archive.wire && item.linkId != null

/** One applied choice in the result's words: "Archived", "Killed", "Snoozed 7 d". */
internal fun tidyDoneWord(action: String): String = when (action) {
    TidyChoice.Archive.wire -> "Archived"
    TidyChoice.Kill.wire, TidyChoice.SafeKill.wire -> "Killed"
    TidyChoice.Snooze.wire -> "Snoozed $KEEP_DAYS d"
    TidyChoice.Keep.wire -> "Kept $KEEP_DAYS d"
    TidyChoice.Never.wire -> "Never for this work"
    else -> action.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/**
 * The result's headline: "✓ Archived 2, killed 1", counting what the hub
 * took (an archive taken back with Undo no longer counts); "Nothing was
 * applied" when it took none.
 */
internal fun tidyDoneLine(results: List<TidyApplyResult>, undone: Set<Long>): String {
    val took = results.filter { it.ok && it.sessionId !in undone }
    if (took.isEmpty()) return "Nothing was applied"
    val parts = took.groupBy { tidyDoneWord(it.action).substringBefore(' ') }.entries.mapIndexed { i, (word, rs) ->
        (if (i == 0) word else word.lowercase()) + " ${rs.size}"
    }
    return "✓ " + parts.joinToString(", ")
}

/** What one row of the result says under the session's name. */
internal fun tidyOutcomeLine(r: TidyApplyResult, undone: Boolean): String = when {
    undone -> "Undone · back in the list"
    r.ok -> tidyDoneWord(r.action) + (r.outcome?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    else -> "Could not ${tidyVerb(r.action)}" +
        (r.error?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
}

/** A candidate's reason line on the New sheet: the rule's reason, its ticket, how long it has been idle. */
internal fun tidyReasonLine(c: TidyCandidate): String = listOfNotNull(
    tidyReasonLabel(c.reason),
    c.key?.takeIf { it.isNotBlank() }?.let { k -> listOfNotNull(k, c.itemStatus?.takeIf { it.isNotBlank() }).joinToString(" is ") },
    c.idleSecs?.let { "idle ${formatIdle(it)}" },
).joinToString(" · ")

/** The verb for a choice, as "Could not …" says it. */
private fun tidyVerb(action: String): String = when (action) {
    TidyChoice.Kill.wire, TidyChoice.SafeKill.wire -> "kill"
    TidyChoice.Archive.wire -> "archive"
    TidyChoice.Snooze.wire -> "snooze"
    TidyChoice.Keep.wire -> "keep"
    TidyChoice.Never.wire -> "set never"
    else -> action.replace('_', ' ')
}
