package dev.claudefleet.mobile.notify

/** The thread every "needs you" notification shares, so the system groups them. */
const val NEEDS_YOU_THREAD: String = "needs_you"

/** Where a notification carries its session, for the tap that opens it. */
const val NEEDS_YOU_SESSION_KEY: String = "sessionId"

const val NEEDS_YOU_ID_PREFIX: String = "needs-you-"

/**
 * The category every "needs you" notification is posted in (iOS registers its
 * actions under it); the action identifiers below are the same on both
 * platforms.
 */
const val NEEDS_YOU_CATEGORY: String = "needs_you"

/** The category of a failure's notification: Open log and Later. */
const val NEEDS_YOU_FAILED_CATEGORY: String = "needs_you_failed"

/** The category of a failure that can be tried again (MobileControl): Open log and Retry. */
const val NEEDS_YOU_RETRY_CATEGORY: String = "needs_you_retry"

/** The category of a finished session's notification: Open and Later. */
const val NEEDS_YOU_DONE_CATEGORY: String = "needs_you_done"

/** Open the session at its question card (the tap does the same). */
const val NEEDS_YOU_ACTION_OPEN: String = "open"

/** Put the notification away; the session keeps waiting and stays in the Inbox. */
const val NEEDS_YOU_ACTION_LATER: String = "later"

/**
 * Open the failed session and send its last prompt again — in the app, on
 * arrival, through the Inbox's own Retry (`InboxViewModel.retry`), which
 * checks again that the row still failed and this device may write.
 */
const val NEEDS_YOU_ACTION_RETRY: String = "retry"

/** One identifier per session: a session that needs you again replaces its own notification. */
fun needsYouId(sessionId: Long): String = "$NEEDS_YOU_ID_PREFIX$sessionId"

/**
 * What a notification's button does (redesign 14.8, the manual's never-list):
 * open the app at the question, put the notification away, or — for a
 * failure, as the MobileControl board draws it — open the app and Retry
 * there. Every button that does anything brings the app up first (a locked
 * phone asks to be unlocked), so nothing is approved, denied or sent from the
 * lock screen itself; a question is still answered by a tap inside the app.
 */
enum class NotifyActionKind { Open, Later, Retry }

/** One button on a "needs you" notification. */
data class NotifyAction(val id: String, val label: String, val kind: NotifyActionKind)

/**
 * The buttons for a notification of [kind] (MobileControl): Answer and Later
 * for a question; Open log and Retry for a failure that can be tried again
 * ([retryable]), Open log and Later for one that cannot; Open and Later for
 * a session that finished.
 */
fun needsYouActions(kind: NotifyKind, retryable: Boolean = false): List<NotifyAction> {
    val later = NotifyAction(NEEDS_YOU_ACTION_LATER, "Later", NotifyActionKind.Later)
    return when (kind) {
        NotifyKind.NEEDS_YOU -> listOf(NotifyAction(NEEDS_YOU_ACTION_OPEN, "Answer", NotifyActionKind.Open), later)
        NotifyKind.FAILED -> listOf(
            NotifyAction(NEEDS_YOU_ACTION_OPEN, "Open log", NotifyActionKind.Open),
            if (retryable) NotifyAction(NEEDS_YOU_ACTION_RETRY, "Retry", NotifyActionKind.Retry) else later,
        )
        NotifyKind.DONE -> listOf(NotifyAction(NEEDS_YOU_ACTION_OPEN, "Open", NotifyActionKind.Open), later)
    }
}

fun needsYouActions(alert: NeedsYouAlert): List<NotifyAction> = needsYouActions(notifyKindOf(alert.reason), alert.retryable)

/** The category a notification of [kind] is posted in: one per set of buttons, since iOS fixes them per category. */
fun needsYouCategory(kind: NotifyKind, retryable: Boolean = false): String = when (kind) {
    NotifyKind.NEEDS_YOU -> NEEDS_YOU_CATEGORY
    NotifyKind.FAILED -> if (retryable) NEEDS_YOU_RETRY_CATEGORY else NEEDS_YOU_FAILED_CATEGORY
    NotifyKind.DONE -> NEEDS_YOU_DONE_CATEGORY
}

/** Every category with its buttons — what iOS registers, all at once, so one never replaces another. */
fun needsYouCategories(): Map<String, List<NotifyAction>> =
    NotifyKind.entries.flatMap { kind -> listOf(false, true).map { r -> needsYouCategory(kind, r) to needsYouActions(kind, r) } }.toMap()

/** What a platform's notification says, decided once for every platform that posts one. */
data class NeedsYouContent(
    val id: String,
    val thread: String,
    val title: String,
    val body: String,
    val sessionId: Long,
    /**
     * What the lock screen shows before the phone is unlocked: the session and
     * why, without the question — a command can carry paths, branch names or
     * worse, and a locked phone is anyone's to read.
     */
    val publicTitle: String = title,
    val publicBody: String = body,
    val category: String = NEEDS_YOU_CATEGORY,
    val actions: List<NotifyAction> = emptyList(),
)

/** "<session> needs you", "<session> failed" for a failure (MobileControl), "<session> is done" when it finished. */
fun needsYouHeadline(alert: NeedsYouAlert): String = when (notifyKindOf(alert.reason)) {
    NotifyKind.FAILED -> "${alert.title} failed"
    NotifyKind.NEEDS_YOU -> "${alert.title} needs you"
    NotifyKind.DONE -> "${alert.title} is done"
}

fun needsYouContent(alert: NeedsYouAlert): NeedsYouContent {
    val headline = needsYouHeadline(alert)
    return NeedsYouContent(
        id = needsYouId(alert.sessionId),
        thread = NEEDS_YOU_THREAD,
        title = headline,
        // The question first: it is half of whether to pick the phone up; then
        // why and where, and what it is doing when there is a line for it.
        body = listOfNotNull(alert.question, alert.text, alert.detail).joinToString("\n"),
        sessionId = alert.sessionId,
        publicTitle = headline,
        publicBody = alert.text,
        category = needsYouCategory(notifyKindOf(alert.reason), alert.retryable),
        actions = needsYouActions(alert),
    )
}
