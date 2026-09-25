package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.net.HubEvent
import dev.claudefleet.mobile.net.json
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

/** What the app knows about the fleet at one moment. */
data class FleetSnapshot(
    val sessions: List<SessionRow> = emptyList(),
    val hosts: List<HostRow> = emptyList(),
    /** Not drawn on their own: what names the groups the session list is cut into. */
    val projects: List<ProjectRow> = emptyList(),
    /**
     * Tracker tickets the app has been shown — by `work { tickets }` or
     * `lookup` — kept current by `work:item` frames. A cache, not a view:
     * which ones a sheet lists is the view's answer; this only makes what it
     * lists fresh.
     */
    val tickets: List<Ticket> = emptyList(),
)

/**
 * The event kinds the snapshot acts on, and therefore what the stream asks the
 * hub for with `?kinds=`.
 *
 * The hub publishes a dozen (`EVENT_KINDS` in `crates/fleet-core/src/events.rs`);
 * a phone draws four of them. `work` is harmless to ask an older hub for — the
 * route ignores kinds it does not know — and a session's primary work rides
 * `session:updated`, so only the ticket cache needs it. `HubEventStream`'s default carries the same list
 * and a test on each side pins it, so the filter and the applier cannot drift
 * apart — a filter that is too narrow leaves rows quietly stale, and one that
 * is too wide spends a phone's radio on frames that get dropped.
 */
val SNAPSHOT_EVENT_KINDS: List<String> = listOf("session", "host", "project", "work")

/**
 * The payload keys the snapshot decodes, and therefore what the stream asks
 * the hub for with `?fields=`.
 *
 * A hub session row is about 1.2 KB and this app reads roughly a third of it;
 * the rest is decoded and dropped, around twelve hundred times an hour. The
 * hub projects a frame's payload to these names before writing it.
 *
 * **Derived, never written down.** The list is the union of what the four row
 * serializers declare, so it cannot drift from what `applying` decodes the way
 * a hand-kept literal would. The union matters because `?fields=` is one list
 * for every frame on the connection: a session-shaped list would project a
 * `host:probed` payload down to nothing, and the failure would be a host row
 * that quietly stopped updating — no error, no log line, just a stale screen.
 *
 * The removal frames (`{"id": n}`, `{"alias": "…"}`) need no entry of their
 * own: both keys are already fields of the rows they remove. `FleetSnapshotTest`
 * pins that by filtering a realistic payload of every event name through this
 * set and asserting the snapshot still changes.
 */
val SNAPSHOT_PAYLOAD_FIELDS: List<String> =
    listOf(
        SessionRow.serializer().descriptor,
        HostRow.serializer().descriptor,
        ProjectRow.serializer().descriptor,
        Ticket.serializer().descriptor,
    )
        .flatMap { d -> (0 until d.elementsCount).map { d.getElementName(it) } }
        .distinct()
        .sorted()

/**
 * Apply one row event, returning the snapshot it produces.
 *
 * Pure: no coroutines, no transport, no clock. Returns *this very instance*
 * when the event changes nothing, so a caller can tell a no-op from a change
 * without comparing lists.
 *
 * The payloads are what `RowChange::payload()` builds — the whole store row for
 * a create/update/probe, and `{"id": n}` / `{"alias": "…"}` for a removal.
 * Anything that does not fit is ignored rather than thrown: a hub that grows a
 * variant must not corrupt what is already on screen, and a live stream is not
 * worth tearing down over one bad frame.
 */
fun FleetSnapshot.applying(event: HubEvent.Row): FleetSnapshot = when (event.name) {
    "session:created", "session:updated" -> upsertSession(event.payload)
    "session:killed" -> removeSession(event.payload)
    "host:added", "host:probed" -> upsertHost(event.payload)
    "host:removed" -> removeHost(event.payload)
    "project:updated" -> upsertProject(event.payload)
    "work:item" -> upsertTicket(event.payload)
    "work:tracker_removed" -> trackerRemoved(event.payload)
    // `work:tracker` (a tracker's own state) changes nothing drawn here.
    else -> this
}

/**
 * The id of the session a `session:*` row event names, or null for any other
 * kind — `host:probed`, `project:updated` — and for a `session:*` frame whose
 * payload does not carry one.
 *
 * What lets a session screen tell "the hub reported a change for the session
 * I have open" from every other row event on the same stream, without
 * decoding the row into a [dev.claudefleet.mobile.model.SessionRow] first.
 *
 * Two frames on that prefix are not a row, and both were read wrong:
 *
 * - **`session:event`** is a timeline entry, where `id` is the
 *   `session_events` rowid — a number in the millions — and the session is in
 *   `session_id`. Reading `id` there meant the open screen never refreshed on
 *   a real timeline entry, and pushed a nonexistent id into the change flow.
 * - **an `mcp_call` timeline entry** says somebody called a tool, which
 *   includes this app's own reads. Taking it as a change is a loop: a read
 *   produces a frame, the frame triggers a read. Recent hubs no longer
 *   announce read-only calls at all, but an older one does, and one client
 *   spinning against it is not a failure mode worth leaving open.
 */
fun HubEvent.Row.sessionId(): Long? = when {
    !name.startsWith("session:") -> null
    name == "session:event" ->
        if (payload.text("kind") == "mcp_call") null else payload.number("session_id")
    else -> payload.number("id")
}

