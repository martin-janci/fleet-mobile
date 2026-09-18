package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubEvent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Applying one frame to the snapshot: pure, no coroutines, no transport.
 *
 * The payloads are what `RowChange::payload()` produces
 * (`crates/fleet-core/src/events.rs`): `session:created` / `session:updated`
 * serialize the whole store row, `session:killed` is `{"id": n}`,
 * `host:added` / `host:probed` the whole host row, `host:removed` is
 * `{"alias": "..."}`.
 */
private val parse = Json { ignoreUnknownKeys = true; explicitNulls = false }

private fun row(name: String, json: String) = HubEvent.Row(name, parse.parseToJsonElement(json))

/**
 * A session row exactly as the store serializes it into an event: every column,
 * nulls included (the event path is plain serde, not `ok_json_compact`), and
 * **no `is_controller`** — that flag is added by `list_sessions`, not carried by
 * the row.
 */
private fun sessionPayload(
    id: Long,
    tmux: String = "sess-$id",
    status: String = "running",
    claudeStatus: String? = "working",
    activity: String? = "editing",
): String = """
{
  "id": $id,
  "tmux_name": "$tmux",
  "host_alias": "box",
  "project_id": 3,
  "worktree_id": null,
  "created_at": 1758100000,
  "last_activity_at": 1758153600,
  "status": "$status",
  "notes": null,
  "account_uuid": null,
  "kind": "work",
  "reviews_session_id": null,
  "worktree_key": null,
  "lost_at": null,
  "claude_session_id": null,
  "claude_status": ${claudeStatus?.let { "\"$it\"" } ?: "null"},
  "effort_level": null,
  "pr_url": null,
  "current_activity": ${activity?.let { "\"$it\"" } ?: "null"},
  "context_pct": 41.5,
  "stuck_kind": null,
  "friendly_name": null,
  "safe_kill_state": null,
  "safe_kill_nonce": null,
  "safe_kill_detail": null,
  "safe_kill_requested_at": null,
  "idle_since": null,
  "stuck_since": null,
  "last_playbook_at": null,
  "last_prompt": null,
  "started_at": null,
  "last_turn_at": null,
  "ci_status": null,
  "turn_seq": 9,
  "last_stop_at": null,
  "parent_session_id": null,
  "tags": ["mobile"],
  "usage_input_tokens": 120,
  "usage_cost_usd": 0.31
}
"""

private fun hostPayload(alias: String, reachable: Boolean = true): String = """
{
  "alias": "$alias",
  "ssh_alias": "$alias.local",
  "reachable": $reachable,
  "claude_version": "2.0.1",
  "tmux_version": "3.4",
  "hidden": false,
  "last_pinged_at": 1758153600,
  "account_uuid": null,
  "provisioned": true,
  "transport": "ssh"
}
"""

class FleetSnapshotTest {

    private val two = FleetSnapshot(
        sessions = listOf(SessionRow(id = 1, tmuxName = "one"), SessionRow(id = 2, tmuxName = "two")),
        hosts = listOf(HostRow(alias = "box"), HostRow(alias = "trn")),
    )

    @Test
    fun a_session_updated_frame_replaces_the_row_with_that_id() {
        val after = two.applying(row("session:updated", sessionPayload(id = 2, tmux = "renamed")))

        assertEquals(2, after.sessions.size)
        assertEquals("renamed", after.sessions[1].tmuxName)
        assertEquals("editing", after.sessions[1].currentActivity)
        assertEquals(listOf("mobile"), after.sessions[1].tags)
        assertEquals("one", after.sessions[0].tmuxName, "the other row is untouched")
    }

    /** A replacement keeps its place, or every update would reshuffle the list. */
    @Test
    fun a_replaced_row_stays_where_it_was() {
        val after = two.applying(row("session:updated", sessionPayload(id = 1)))

        assertEquals(listOf(1L, 2L), after.sessions.map { it.id })
    }

