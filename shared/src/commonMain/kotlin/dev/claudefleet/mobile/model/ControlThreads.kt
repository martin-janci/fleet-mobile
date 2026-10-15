package dev.claudefleet.mobile.model

/*
 * Control as a Thread room on the phone (mission control hardening,
 * claude-fleet #914): the threads Control handed out — the sessions its
 * agent started (`origin = operator`), the sessions and missions its handoff
 * receipts name — grouped Needs you / Working / Done today / Earlier. A port
 * of the desktop's `src/lib/control_threads.ts`. Pure: it reads the live
 * rows, the receipts and the missions, never the transcript's text.
 */

enum class ThreadGroup(val label: String) {
    NEEDS("Needs you"),
    WORKING("Working"),
    DONE("Done today"),
    EARLIER("Earlier"),
}

data class ControlThread(
    /** `s:<id>` or `m:<id>`, unique across the list. */
    val key: String,
    val id: Long,
    /** True for a mission, false for a session. */
    val mission: Boolean,
    val group: ThreadGroup,
    val title: String,
    /** The second line: what it is doing or waiting on. */
    val line: String,
    /** Its last change, unix seconds: the sort. */
    val at: Long,
)

/** Why a mission waits on a person, in words (`MissionWait.reason`); the desktop's `WAIT_WORDS`. */
fun threadWaitWords(reason: String): String = when (reason) {
    "question" -> "Asks a question"
    "sign_grant" -> "Grant needs signing"
    "confirm" -> "A step waits for your OK"
    else -> "Waits on you"
}

private fun sessionGroup(s: SessionRow, since: Long): ThreadGroup = when {
    s.needsAttention -> ThreadGroup.NEEDS
    s.claudeStatus == "working" -> ThreadGroup.WORKING
    (s.lastStopAt ?: 0) >= since || (s.claudeStatus == "completed" && (s.lastActivityAt ?: 0) >= since) -> ThreadGroup.DONE
    else -> ThreadGroup.EARLIER
}

private fun sessionLine(s: SessionRow): String {
    val status = when (s.claudeStatus) {
        "working" -> "Working"
        "blocked" -> "Needs you"
        "completed" -> "Done"
        "failed" -> "Failed"
        "idle" -> "Idle"
        "stopped" -> "Stopped"
        else -> null
    }
    return listOfNotNull(lostLine(s) ?: status, s.hostAlias.ifBlank { null }).joinToString(" · ")
}

private fun missionGroup(state: String, waiting: Boolean, updatedAt: Long, since: Long): ThreadGroup = when {
    waiting -> ThreadGroup.NEEDS
    state == "active" || state == "running" || state == "in_progress" -> ThreadGroup.WORKING
    state == "failed" -> ThreadGroup.NEEDS
    (state == "completed" || state == "done" || state == "cancelled") && updatedAt >= since -> ThreadGroup.DONE
    else -> ThreadGroup.EARLIER
}

/**
 * Every thread Control handed out, newest first. [since] is local midnight,
 * what "Done today" means; [operatorId] is Control's own session, never a
 * thread of itself.
 */
fun buildThreads(
    rows: List<SessionRow>,
    handoffs: List<ControlHandoff>,
    missions: List<Mission>,
    operatorId: Long?,
    since: Long,
): List<ControlThread> {
    val byId = rows.associateBy { it.id }
    val sessionIds = LinkedHashSet<Long>()
    for (s in rows) if (s.origin == "operator" && s.id != operatorId) sessionIds += s.id
    val handedAt = HashMap<Long, Long>()
    val missionReceipts = LinkedHashMap<Long, ControlHandoff>()
    for (h in handoffs) {
        val sid = h.sessionId
        if (sid != null && sid != operatorId && (h.kind == "session" || h.kind == "task") && sid in byId) {
            sessionIds += sid
            handedAt[sid] = maxOf(handedAt[sid] ?: 0, h.at)
        }
        val mid = h.missionId
        if (mid != null) {
            val prev = missionReceipts[mid]
            if (prev == null || h.at > prev.at) missionReceipts[mid] = h
        }
    }
    val out = ArrayList<ControlThread>()
    for (id in sessionIds) {
        val row = byId.getValue(id)
        // A mission's worker belongs to the mission's thread, not its own.
        if (row.origin == "mission" && row.originRef?.toLongOrNull()?.let { it in missionReceipts } == true) continue
        out += ControlThread(
            key = "s:$id",
            id = id,
            mission = false,
            group = sessionGroup(row, since),
            title = row.displayName,
            line = sessionLine(row),
            at = maxOf(row.lastActivityAt ?: 0, handedAt[id] ?: 0, row.createdAt ?: 0),
        )
    }
    val missionById = missions.associateBy { it.id }
    for ((id, h) in missionReceipts) {
        val m = missionById[id]
        val state = m?.state ?: h.missionState ?: "draft"
        val wait = m?.waitingOn
        val line = when {
            wait != null -> threadWaitWords(wait.reason)
            m != null && m.total > 0 -> "Mission · ${m.done} of ${m.total} done"
            else -> "Mission"
        }
        out += ControlThread(
            key = "m:$id",
            id = id,
            mission = true,
            group = missionGroup(state, wait != null, maxOf(m?.updatedAt ?: 0, h.at), since),
            title = m?.name?.ifBlank { null } ?: h.missionName ?: "Mission $id",
            line = line,
            at = maxOf(m?.updatedAt ?: 0, h.at),
        )
    }
    return out.sortedByDescending { it.at }
}

/** The list by group, in the room's order, empty groups left out. */
fun groupThreads(list: List<ControlThread>): List<Pair<ThreadGroup, List<ControlThread>>> =
    ThreadGroup.entries.mapNotNull { g -> list.filter { it.group == g }.takeIf { it.isNotEmpty() }?.let { g to it } }

/** "2 need you · 3 working": the strip's words, the groups that matter now; null with no threads. */
fun threadsSummary(list: List<ControlThread>): String? {
    if (list.isEmpty()) return null
    val needs = list.count { it.group == ThreadGroup.NEEDS }
    val working = list.count { it.group == ThreadGroup.WORKING }
    val done = list.count { it.group == ThreadGroup.DONE }
    val parts = listOfNotNull(
        needs.takeIf { it > 0 }?.let { "$it need${if (it == 1) "s" else ""} you" },
        working.takeIf { it > 0 }?.let { "$it working" },
        done.takeIf { it > 0 }?.let { "$it done today" },
    )
    return if (parts.isEmpty()) "${list.size} earlier" else parts.joinToString(" · ")
}