/** The timeline entry a `session:event` frame carries, or null for any other frame. */
fun HubEvent.Row.timelineFrame(): TimelineFrame? {
    if (name != "session:event") return null
    val id = payload.number("session_id") ?: return null
    val kind = payload.text("kind") ?: return null
    return TimelineFrame(id, kind, payload.text("detail"))
}

/**
 * Replace the row [sameRow] picks out, or append [incoming] when there is none.
 *
 * The three upserts below were the same eleven lines three times, differing
 * only in which field identifies a row — and in the one place where a row needs
 * something carried over from the copy it replaces, which is the whole reason
 * [merge] exists. Writing it once is what makes that exception visible: two
 * callers take the default and one does not, and the one that does not says
 * why on the spot.
 *
 * **Not in the list** is an append rather than a drop: a session created while
 * the app was backgrounded, or one whose `session:created` never arrived, is
 * still a session.
 */
private inline fun <T> List<T>.upserted(
    incoming: T,
    merge: (existing: T) -> T = { incoming },
    sameRow: (T) -> Boolean,
): List<T> {
    val at = indexOfFirst(sameRow)
    if (at < 0) return this + incoming
    return toMutableList().also { it[at] = merge(this[at]) }
}

private fun FleetSnapshot.upsertSession(payload: JsonElement): FleetSnapshot {
    val incoming = decode(SessionRow.serializer(), payload) ?: return this
    return copy(
        sessions = sessions.upserted(
            incoming,
            // `is_controller` is flattened onto each row by `list_sessions` and
            // is not a column, so no event payload can carry it. Taking the
            // incoming row wholesale would silently clear it on the first
            // update after a refresh. This is the only row with anything to
            // carry, which is why the other two take the default.
            //
            // `work` and `work_suggested` are deliberately NOT carried. Unlike
            // `is_controller` they are columns, and every frame carries them
            // when they are set — but the hub strips nulls from each frame
            // (`strip_nulls` in `events.rs`) and skips an empty
            // `work_suggested`, so an absent key is how a cleared link or a
            // decided suggestion arrives. Keeping the old value would leave a
            // chip nobody can clear.
            merge = { existing ->
                incoming.copy(isController = existing.isController)
            },
        ) { it.id == incoming.id },
    )
}

private fun FleetSnapshot.removeSession(payload: JsonElement): FleetSnapshot {
    val id = payload.number("id") ?: return this
    val remaining = sessions.filterNot { it.id == id }
    return if (remaining.size == sessions.size) this else copy(sessions = remaining)
}

private fun FleetSnapshot.upsertHost(payload: JsonElement): FleetSnapshot {
    val incoming = decode(HostRow.serializer(), payload) ?: return this
    return copy(hosts = hosts.upserted(incoming) { it.alias == incoming.alias })
}

private fun FleetSnapshot.removeHost(payload: JsonElement): FleetSnapshot {
    val alias = payload.text("alias")?.takeIf { it.isNotBlank() } ?: return this
    val remaining = hosts.filterNot { it.alias == alias }
    return if (remaining.size == hosts.size) this else copy(hosts = remaining)
}

/**
 * There is no `project:removed`. The hub's stale-rows sweep deletes a project
 * row without an event, so a project only ever leaves this list at the next
 * refetch — which every `ready` and every `lagged` frame forces anyway. A
 * lingering row costs a heading nothing points at, and nothing more.
 */
private fun FleetSnapshot.upsertProject(payload: JsonElement): FleetSnapshot {
    val incoming = decode(ProjectRow.serializer(), payload) ?: return this
    return copy(projects = projects.upserted(incoming) { it.id == incoming.id })
}

/**
 * A `work:item` frame carries the hub's `WorkItemRow` — no live sessions, which
 * the `tickets` read joins on — so an upsert keeps them from the copy it
 * replaces. A frame for a ticket no sheet has listed yet is cached too: the
 * sheet draws only the ids its view answered, so an extra entry costs a row of
 * memory and saves a stale one the moment that view lists it.
 */
private fun FleetSnapshot.upsertTicket(payload: JsonElement): FleetSnapshot {
    val incoming = decode(Ticket.serializer(), payload) ?: return this
    return copy(
        tickets = tickets.upserted(
            incoming,
            merge = { existing ->
                incoming.copy(
                    liveSessionIds = existing.liveSessionIds,
                    description = incoming.description ?: existing.description,
                    views = incoming.views.ifEmpty { existing.views },
                )
            },
        ) { it.id == incoming.id },
    )
}

/**
 * `{"id": tracker_id}`: the tracker is gone, and every ticket it served is
 * now unavailable (missing is not gone — the hub keeps the items). Marked, not
 * dropped, so a session's chip can still say which ticket it was.
 */
private fun FleetSnapshot.trackerRemoved(payload: JsonElement): FleetSnapshot {
    val trackerId = payload.number("id") ?: return this
    if (tickets.none { it.trackerId == trackerId && !it.unavailable }) return this
    return copy(
        tickets = tickets.map {
            if (it.trackerId == trackerId && !it.unavailable) {
                it.copy(unavailableReason = TRACKER_REMOVED)
            } else {
                it
            }
        },
    )
}

/** The hub's own `unavailable_reason` for a ticket whose tracker was removed. */
internal const val TRACKER_REMOVED = "tracker_removed"

private fun <T> decode(serializer: DeserializationStrategy<T>, payload: JsonElement): T? = try {
    json.decodeFromJsonElement(serializer, payload)
} catch (_: Exception) {
    null
}

private fun JsonElement.number(key: String): Long? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.let { runCatching { it.long }.getOrNull() }

private fun JsonElement.text(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