    /** A session that appeared while the app was away still has to land. */
    @Test
    fun an_update_for_an_id_the_snapshot_never_saw_is_added() {
        val after = two.applying(row("session:updated", sessionPayload(id = 9)))

        assertEquals(listOf(1L, 2L, 9L), after.sessions.map { it.id })
    }

    @Test
    fun a_session_created_frame_adds_the_row() {
        val after = two.applying(row("session:created", sessionPayload(id = 3)))

        assertEquals(listOf(1L, 2L, 3L), after.sessions.map { it.id })
    }

    @Test
    fun a_session_killed_frame_removes_the_row() {
        val after = two.applying(row("session:killed", """{"id":1}"""))

        assertEquals(listOf(2L), after.sessions.map { it.id })
    }

    @Test
    fun a_kill_for_a_row_that_is_already_gone_changes_nothing() {
        assertSame(two, two.applying(row("session:killed", """{"id":404}""")))
    }

    /**
     * `is_controller` is flattened onto each row by `list_sessions` and is **not
     * a column**, so no event payload can carry it. Replacing a row wholesale
     * would therefore quietly clear it on the first update.
     */
    @Test
    fun an_update_keeps_the_controller_flag_the_event_cannot_carry() {
        val known = FleetSnapshot(sessions = listOf(SessionRow(id = 1, isController = true)))

        val after = known.applying(row("session:updated", sessionPayload(id = 1)))

        assertTrue(after.sessions.single().isController, "the flag must survive an update")
    }

    @Test
    fun a_host_probed_frame_replaces_the_host_with_that_alias() {
        val after = two.applying(row("host:probed", hostPayload("trn", reachable = true)))

        assertEquals(listOf("box", "trn"), after.hosts.map { it.alias })
        assertTrue(after.hosts[1].reachable)
        assertEquals("2.0.1", after.hosts[1].claudeVersion)
    }

    @Test
    fun a_host_added_frame_adds_it() {
        val after = two.applying(row("host:added", hostPayload("mac")))

        assertEquals(listOf("box", "trn", "mac"), after.hosts.map { it.alias })
    }

    @Test
    fun a_host_removed_frame_removes_it_by_alias() {
        val after = two.applying(row("host:removed", """{"alias":"box"}"""))

        assertEquals(listOf("trn"), after.hosts.map { it.alias })
    }

    /** The hub streams ten kinds; the phone's snapshot holds two of them. */
    @Test
    fun an_event_name_the_snapshot_knows_nothing_about_is_ignored() {
        for (name in listOf("task:updated", "account_usage:updated", "sync:progress", "project:updated")) {
            assertSame(two, two.applying(row(name, """{"id":1}""")), "$name must not touch the snapshot")
        }
    }

    /** A hub that grows a variant must not corrupt what is already on screen. */
    @Test
    fun a_payload_that_does_not_fit_the_model_is_ignored_not_thrown() {
        assertSame(two, two.applying(row("session:updated", """{"id":"not-a-number"}""")))
        assertSame(two, two.applying(row("session:killed", """{"no-id-here":true}""")))
        assertSame(two, two.applying(row("host:removed", """{}""")))
    }

    /**
     * What the stream asks the hub for (`?kinds=`) has to be what the snapshot
     * applies. Subscribe to too little and rows go stale in silence; subscribe
     * to too much and the phone pays for frames it drops.
     */
    @Test
    fun the_subscribed_kinds_are_exactly_the_ones_the_snapshot_acts_on() {
        assertEquals(listOf("session", "host"), SNAPSHOT_EVENT_KINDS)

        val empty = FleetSnapshot()
        val acted = listOf(
            "session" to row("session:created", sessionPayload(id = 1)),
            "host" to row("host:added", hostPayload("box")),
        )
        assertEquals(SNAPSHOT_EVENT_KINDS, acted.map { it.first })
        for ((kind, frame) in acted) {
            assertTrue(empty.applying(frame) !== empty, "$kind is subscribed but does nothing")
        }
    }
}
