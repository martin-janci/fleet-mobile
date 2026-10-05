package dev.claudefleet.mobile.notify

/** The thread every "needs you" notification shares, so the system groups them. */
const val NEEDS_YOU_THREAD: String = "needs_you"

/** Where a notification carries its session, for the tap that opens it. */
const val NEEDS_YOU_SESSION_KEY: String = "sessionId"

const val NEEDS_YOU_ID_PREFIX: String = "needs-you-"

/** One identifier per session: a session that needs you again replaces its own notification. */
fun needsYouId(sessionId: Long): String = "$NEEDS_YOU_ID_PREFIX$sessionId"

/** What a platform's notification says, decided once for every platform that posts one. */
data class NeedsYouContent(
    val id: String,
    val thread: String,
    val title: String,
    val body: String,
    val sessionId: Long,
)

fun needsYouContent(alert: NeedsYouAlert): NeedsYouContent = NeedsYouContent(
    id = needsYouId(alert.sessionId),
    thread = NEEDS_YOU_THREAD,
    title = alert.title,
    // What it is doing, when there is a line for it: the question is half of
    // whether to pick the phone up. Same as Android's BigTextStyle.
    body = listOfNotNull(alert.text, alert.detail).joinToString("\n"),
    sessionId = alert.sessionId,
)
