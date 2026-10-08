package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.store.Prefs

/**
 * The two kinds of "a session needs you" notification a person can turn off
 * apart, on This phone (redesign 14.11): one waiting for an answer or a
 * permission, and one that stopped with an error. Done is not here yet: no
 * notification announces a finished session today.
 */
enum class NotifyKind(val label: String, val line: String) {
    NEEDS_YOU("Needs you", "A session waits for an answer or a permission"),
    FAILED("Failed", "A session stopped with an error"),
}

/** The kind of notification a hub attention reason makes: failed and stop_failed are Failed; the rest wait on a person. */
fun notifyKindOf(reason: String?): NotifyKind = when (reason) {
    "failed", "stop_failed" -> NotifyKind.FAILED
    else -> NotifyKind.NEEDS_YOU
}

/** Which kinds this phone announces. Everything is on until someone turns a kind off. */
data class NotifyKinds(val off: Set<NotifyKind> = emptySet()) {
    fun allows(kind: NotifyKind): Boolean = kind !in off

    /** Whether an alert for a session with this attention [reason] is posted. */
    fun allows(reason: String?): Boolean = allows(notifyKindOf(reason))

    fun with(kind: NotifyKind, on: Boolean): NotifyKinds = NotifyKinds(if (on) off - kind else off + kind)
}

private const val NOTIFY_OFF_PREF = "phone.notify.off"

/**
 * Read fresh on every alert, not cached: the Android service builds its own
 * container, and a switch flipped in the open app must reach it at once.
 * Stored as the kinds turned OFF, so a kind added later starts on.
 */
fun Prefs.notifyKinds(): NotifyKinds =
    NotifyKinds(getStringList(NOTIFY_OFF_PREF).mapNotNull { name -> NotifyKind.entries.firstOrNull { it.name == name } }.toSet())

fun Prefs.writeNotifyKinds(kinds: NotifyKinds) {
    putStringList(NOTIFY_OFF_PREF, kinds.off.map { it.name }.sorted())
}
