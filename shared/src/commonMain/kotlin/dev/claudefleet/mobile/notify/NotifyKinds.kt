package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.store.Prefs

/**
 * The kinds of notification a person can turn on and off apart, on This
 * phone (redesign 14.11): one waiting for an answer or a permission, one that
 * stopped with an error, and one that finished its task ([DONE_REASON]).
 * Done [startsOn] off: a finished session asks nothing of anyone, so it is
 * announced only to a person who asked for it.
 */
enum class NotifyKind(val label: String, val line: String, val startsOn: Boolean = true) {
    NEEDS_YOU("Needs you", "A session waits for an answer or a permission"),
    FAILED("Failed", "A session stopped with an error"),
    DONE("Done", "A session finished its task", startsOn = false),
}

/**
 * The notifier's own reason for a session that finished its task (the hub's
 * `completed` status) and asks nothing: not a hub attention reason, so it
 * never reaches the Inbox, only a Done notification.
 */
const val DONE_REASON: String = "done"

/** The kind of notification a reason makes: failed and stop_failed are Failed, [DONE_REASON] is Done; the rest wait on a person. */
fun notifyKindOf(reason: String?): NotifyKind = when (reason) {
    "failed", "stop_failed" -> NotifyKind.FAILED
    DONE_REASON -> NotifyKind.DONE
    else -> NotifyKind.NEEDS_YOU
}

/** Which kinds this phone announces: every kind as it [NotifyKind.startsOn] until someone flips it. */
data class NotifyKinds(val off: Set<NotifyKind> = NotifyKind.entries.filterNot { it.startsOn }.toSet()) {
    fun allows(kind: NotifyKind): Boolean = kind !in off

    /** Whether an alert for a session with this attention [reason] is posted. */
    fun allows(reason: String?): Boolean = allows(notifyKindOf(reason))

    fun with(kind: NotifyKind, on: Boolean): NotifyKinds = NotifyKinds(if (on) off - kind else off + kind)
}

private const val NOTIFY_OFF_PREF = "phone.notify.off"
private const val NOTIFY_ON_PREF = "phone.notify.on"

/**
 * Read fresh on every alert, not cached: the Android service builds its own
 * container, and a switch flipped in the open app must reach it at once.
 * Stored as the kinds that differ from how they start — the ones that start
 * on and were turned OFF, the ones that start off and were turned ON — so a
 * kind added later starts as its [NotifyKind.startsOn] says.
 */
fun Prefs.notifyKinds(): NotifyKinds {
    fun kinds(pref: String) = getStringList(pref).mapNotNull { name -> NotifyKind.entries.firstOrNull { it.name == name } }.toSet()
    val turnedOff = kinds(NOTIFY_OFF_PREF).filter { it.startsOn }
    val turnedOn = kinds(NOTIFY_ON_PREF)
    return NotifyKinds((turnedOff + NotifyKind.entries.filter { !it.startsOn && it !in turnedOn }).toSet())
}

fun Prefs.writeNotifyKinds(kinds: NotifyKinds) {
    putStringList(NOTIFY_OFF_PREF, kinds.off.filter { it.startsOn }.map { it.name }.sorted())
    putStringList(NOTIFY_ON_PREF, NotifyKind.entries.filter { !it.startsOn && it !in kinds.off }.map { it.name }.sorted())
}
