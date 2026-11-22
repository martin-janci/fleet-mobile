package dev.claudefleet.mobile.model

import kotlinx.serialization.Serializable

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

/**
 * A thread Control started or was handed, as the hub's journal lists it
 * (`control_journal { read }`'s `follows`): whether Control still follows it
 * (`state`: following, quiet, merged, done, killed) and whether it is muted.
 */
@Serializable
data class JournalFollow(
    /** `session` | `mission`. */
    val kind: String = "",
    val id: Long = 0,
    val state: String = "",
    val name: String = "",
    val muted: Boolean = false,
)

/** Whether the hub still follows a thread, and whether it is muted: the desktop's `FollowMark`. */
data class FollowMark(val state: String, val muted: Boolean, val name: String? = null)

/** A journal read's threads by key (`s:<id>`, `m:<id>`): the desktop's `followMarks`. */
fun followMarks(follows: List<JournalFollow>): Map<String, FollowMark> =
    follows.associate { f -> "${if (f.kind == "session") "s" else "m"}:${f.id}" to FollowMark(f.state, f.muted, f.name.ifBlank { null }) }

/** Control no longer follows the thread (it went quiet, its PR merged, it ended); a muted or unknown one still counts as followed. */
private fun unfollowed(mark: FollowMark?): Boolean = mark != null && !mark.muted && mark.state != "following"

/**
 * A session's group. A counted reason ([SessionRow.needsAttention]: Action
 * required, Failed, Blocked) puts it in Needs you, unless Control no longer
 * follows the thread: then only a session asking something right now
 * ([SessionRow.actionRequired]) stays there, and a failure or a block goes by
 * what the session is doing (the desktop's `sessionGroup`, review w04-1).
 */
private fun sessionGroup(s: SessionRow, unfollowed: Boolean, since: Long): ThreadGroup = when {
    s.needsAttention && (!unfollowed || s.actionRequired) -> ThreadGroup.NEEDS
    s.claudeStatus == "working" -> ThreadGroup.WORKING
    (s.lastStopAt ?: 0) >= since || (s.claudeStatus == "completed" && (s.lastActivityAt ?: 0) >= since) -> ThreadGroup.DONE
    else -> ThreadGroup.EARLIER
}

/**
 * What a thread's line reads beyond its row (review w04-5): account names,
 * which window an account's limit is in, and the hosts' signed-out logins.
 * Empty, an account reads as its uuid's head and a limit as weekly.
 */
data class ThreadFacts(
    val accountNames: Map<String, String> = emptyMap(),
    val limits: Map<String, AccountLimit> = emptyMap(),
    val hosts: List<HostRow> = emptyList(),
) {
    internal fun accountName(uuid: String?): String =
        uuid?.takeIf { it.isNotBlank() }?.let { accountNames[it] ?: it.take(8) } ?: "its account"

    internal fun signedOut(host: String, profile: String?): Boolean =
        hosts.any { it.alias == host && it.isSignedOut(profile) }
}

/** A stuck kind in words: the desktop's `stuckKindLabel`; null for an unknown one. */
fun stuckKindLabel(kind: String?): String? = when (kind) {
    "auth_menu" -> "auth menu"
    "reconnect" -> "reconnecting"
    "trust_prompt" -> "trust prompt"
    "oom" -> "out of memory"
    "press_enter" -> "press Enter"
    else -> null
}

/**
 * The line of a Blocked thread, as the desktop's `blockedLine` words it:
 * "Paused · weekly limit on tech.silvester". Null for any other reason.
 */
private fun blockedLine(s: SessionRow, facts: ThreadFacts): String? = when (s.attentionReason) {
    "host_down" -> "Needs you · blocked on ${s.hostAlias}, which is down"
    "account_limit" -> {
        val w = if (s.accountUuid?.let { facts.limits[it] }?.weekly == false) "5-hour" else "weekly"
        "Paused · $w limit on ${facts.accountName(s.accountUuid)}"
    }
    // The login on this host, when fleet saw it signed out there: the same
    // account may be signed in elsewhere.
    "no_credentials" -> if (facts.signedOut(s.hostAlias, s.claudeProfile)) {
        "Needs you · signed out on ${s.hostAlias}${s.claudeProfile?.let { " ($it)" }.orEmpty()}"
    } else {
        "Needs you · blocked on ${facts.accountName(s.accountUuid)}, which is signed out"
    }
    else -> null
}

