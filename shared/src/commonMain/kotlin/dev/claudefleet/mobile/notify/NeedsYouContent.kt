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

/** The category of a failure's notification: its open button reads Open rather than Answer. */
const val NEEDS_YOU_FAILED_CATEGORY: String = "needs_you_failed"

/** Open the session at its question card (the tap does the same). */
const val NEEDS_YOU_ACTION_OPEN: String = "open"

/** Put the notification away; the session keeps waiting and stays in the Inbox. */
const val NEEDS_YOU_ACTION_LATER: String = "later"

/** One identifier per session: a session that needs you again replaces its own notification. */
fun needsYouId(sessionId: Long): String = "$NEEDS_YOU_ID_PREFIX$sessionId"

/**
 * What a notification's button does. Only two things, on purpose (redesign
 * 14.8, the manual's never-list): open the app at the question, or put the
 * notification away. Nothing is approved, denied, retried or sent from a
 * notification or the lock screen; the answer is a tap inside the app.
 */
enum class NotifyActionKind { Open, Later }

/** One button on a "needs you" notification. */
data class NotifyAction(val id: String, val label: String, val kind: NotifyActionKind)

/** The buttons for a notification of [kind]: Answer (Open, for a failure) and Later. */
fun needsYouActions(kind: NotifyKind): List<NotifyAction> = listOf(
    NotifyAction(NEEDS_YOU_ACTION_OPEN, if (kind == NotifyKind.FAILED) "Open" else "Answer", NotifyActionKind.Open),
    NotifyAction(NEEDS_YOU_ACTION_LATER, "Later", NotifyActionKind.Later),
)

fun needsYouActions(alert: NeedsYouAlert): List<NotifyAction> = needsYouActions(notifyKindOf(alert.reason))

/** The category a notification of [kind] is posted in. */
fun needsYouCategory(kind: NotifyKind): String = if (kind == NotifyKind.FAILED) NEEDS_YOU_FAILED_CATEGORY else NEEDS_YOU_CATEGORY

/** Every category with its buttons — what iOS registers, all at once, so one never replaces another. */
fun needsYouCategories(): Map<String, List<NotifyAction>> =
    NotifyKind.entries.associate { needsYouCategory(it) to needsYouActions(it) }

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

/** "<session> needs you", or "<session> failed" for a failure (MobileControl). */
fun needsYouHeadline(alert: NeedsYouAlert): String = when (notifyKindOf(alert.reason)) {
    NotifyKind.FAILED -> "${alert.title} failed"
    NotifyKind.NEEDS_YOU -> "${alert.title} needs you"
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
        category = needsYouCategory(notifyKindOf(alert.reason)),
        actions = needsYouActions(alert),
    )
}
