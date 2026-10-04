package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.ActivityProbe
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn

/**
 * The actions under one reply — Copy, Quote, Retry, Rewind here, Fork here —
 * as the desktop's `reply_actions.ts` decides them, so a turn offers the same
 * on both. Fork, Rewind and Retry are one hub operation,
 * `rewind_conversation`, which copies the transcript up to an anchor into a
 * new conversation and never changes the original.
 */
internal data class ReplyActionsView(
    val canFork: Boolean = false,
    val canRewind: Boolean = false,
    /** A rewind plus a re-send, so it needs a prompt that can be sent again as it was. */
    val canRetry: Boolean = false,
    /** Why Retry is not offered on a turn that could be rewound; shown rather than hidden. */
    val retryUnavailable: String? = null,
    /** Keep strictly before this; null keeps the whole transcript. */
    val forkAnchor: String? = null,
    val rewindAnchor: String? = null,
)

/**
 * What the turn at [index] (oldest first, as the hub sends them) offers.
 *
 * Fork keeps everything through this turn, so it anchors on the next later
 * prompt, scanning past prompt-less turns: keeping more history is safe,
 * keeping less would drop work. Rewind keeps everything before this turn's
 * own prompt — except at the conversation's very first turn ([truncated]
 * false), where it would leave an empty conversation: that is `/clear`.
 * [supported] is the hub having `rewind_conversation` at all and this token
 * being allowed to write.
 */
internal fun replyActionsFor(turns: List<ConvTurn>, index: Int, truncated: Boolean, supported: Boolean): ReplyActionsView {
    if (!supported || index !in turns.indices) return ReplyActionsView()
    val forkAnchor = turns.drop(index + 1).firstNotNullOfOrNull { it.promptUuid }
    val own = turns[index].promptUuid
    val canRewind = own != null && !(index == 0 && !truncated)
    val retryUnavailable = if (canRewind) retryBlockedReason(turns[index]) else null
    return ReplyActionsView(
        canFork = true,
        canRewind = canRewind,
        canRetry = canRewind && retryUnavailable == null,
        retryUnavailable = retryUnavailable,
        forkAnchor = forkAnchor,
        rewindAnchor = if (canRewind) own else null,
    )
}

/**
 * Why this turn's prompt cannot be sent again as "the same prompt", or null
 * when it can: an image-only prompt has no text, and a partial one is not
 * what was asked.
 */
internal fun retryBlockedReason(turn: ConvTurn): String? = when {
    turn.prompt.isNullOrEmpty() ->
        "This prompt had no text to send again (an image only). Rewind puts the conversation back; attach the image again yourself."
    turn.promptPartial ->
        "The prompt shown is not the whole prompt (it was cut to fit, or held an image). Rewind here, then send it yourself."
    else -> null
}

/** The reply's own words: every text item of the turn, in order. What Copy and Quote take. */
internal fun replyText(turn: ConvTurn): String =
    turn.items.filterIsInstance<ConvItem.Text>().joinToString("\n\n") { it.text.trim() }.trim()

/** [text] as a Markdown block quote, ready to precede the person's own words. */
internal fun quoteText(text: String): String =
    text.split("\n").joinToString("\n") { if (it.isEmpty()) ">" else "> $it" } + "\n\n"

/**
 * One probe showing a REPL back at its input prompt — `idle`, no dialog, no
 * spinner. Retry needs two in a row before it sends: a rewind respawns the
 * pane, and the first capture can still be the old REPL's footer.
 */
internal fun isReplReady(probe: ActivityProbe): Boolean =
    probe.claudeStatus == "idle" && probe.pendingInput == null && probe.spinner == null

/** Consecutive [isReplReady] probes Retry waits for, how often it asks, and for how long. */
internal const val READY_STREAK: Int = 2
internal const val READY_POLL_MS: Long = 750
internal const val READY_TIMEOUT_MS: Long = 30_000

/** `fork-of-<name>` as a git-safe worktree name: the desktop's suggestion for Fork here. */
internal fun forkWorktreeName(sessionName: String): String = branchSlug("fork-of-$sessionName").ifEmpty { "fork" }

/**
 * Free text as a git-safe branch / worktree name, as the desktop's
 * `finalizeBranchSlug` makes one: lowercase, whitespace and `_` to `-`,
 * `[a-z0-9./-]` only, no runs of separators, none leading or trailing, at
 * most 60 characters. Empty when nothing usable is left.
 */
internal fun branchSlug(raw: String): String {
    var out = raw.lowercase().replace(Regex("[\\s_]+"), "-")
        .filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '/' || it == '-' }
    out = out.replace(Regex("-{2,}"), "-").replace(Regex("\\.{2,}"), ".").replace(Regex("/{2,}"), "/")
        .trimStart('-', '.', '/')
    if (out.length > 60) out = out.take(60)
    return out.trimEnd('-', '.', '/')
}
