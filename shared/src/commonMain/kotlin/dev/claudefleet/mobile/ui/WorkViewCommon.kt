package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.GroupRef
import dev.claudefleet.mobile.model.GroupSource
import dev.claudefleet.mobile.model.LinkState
import dev.claudefleet.mobile.model.OrgSource
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.model.WorkTaskLink
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * What the Work view's screens (My work, a task, a session's Tasks, the
 * Review sheet) share: how a refused write is said, how "offline, as of"
 * reads, and the words for where a value came from. One place, so the four
 * screens say the same thing the same way.
 */

/** The hub's refusal of a write whose version (or primary, or impact) changed under it. */
internal const val E_CONFLICT = "E_CONFLICT"

/** A write another device beat: shown with the hub's sentence and *Reload*, never retried by itself. */
internal fun Throwable.isConflict(): Boolean = this is HubError.Tool && code == E_CONFLICT

/**
 * [friendlyWork] for a Work-view write. `E_CONFLICT` is its own case: the
 * value changed on another device, the hub's message names the current one,
 * and the screen offers *Reload* beside it (see each state's `conflict`).
 * A refusal (`E_FORBIDDEN`) is the hub's own sentence, as everywhere.
 */
internal fun friendlyWorkWrite(t: Throwable): Friendly =
    if (t.isConflict()) {
        Friendly("Changed on another device", (t as HubError.Tool).message, isError = true, details = explain(t))
    } else {
        friendlyWork(t)
    }

internal fun ConnectionStatus.isConnected(): Boolean = this is ConnectionStatus.Connected

/**
 * What a Work-view write says when it is tapped while the hub is not
 * connected: refused on the spot, visibly. Nothing is queued to be sent
 * later, and nothing is drawn as saved.
 */
internal val OFFLINE_WRITE = Friendly(
    "Not saved — offline",
    "The hub is not connected, so nothing was sent. Nothing is queued: try again once it is back.",
    isError = true,
)

// ---- staying current ----

/**
 * The part of a session row the Work view draws from: its primary work, its
 * top guess, its org, [SessionRow.workRev] (every live link, secondaries
 * included) and whether it is alive. A `session:updated` that moves none of
 * these — status, activity, context, cost: the churn of a working session —
 * is no reason to re-read a task or a tree.
 */
internal data class WorkSignature(
    val work: WorkSummary?,
    val workSuggested: WorkSummary?,
    val orgId: Long?,
    val workRev: Long,
    val alive: Boolean,
)

internal fun SessionRow.workSignature(): WorkSignature =
    WorkSignature(work, workSuggested, orgId, workRev, alive = lostAt == null && status != "ghost")

/**
 * A tick each time the work signature of the rows [which] keeps changes — a
 * row added or gone counts — and never for anything else a row carries.
 * Read from [FleetState.sessions] rather than [FleetState.sessionChanges],
 * so a resync that re-lists everything ticks only when work actually moved.
 * The first value is what is already showing, not a change.
 */
internal fun FleetState.workSignatureChanges(which: (SessionRow) -> Boolean = { true }): Flow<Unit> =
    sessions
        .map { rows -> rows.filter(which).associate { it.id to it.workSignature() } }
        .distinctUntilChanged()
        .drop(1)
        .map { }

/**
 * At most one tick per [periodMs], leading and trailing: the first change
 * re-reads at once, changes during the following [periodMs] fold into one
 * more re-read at its end, and so on. Unlike a debounce, a steady stream of
 * changes cannot hold a re-read back for ever — the screen is at most
 * [periodMs] behind the hub, however busy the fleet is.
 */
internal fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<Unit> = channelFlow {
    val ticks = Channel<Unit>(Channel.CONFLATED)
    launch {
        collect { ticks.send(Unit) }
        ticks.close()
    }
    for (tick in ticks) {
        send(Unit)
        delay(periodMs)
    }
}

/**
 * What *Place in group…* offers: groups a person or a rule made — `label:`
 * groups — never one derived from a tracker's container, a repository or a
 * key prefix. Those are where a task sits by itself; offering one as a
 * placement would pin the task to a copy of it by hand. Plus whatever the
 * person types (the sheet's own field).
 */
internal fun placeableGroups(groups: List<GroupRef>): List<String> =
    groups.filter { it.isPlaceable }
        .map { it.label.ifBlank { it.id.removePrefix(LABEL_GROUP_PREFIX) } }
        .filter { it.isNotBlank() }
        .distinct()

/** A `label:` group a person or a rule made: what a placement may name. */
internal val GroupRef.isPlaceable: Boolean
    get() = id.startsWith(LABEL_GROUP_PREFIX) && (source == GroupSource.Manual || source == GroupSource.Rule)

/** The id prefix of a group a person's placement or a rule named: `label:<label>`. */
internal const val LABEL_GROUP_PREFIX = "label:"

/** `10:42` — the local wall clock at [epochSeconds], without a date library. */
internal fun clockLabel(epochSeconds: Long, utcOffsetSeconds: Int): String {
    val local = epochSeconds + utcOffsetSeconds
    val ofDay = ((local % DAY) + DAY) % DAY
    val h = (ofDay / 3600).toInt()
    val m = ((ofDay % 3600) / 60).toInt()
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
}

/** What a Work screen says over a picture it can no longer refresh. */
internal fun staleLine(asOf: Long, utcOffsetSeconds: Int): String = "Offline · as of ${clockLabel(asOf, utcOffsetSeconds)}"

private const val DAY = 86_400L

/** A link's state in words: never "active" for one that has ended. */
fun linkStateWords(link: WorkTaskLink): String = when (link.state) {
    LinkState.Active -> if (link.primary) "primary" else "secondary"
    LinkState.Suggested -> "suggested"
    LinkState.Ended -> "ended"
    LinkState.Rejected -> "not this"
    LinkState.Unknown -> "linked"
}

/** Where a task's org comes from, in words. */
fun orgSourceWords(source: OrgSource): String = when (source) {
    OrgSource.Tracker -> "from its tracker"
    OrgSource.Item -> "set on the task"
    OrgSource.Sessions -> "from its sessions"
    OrgSource.None -> "no organisation"
    OrgSource.Unknown -> "from the hub"
}

/** Where a task's group comes from, in words — the tracker's own is labelled as the tracker's. */
fun groupSourceWords(group: GroupRef): String = when (group.source) {
    GroupSource.Manual -> "placed by a person"
    GroupSource.Rule -> "placed by a rule"
    GroupSource.Tracker -> "from the tracker" + (group.trackerValue?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
    GroupSource.Repo -> "from its repository"
    GroupSource.Key -> "from its key"
    GroupSource.None -> "nothing places it"
    GroupSource.Unknown -> "from the hub"
}

/**
 * Whether a person's placement is what is showing — the only group a
 * *Clear placement* can remove. Everything else is derived and stays.
 */
val GroupRef.isPlacedByHand: Boolean get() = source == GroupSource.Manual
