package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.STORE_ROW_WITHOUT_WORK
import dev.claudefleet.mobile.model.STORE_ROW_WITH_WORK
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.StatusCategory
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.net.HubEvent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        hosts = listOf(HostRow(alias = "box"), HostRow(alias = "pine")),
    )

    // ---- a malformed frame must not change what is on the screen ----
    //
    // `applying`'s own KDoc promises exactly this: "Anything that does not fit
    // is ignored rather than thrown: a hub that grows a variant must not
    // corrupt what is already on screen, and a live stream is not worth
    // tearing down over one bad frame." Two of the guards keeping that promise
    // had no test, so a sweep could delete them and nothing objected.

    /**
     * `box`, one with a blank alias, and one literally called `123`.
     *
     * The last is what makes the type guard testable at all. Without a host
     * whose alias equals the stringified number, a frame carrying `"alias":
     * 123` removes nothing either way — the filter simply matches nothing —
     * and an assertion that the snapshot is unchanged passes whether the guard
     * is there or not. It took the sweep to notice that; the first version of
     * this test was green against the mutation it was written to catch.
     */
    private val twoHosts = FleetSnapshot(
        hosts = listOf(HostRow(alias = "box"), HostRow(alias = ""), HostRow(alias = "123")),
    )

    /**
     * A removal naming no host removes no host.
     *
     * The blank check is not decoration. `HostRow.alias` defaults to the empty
     * string, so a row that arrived without one sits in the list under a blank
     * alias — and a `host:removed` frame whose alias is blank or whitespace
     * would match it and take it off the screen. The frame names nothing, so
     * it must do nothing.
     */
    @Test
    fun a_host_removal_with_a_blank_alias_removes_nothing() {
        for (alias in listOf("\"\"", "\"   \"")) {
            val after = twoHosts.applying(row("host:removed", """{"alias": $alias}"""))
            assertSame(twoHosts, after, "a blank alias named no host, so nothing may change")
        }
    }

    /**
     * And neither does one whose alias is not a string.
     *
     * An alias arriving as a number or an object is a frame this app does not
     * understand, and the rule for those is to ignore them. Reading `123` as
     * the alias `"123"` would be a guess, and a guess that deletes a row is
     * the wrong kind of guess.
     *
     * Numeric *ids* are read the other way round on purpose: `{"id": "7"}` is
     * accepted, because a quoted number still names exactly one session and
     * the reading cannot be wrong. Only the guess that could be is refused.
     */
    @Test
    fun a_host_removal_whose_alias_is_not_a_string_removes_nothing() {
        for (alias in listOf("123", "true", "null", """{"name":"box"}""", """["box"]""")) {
            val after = twoHosts.applying(row("host:removed", """{"alias": $alias}"""))

            assertSame(twoHosts, after, "alias $alias is not an alias")
            assertEquals(
                twoHosts.hosts.map { it.alias },
                after.hosts.map { it.alias },
                "the host called \"123\" must survive a frame carrying the number 123",
            )
        }
    }

    /** And the removal that does name a host still works: these are guards, not a wall. */
    @Test
    fun a_host_removal_that_names_a_host_still_removes_it() {
        val after = twoHosts.applying(row("host:removed", """{"alias": "box"}"""))

        assertEquals(listOf("", "123"), after.hosts.map { it.alias }, "only the named host goes")
    }

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
        val after = two.applying(row("host:probed", hostPayload("pine", reachable = true)))

        assertEquals(listOf("box", "pine"), after.hosts.map { it.alias })
        assertTrue(after.hosts[1].reachable)
        assertEquals("2.0.1", after.hosts[1].claudeVersion)
    }

    /**
     * The same frame, for the row that happens to be **first**.
     *
     * Every upsert here was tested only against a row in the middle of a list —
     * `pine` at index 1, session `2` at index 1 — so `indexOfFirst` never
     * returned 0 in any test, and `if (at < 0)` could have been `if (at <= 0)`
     * with the whole suite still green. Found by mutation, not by reading: the
     * two branches differ on exactly one input and no test supplied it.
     *
     * What the mutant did is worth naming, because it is the shape of a real
     * bug rather than a wrong number. Appending instead of replacing leaves
     * **both** copies of the host in the snapshot, so the fleet list grows a
     * duplicate entry every time the first host is probed — and `host:probed`
     * is one of the three kinds this app subscribes to, so it arrives on a
     * timer forever.
     */
    @Test
    fun a_host_probed_frame_replaces_the_first_host_too() {
        val after = two.applying(row("host:probed", hostPayload("box", reachable = true)))

        assertEquals(listOf("box", "pine"), after.hosts.map { it.alias }, "replaced in place")
        assertEquals(2, after.hosts.size, "replaced, not appended")
        assertTrue(after.hosts[0].reachable)
        assertEquals("2.0.1", after.hosts[0].claudeVersion)
    }

    /** The same boundary on the session list, which has the extra carry-over. */
    @Test
    fun a_session_updated_frame_replaces_the_first_row_too() {
        val after = two.applying(row("session:updated", sessionPayload(id = 1, tmux = "renamed")))

        assertEquals(2, after.sessions.size, "replaced, not appended")
        assertEquals(listOf(1L, 2L), after.sessions.map { it.id })
        assertEquals("renamed", after.sessions[0].tmuxName)
        assertEquals("two", after.sessions[1].tmuxName, "the other row is untouched")
    }

    /** And on the project list. */
    @Test
    fun a_project_frame_replaces_the_first_row_too() {
        val before = FleetSnapshot(
            projects = listOf(ProjectRow(id = 1, owner = "a", repo = "one"), ProjectRow(id = 2)),
        )

        val after = before.applying(
            row("project:updated", """{"id":1,"owner":"a","repo":"renamed","last_session_at":9}"""),
        )

        assertEquals(2, after.projects.size, "replaced, not appended")
        assertEquals(listOf(1L, 2L), after.projects.map { it.id })
        assertEquals("renamed", after.projects[0].repo)
    }

    @Test
    fun a_host_added_frame_adds_it() {
        val after = two.applying(row("host:added", hostPayload("mac")))

        assertEquals(listOf("box", "pine", "mac"), after.hosts.map { it.alias })
    }

    @Test
    fun a_host_removed_frame_removes_it_by_alias() {
        val after = two.applying(row("host:removed", """{"alias":"box"}"""))

        assertEquals(listOf("pine"), after.hosts.map { it.alias })
    }

    /**
     * `project:updated` carries the **store** row (`base_path`, `adopted`, no
     * `worktree_count`), where `list_projects` answers the slim summary
     * (`worktree_count`, no `base_path`). Only the fields both shapes carry are
     * modelled, so a frame cannot silently blank a field the list had filled in.
     */
    @Test
    fun a_project_frame_replaces_the_row_by_id() {
        val before = FleetSnapshot(
            projects = listOf(ProjectRow(id = 3, owner = "martin-janci", repo = "old-name")),
        )
        val after = before.applying(
            row(
                "project:updated",
                """{"id":3,"owner":"martin-janci","repo":"claude-fleet",
                   "base_path":"/home/dev/projects","last_session_at":1758153600,"adopted":false}""",
            ),
        )

        assertEquals(listOf("martin-janci/claude-fleet"), after.projects.map { it.label })
        assertEquals(1758153600L, after.projects.single().lastSessionAt)
    }

    @Test
    fun a_project_the_snapshot_has_never_seen_is_added_rather_than_dropped() {
        val after = FleetSnapshot().applying(
            row("project:updated", """{"id":9,"owner":"o","repo":"r","base_path":"/p","adopted":true}"""),
        )

        assertEquals(listOf(9L), after.projects.map { it.id })
    }

    /** The hub streams ten kinds; the phone's snapshot holds three of them. */
    @Test
    fun an_event_name_the_snapshot_knows_nothing_about_is_ignored() {
        for (name in listOf("task:updated", "account_usage:updated", "sync:progress", "worktree:updated")) {
            assertSame(two, two.applying(row(name, """{"id":1}""")), "$name must not touch the snapshot")
        }
    }

    /** A hub that grows a variant must not corrupt what is already on screen. */
    @Test
    fun a_payload_that_does_not_fit_the_model_is_ignored_not_thrown() {
        assertSame(two, two.applying(row("session:updated", """{"id":"not-a-number"}""")))
        assertSame(two, two.applying(row("session:killed", """{"no-id-here":true}""")))
        assertSame(two, two.applying(row("host:removed", """{}""")))
        assertSame(two, two.applying(row("project:updated", """{"owner":"o","repo":"r"}""")))
    }

    /** The id a session screen needs in order to know it is the one that changed. */
    @Test
    fun sessionId_reads_the_id_off_a_session_row_event() {
        assertEquals(2L, row("session:updated", sessionPayload(id = 2)).sessionId())
        assertEquals(1L, row("session:created", sessionPayload(id = 1)).sessionId())
        assertEquals(9L, row("session:killed", """{"id":9}""").sessionId())
    }

    /**
     * On a `session:event` frame `id` is the `session_events` rowid, not the
     * session — a number in the millions. Reading it meant the open screen
     * never refreshed on a real timeline entry and pushed an id that matches
     * nothing into the change flow.
     */
    @Test
    fun sessionId_reads_session_id_on_a_timeline_frame_not_the_row_id() {
        val frame = """{"id":2125930,"session_id":21340,"at":1790027305,"kind":"status_change"}"""
        assertEquals(21340L, row("session:event", frame).sessionId())
    }

    /**
     * An `mcp_call` entry says somebody called a tool — including this app's
     * own reads. Treating it as a change is a loop: a read produces the frame,
     * the frame triggers a read. Current hubs no longer announce read-only
     * calls; an older one does.
     */
    @Test
    fun sessionId_is_null_for_an_mcp_call_timeline_frame() {
        val frame = """{"id":2125930,"session_id":21340,"kind":"mcp_call","detail":"list_sessions by client:phone"}"""
        assertEquals(null, row("session:event", frame).sessionId())
    }

    @Test
    fun sessionId_is_null_for_a_row_event_that_is_not_about_a_session() {
        assertEquals(null, row("host:probed", hostPayload("box")).sessionId())
        assertEquals(null, row("project:updated", """{"id":1,"owner":"o","repo":"r"}""").sessionId())
    }

    @Test
    fun sessionId_is_null_when_the_payload_has_no_id() {
        assertEquals(null, row("session:killed", """{"no-id-here":true}""").sessionId())
    }

    /**
     * What the stream asks the hub for (`?kinds=`) has to be what the snapshot
     * applies. Subscribe to too little and rows go stale in silence; subscribe
     * to too much and the phone pays for frames it drops.
     */
    @Test
    fun the_subscribed_kinds_are_exactly_the_ones_the_snapshot_acts_on() {
        assertEquals(listOf("session", "host", "project", "work"), SNAPSHOT_EVENT_KINDS)

        val empty = FleetSnapshot()
        val acted = listOf(
            "session" to row("session:created", sessionPayload(id = 1)),
            "host" to row("host:added", hostPayload("box")),
            "project" to row("project:updated", """{"id":3,"owner":"o","repo":"r","base_path":"/p","adopted":false}"""),
            "work" to row("work:item", ticketPayload(id = 5)),
        )
        assertEquals(SNAPSHOT_EVENT_KINDS, acted.map { it.first })
        for ((kind, frame) in acted) {
            assertTrue(empty.applying(frame) !== empty, "$kind is subscribed but does nothing")
        }
    }

    // ---- work graph (M8.1) ----

    /**
     * The hub strips nulls from every frame, so an update without `work` is a
     * session whose link was cleared — not a frame too small to carry it, the
     * way `is_controller` is. Keeping the old value, as the M8 plan's first
     * draft said, would pin a chip nobody could remove. Both payloads are the
     * hub's own serialization of one store row (see `STORE_ROW_WITH_WORK`).
     */
    @Test
    fun an_update_without_work_clears_the_chip_and_the_suggestion() {
        val linked = FleetSnapshot().applying(row("session:created", STORE_ROW_WITH_WORK))
        assertEquals("PAY-7", linked.sessions.single().work?.key)
        assertEquals(12L, linked.sessions.single().workSuggested?.linkId)

        val cleared = linked.applying(row("session:updated", STORE_ROW_WITHOUT_WORK))

        assertEquals(null, cleared.sessions.single().work, "absent on the wire means none")
        assertEquals(null, cleared.sessions.single().workSuggested)
    }

    /** And the other way: a frame that gains a link shows it. */
    @Test
    fun an_update_with_work_sets_it() {
        val bare = FleetSnapshot().applying(row("session:created", STORE_ROW_WITHOUT_WORK))
        val linked = bare.applying(row("session:updated", STORE_ROW_WITH_WORK))
        assertEquals("PAY-7", linked.sessions.single().work?.label)
    }

    @Test
    fun a_work_item_frame_upserts_the_ticket_and_keeps_what_the_row_cannot_carry() {
        val seeded = FleetSnapshot(
            tickets = mapOf(5L to Ticket(id = 5, key = "PAY-9", title = "old", liveSessionIds = listOf(4), views = listOf("mine"))),
        )

        val next = seeded.applying(row("work:item", ticketPayload(id = 5, title = "Refund webhook", status = "done")))

        val t = next.tickets.getValue(5)
        assertEquals("Refund webhook", t.title)
        assertEquals(StatusCategory.Done, t.statusCategory)
        assertEquals(listOf(4L), t.liveSessionIds, "not a column: carried over")
        assertEquals(listOf("mine"), t.views, "not a column: carried over")

        val added = next.applying(row("work:item", ticketPayload(id = 6)))
        assertEquals(setOf(5L, 6L), added.tickets.keys, "an item the cache has not seen is added")
    }

    @Test
    fun a_work_item_frame_without_unavailable_makes_the_ticket_available_again() {
        val gone = FleetSnapshot(tickets = mapOf(5L to Ticket(id = 5, key = "PAY-9", unavailableAt = 9, unavailableReason = "not_found_or_no_permission")))
        val back = gone.applying(row("work:item", ticketPayload(id = 5)))
        assertFalse(back.tickets.getValue(5).unavailable)
    }

    @Test
    fun a_removed_tracker_marks_its_tickets_unavailable_and_leaves_others() {
        val snap = FleetSnapshot(
            tickets = mapOf(
                1L to Ticket(id = 1, key = "PAY-1", trackerId = 2),
                2L to Ticket(id = 2, key = "OPS-1", trackerId = 3),
            ),
        )

        val next = snap.applying(row("work:tracker_removed", """{"id":2}"""))

        assertTrue(next.tickets.getValue(1).unavailable)
        assertEquals(TRACKER_REMOVED, next.tickets.getValue(1).unavailableReason)
        assertFalse(next.tickets.getValue(2).unavailable)
        assertSame(next, next.applying(row("work:tracker_removed", """{"id":2}""")), "nothing left to mark")
        assertSame(snap, snap.applying(row("work:tracker_removed", """{"id":99}""")))
        assertSame(snap, snap.applying(row("work:tracker", """{"id":2,"state":"ok"}""")), "a tracker's own row draws nothing yet")
    }

    @Test
    fun a_malformed_work_frame_changes_nothing() {
        val snap = FleetSnapshot()
        assertSame(snap, snap.applying(row("work:item", """{"no_id":true}""")))
        assertSame(snap, snap.applying(row("work:tracker_removed", """{"alias":"x"}""")))
    }
}

/** A `work:item` frame: the hub's `WorkItemRow`, nulls stripped. */
private fun ticketPayload(id: Long, title: String = "Refund", status: String = "todo") =
    """{"id":$id,"source":"jira","key":"PAY-$id","title":"$title","status_category":"$status",""" +
        """"created_at":1,"updated_at":2,"tracker_id":2,"external_id":"100$id","status_name":"To Do"}"""