/** Why a counted reason counts, in words: the desktop's `reasonWords`. */
private fun reasonWords(s: SessionRow): String? = when (s.attentionReason) {
    "waiting" -> "Needs you · waiting for your answer"
    "stuck" -> stuckKindLabel(s.stuckKind)?.let { "Needs you · stuck on $it" } ?: "Needs you · stuck"
    "stop_failed", "failed" -> "Failed · last turn failed"
    "context_full" -> "Needs you · context almost full"
    "stale_working" -> "Needs you · says working, no news"
    "ci_failing" -> "Failed · checks failing"
    else -> null
}

private fun sessionLine(s: SessionRow, facts: ThreadFacts): String {
    // A row in Needs you says why, in the desktop's words, not only its status.
    if (s.needsAttention) {
        blockedLine(s, facts)?.let { return it }
        val why = reasonWords(s) ?: s.attentionReason?.let(::reasonLabel)
        if (why != null) return listOfNotNull(why, s.hostAlias.ifBlank { null }).joinToString(" · ")
    }
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
 * thread of itself. [follows] is the hub's journal ([followMarks]): every
 * thread Control started or was handed, past the newest receipts, and which of
 * them it no longer follows; null from an older hub or a device the journal
 * is not for. [facts] words a Blocked thread's line.
 */
fun buildThreads(
    rows: List<SessionRow>,
    handoffs: List<ControlHandoff>,
    missions: List<Mission>,
    operatorId: Long?,
    since: Long,
    follows: Map<String, FollowMark>? = null,
    facts: ThreadFacts = ThreadFacts(),
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
    // The hub's follows: every thread it knows, past the newest receipts.
    val missionIds = LinkedHashSet<Long>(missionReceipts.keys)
    for (key in follows?.keys.orEmpty()) {
        val id = key.drop(2).toLongOrNull() ?: continue
        if (key.startsWith("s:") && id != operatorId && id in byId) sessionIds += id
        else if (key.startsWith("m:")) missionIds += id
    }
    val out = ArrayList<ControlThread>()
    for (id in sessionIds) {
        val row = byId.getValue(id)
        // A mission's worker belongs to the mission's thread, not its own.
        if (row.origin == "mission" && row.originRef?.toLongOrNull()?.let { it in missionIds } == true) continue
        out += ControlThread(
            key = "s:$id",
            id = id,
            mission = false,
            group = sessionGroup(row, unfollowed(follows?.get("s:$id")), since),
            title = row.displayName,
            line = sessionLine(row, facts),
            at = maxOf(row.lastActivityAt ?: 0, handedAt[id] ?: 0, row.createdAt ?: 0),
        )
    }
    val missionById = missions.associateBy { it.id }
    for (id in missionIds) {
        val h = missionReceipts[id]
        val m = missionById[id]
        val state = m?.state ?: h?.missionState ?: "draft"
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
            group = missionGroup(state, wait != null, maxOf(m?.updatedAt ?: 0, h?.at ?: 0), since),
            title = m?.name?.ifBlank { null } ?: h?.missionName ?: follows?.get("m:$id")?.name ?: "Mission $id",
            line = line,
            at = maxOf(m?.updatedAt ?: 0, h?.at ?: 0),
        )
    }
    return out.sortedByDescending { it.at }
}

/** The list by group, in the room's order, empty groups left out. */
fun groupThreads(list: List<ControlThread>): List<Pair<ThreadGroup, List<ControlThread>>> =
    ThreadGroup.entries.mapNotNull { g -> list.filter { it.group == g }.takeIf { it.isNotEmpty() }?.let { g to it } }
