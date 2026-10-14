package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.LinkState
import dev.claudefleet.mobile.model.WorkTask

/**
 * What tapping a row of the Work list opens (This phone): its ticket, as it
 * always has, or the session working on it. Session falls back to the
 * ticket on a task no session works on; every row with a live session also
 * carries a Session button, so both stay one tap away whichever is picked.
 */
enum class WorkTapChoice(val label: String) {
    TICKET("Ticket"),
    SESSION("Session"),
}

/** Settings' line under the choice, Classic's and New's alike. */
internal const val WORK_TAP_LINE =
    "Session opens the session working on the task, or the ticket when none is. Rows with a session also have a button for it."

/** Where a tap on a Work row goes. */
sealed interface WorkTapTarget {
    data class Ticket(val taskId: String) : WorkTapTarget
    data class Session(val sessionId: Long) : WorkTapTarget
}

/**
 * The live session a task's row opens: an active link's session, the
 * primary one first, else the first the hub lists. Null when none is live
 * (an ended link has no session id).
 */
fun liveSessionOf(task: WorkTask): Long? {
    val live = task.sessions.filter { it.state == LinkState.Active && it.sessionId != null }
    return (live.firstOrNull { it.primary } ?: live.firstOrNull())?.sessionId
}

/** What a tap on [task]'s row opens under [choice]. */
fun workTapTarget(task: WorkTask, choice: WorkTapChoice): WorkTapTarget {
    if (choice == WorkTapChoice.SESSION) liveSessionOf(task)?.let { return WorkTapTarget.Session(it) }
    return WorkTapTarget.Ticket(task.taskId)
}

/** Open [task]'s row the way [choice] says, through the screen's own handlers. */
internal fun MyWorkHandlers.openRow(task: WorkTask, choice: WorkTapChoice) {
    when (val t = workTapTarget(task, choice)) {
        is WorkTapTarget.Ticket -> onOpenTask(t.taskId)
        is WorkTapTarget.Session -> onOpenSession(t.sessionId)
    }
}
