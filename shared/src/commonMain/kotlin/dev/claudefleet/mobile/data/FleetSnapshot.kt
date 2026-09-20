package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
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
)

/**
 * The event kinds the snapshot acts on, and therefore what the stream asks the
 * hub for with `?kinds=`.
 *
 * The hub publishes ten (`EVENT_KINDS` in `crates/fleet-core/src/events.rs`);
 * a phone draws three of them. `HubEventStream`'s default carries the same list
 * and a test on each side pins it, so the filter and the applier cannot drift
 * apart — a filter that is too narrow leaves rows quietly stale, and one that
 * is too wide spends a phone's radio on frames that get dropped.
 */
val SNAPSHOT_EVENT_KINDS: List<String> = listOf("session", "host", "project")

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
 */
fun HubEvent.Row.sessionId(): Long? = if (name.startsWith("session:")) payload.number("id") else null

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
            merge = { existing -> incoming.copy(isController = existing.isController) },
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

private fun <T> decode(serializer: DeserializationStrategy<T>, payload: JsonElement): T? = try {
    json.decodeFromJsonElement(serializer, payload)
} catch (_: Exception) {
    null
}

private fun JsonElement.number(key: String): Long? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.let { runCatching { it.long }.getOrNull() }

private fun JsonElement.text(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
