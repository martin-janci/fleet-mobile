package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Midday, so a test can put a row on either side of it without negative numbers. */
private const val NOW = 1_700_000_000L

private fun row(
    id: Long = 1,
    name: String = "sess",
    host: String = "box",
    claudeStatus: String? = "working",
    stuckKind: String? = null,
    lastActivityAt: Long? = NOW,
    tmuxName: String = name,
    branch: String? = null,
    tags: List<String> = emptyList(),
    activity: String? = null,
    work: WorkSummary? = null,
    orgId: Long? = null,
    projectId: Long? = null,
) = SessionRow(
    id = id,
    tmuxName = tmuxName,
    hostAlias = host,
    claudeStatus = claudeStatus,
    stuckKind = stuckKind,
    lastActivityAt = lastActivityAt,
    branch = branch,
    tags = tags,
    currentActivity = activity,
    work = work,
    orgId = orgId,
    projectId = projectId,
)

private fun SessionRow.kept(filters: SessionFilters, now: Long = NOW, project: String? = null) =
    matches(filters, now, project)

class SessionFiltersTest {

    @Test
    fun nothing_set_keeps_every_row() {
        assertTrue(row().kept(SessionFilters()))
        assertEquals(0, SessionFilters().activeCount)
        assertFalse(SessionFilters().any)
    }

    // ---- the activity window -------------------------------------------------

    /**
     * The trap the desktop's `matchesRecency` walks into: it returns false for
     * a null timestamp, so porting it would make a session the hub has not yet
     * stamped disappear the moment anyone picks a window — a *brand new*
     * session hidden by a filter asking for recent ones.
     */
    @Test
    fun a_row_the_hub_has_never_stamped_survives_every_window_in_both_directions() {
        val unstamped = row(lastActivityAt = null)
        for (w in TimeWindow.entries) {
            for (d in TimeDirection.entries) {
                assertTrue(
                    unstamped.kept(SessionFilters(window = w, direction = d)),
                    "an unstamped row was dropped by $d $w",
                )
            }
        }
    }

    @Test
    fun active_within_keeps_a_row_exactly_on_the_boundary_and_drops_the_next_second() {
        val f = SessionFilters(window = TimeWindow.H1, direction = TimeDirection.WITHIN)
        assertTrue(row(lastActivityAt = NOW - 3_600).kept(f), "an hour old is within an hour")
        assertFalse(row(lastActivityAt = NOW - 3_601).kept(f))
    }

    @Test
    fun idle_beyond_is_the_exact_complement_of_active_within() {
        val within = SessionFilters(window = TimeWindow.H1, direction = TimeDirection.WITHIN)
        val beyond = within.copy(direction = TimeDirection.BEYOND)
        // A stamped row is kept by exactly one of the two, whatever its age —
        // which is what makes the pair a direction rather than two filters.
        for (age in listOf(0L, 1, 3_599, 3_600, 3_601, 86_400)) {
            val r = row(lastActivityAt = NOW - age)
            assertEquals(
                1,
                listOf(r.kept(within), r.kept(beyond)).count { it },
                "age ${age}s is in neither or both halves",
            )
        }
    }

    /**
     * A host whose clock runs ahead of the skew correction stamps a row in the
     * future. It is *just active*, not idle since before the fleet existed, so
     * the age floors at zero rather than going negative.
     */
    @Test
    fun a_timestamp_in_the_future_counts_as_just_active_and_never_as_idle() {
        val ahead = row(lastActivityAt = NOW + 600)
        assertTrue(ahead.kept(SessionFilters(window = TimeWindow.H1, direction = TimeDirection.WITHIN)))
        assertFalse(ahead.kept(SessionFilters(window = TimeWindow.H1, direction = TimeDirection.BEYOND)))
    }

    @Test
    fun the_any_window_ignores_the_direction_entirely() {
        val old = row(lastActivityAt = NOW - 999_999)
        for (d in TimeDirection.entries) {
            assertTrue(old.kept(SessionFilters(window = TimeWindow.ANY, direction = d)))
        }
        assertEquals(0, SessionFilters(direction = TimeDirection.BEYOND).activeCount)
    }

    // ---- status --------------------------------------------------------------

    @Test
    fun no_status_chosen_means_every_status() {
        for (s in listOf("working", "blocked", "failed", null)) {
            assertTrue(row(claudeStatus = s).kept(SessionFilters()))
        }
    }

    @Test
    fun statuses_are_or_ed_together() {
        val f = SessionFilters(statuses = setOf(StatusFilter.BLOCKED, StatusFilter.FAILED))
        assertTrue(row(claudeStatus = "blocked").kept(f))
        assertTrue(row(claudeStatus = "failed").kept(f))
        assertFalse(row(claudeStatus = "working").kept(f))
    }

    /** `stuck_kind` is a different column, and a stuck row's status can be anything. */
    @Test
    fun stuck_asks_the_stuck_field_whatever_the_status_says() {
        val f = SessionFilters(statuses = setOf(StatusFilter.STUCK))
        assertTrue(row(claudeStatus = "working", stuckKind = "oom").kept(f))
        assertTrue(row(claudeStatus = null, stuckKind = "auth_menu").kept(f))
        assertFalse(row(claudeStatus = "working", stuckKind = null).kept(f))
    }

    @Test
    fun a_row_with_no_status_at_all_is_dropped_once_a_status_is_asked_for() {
        assertFalse(row(claudeStatus = null).kept(SessionFilters(statuses = setOf(StatusFilter.WORKING))))
    }

    // ---- background ----------------------------------------------------------

    @Test
    fun background_agents_are_listed_until_they_are_switched_off() {
        val bg = row(tmuxName = "bg:abcd")
        assertTrue(bg.kept(SessionFilters()))
        assertFalse(bg.kept(SessionFilters(showBackground = false)))
        assertTrue(row(tmuxName = "work-1").kept(SessionFilters(showBackground = false)))
    }

    // ---- search --------------------------------------------------------------

    @Test
    fun a_blank_query_keeps_everything_and_counts_for_nothing() {
        assertTrue(row().kept(SessionFilters(query = "   ")))
        assertEquals(0, SessionFilters(query = "   ").activeCount)
    }

    @Test
    fun the_query_reaches_every_field_the_row_is_filed_under() {
        val r = row(
            tmuxName = "hub-rework",
            host = "mefistos",
            branch = "feat/filters",
            tags = listOf("urgent"),
            activity = "running clippy",
            work = WorkSummary(key = "ABC-12", title = "Session filters"),
        )
        for (q in listOf("rework", "MEFISTOS", "feat/fil", "urgent", "clippy", "abc-12", "Session filters")) {
            assertTrue(r.kept(SessionFilters(query = q)), "\"$q\" did not match")
        }
        assertTrue(r.kept(SessionFilters(query = "claude-fleet"), project = "martin-janci/claude-fleet"))
        assertFalse(r.kept(SessionFilters(query = "nothing-like-this")))
    }

    /** A row carries a `project_id` and no name, so the label has to be handed in. */
    @Test
    fun a_project_name_only_matches_when_the_label_is_supplied() {
        val r = row(tmuxName = "s1")
        assertFalse(r.kept(SessionFilters(query = "fleet-mobile"), project = null))
        assertTrue(r.kept(SessionFilters(query = "fleet-mobile"), project = "martin-janci/fleet-mobile"))
    }

    @Test
    fun a_query_is_trimmed_before_it_is_matched() {
        assertTrue(row(tmuxName = "hub").kept(SessionFilters(query = "  hub  ")))
    }

    // ---- host, org -----------------------------------------------------------

    @Test
    fun the_host_and_org_filters_keep_only_their_own() {
        assertTrue(row(host = "pine").kept(SessionFilters(hostFilter = "pine")))
        assertFalse(row(host = "box").kept(SessionFilters(hostFilter = "pine")))
        assertTrue(row(orgId = 7).kept(SessionFilters(orgFilter = 7)))
        assertFalse(row(orgId = 9).kept(SessionFilters(orgFilter = 7)))
        // A session no org claims is not in any of them.
        assertFalse(row(orgId = null).kept(SessionFilters(orgFilter = 7)))
    }

    // ---- the count and the way out -------------------------------------------

    @Test
    fun every_filter_counts_once_and_the_time_pair_counts_once_between_them() {
        val all = SessionFilters(
            query = "x",
            needsAttentionOnly = true,
            window = TimeWindow.D1,
            direction = TimeDirection.BEYOND,
            statuses = setOf(StatusFilter.BLOCKED, StatusFilter.FAILED),
            showBackground = false,
            hostFilter = "box",
            myWorkOnly = true,
            orgFilter = 3,
            projectFilter = 4,
            workStatuses = setOf(WorkStatusFilter.TODO, WorkStatusFilter.DONE),
            showArchived = false,
        )
        assertEquals(11, all.activeCount)
        assertTrue(all.any)
    }

    /**
     * The chip's badge counts what is *in the sheet*. Needs-attention and the
     * search both have a control of their own on screen, so counting them made
     * the button read "Filters · 1" over a sheet where nothing was set.
     */
    @Test
    fun the_filters_chip_does_not_count_the_controls_that_sit_beside_it() {
        assertEquals(0, SessionFilters(needsAttentionOnly = true).sheetCount)
        assertEquals(0, SessionFilters(query = "hub").sheetCount)
        assertEquals(0, SessionFilters(needsAttentionOnly = true, query = "hub").sheetCount)

        // Everything else still counts, and both counts move together.
        val inSheet = SessionFilters(needsAttentionOnly = true, query = "hub", hostFilter = "box", myWorkOnly = true)
        assertEquals(4, inSheet.activeCount)
        assertEquals(2, inSheet.sheetCount)
    }

    /** The summary names every filter, wherever its control happens to live. */
    @Test
    fun the_summary_still_names_the_filters_the_chip_does_not_count() {
        val f = SessionFilters(needsAttentionOnly = true, query = "hub")
        assertEquals(0, f.sheetCount)
        assertEquals(listOf("Needs you", "\"hub\""), f.summary())
    }

    /**
     * `Screen.Sessions.hostAlias` owns the host filter, so clearing it here
     * would put this object and the navigator into disagreement — the stale
     * `returnTo` bug `Navigator.clearHostFilter` documents. The screen calls
     * both; this one carries the host through untouched.
     */
    @Test
    fun clearing_drops_every_filter_but_the_host_the_navigator_owns() {
        val cleared = SessionFilters(
            query = "x",
            needsAttentionOnly = true,
            window = TimeWindow.D7,
            statuses = setOf(StatusFilter.STUCK),
            showBackground = false,
            hostFilter = "box",
            myWorkOnly = true,
            orgFilter = 3,
            projectFilter = 4,
            workStatuses = setOf(WorkStatusFilter.IN_PROGRESS),
            showArchived = false,
        ).cleared()

        assertEquals(SessionFilters(hostFilter = "box"), cleared)
        assertEquals(1, cleared.activeCount)
    }

    @Test
    fun the_summary_names_what_is_on_and_says_nothing_when_nothing_is() {
        assertEquals(emptyList(), SessionFilters().summary())
        val named = SessionFilters(
            hostFilter = "box",
            needsAttentionOnly = true,
            window = TimeWindow.D1,
            direction = TimeDirection.BEYOND,
            statuses = setOf(StatusFilter.FAILED, StatusFilter.BLOCKED),
            orgFilter = 3,
            showBackground = false,
            query = " hub ",
        ).summary { "Acme" }

        assertEquals(
            listOf("Host box", "Needs you", "Idle beyond 24 hours", "Blocked/Failed", "Acme", "No background", "\"hub\""),
            named,
        )
    }

    /** The statuses are named in the order the sheet offers them, not in set order. */
    @Test
    fun the_summary_names_statuses_in_the_sheets_order() {
        val f = SessionFilters(statuses = setOf(StatusFilter.STOPPED, StatusFilter.WORKING, StatusFilter.STUCK))
        assertEquals(listOf("Working/Stuck/Stopped"), f.summary())
    }

    // ---- the desktop's work filters and the project --------------------------

    @Test
    fun the_project_filter_keeps_only_its_own_and_never_a_session_in_no_project() {
        val f = SessionFilters(projectFilter = 4)
        assertTrue(row(projectId = 4).kept(f))
        assertFalse(row(projectId = 5).kept(f))
        assertFalse(row(projectId = null).kept(f))
    }

    /**
     * The desktop's rule: a status filter asks about tickets, so a session
     * with no work — or work the tracker gave no status — is not an answer.
     */
    @Test
    fun a_ticket_status_keeps_only_work_in_that_bucket() {
        val todo = row(work = WorkSummary(key = "ABC-1", statusCategory = StatusCategory.Todo))
        val done = row(work = WorkSummary(key = "ABC-2", statusCategory = StatusCategory.Done))
        val bare = row(work = WorkSummary(key = "ABC-3"))
        val none = row(work = null)

        val f = SessionFilters(workStatuses = setOf(WorkStatusFilter.TODO))
        assertTrue(todo.kept(f))
        assertFalse(done.kept(f))
        assertFalse(bare.kept(f))
        assertFalse(none.kept(f))

        // OR-ed, like the session statuses.
        val both = SessionFilters(workStatuses = setOf(WorkStatusFilter.TODO, WorkStatusFilter.DONE))
        assertTrue(todo.kept(both))
        assertTrue(done.kept(both))
        assertFalse(bare.kept(both))

        // None chosen asks nothing.
        assertTrue(none.kept(SessionFilters()))
    }

    /**
     * The tracker's own status names ("QA Review"): matched case-insensitively,
     * and OR-ed with the buckets — one ticket-status question, one filter.
     */
    @Test
    fun a_tracker_status_name_keeps_only_work_in_that_column() {
        val qa = row(work = WorkSummary(key = "ABC-1", statusCategory = StatusCategory.InProgress, statusName = "QA Review"))
        val dev = row(work = WorkSummary(key = "ABC-2", statusCategory = StatusCategory.InProgress, statusName = "In Progress"))
        val todo = row(work = WorkSummary(key = "ABC-3", statusCategory = StatusCategory.Todo, statusName = "To Do"))
        val none = row(work = null)

        val f = SessionFilters(workStatusNames = setOf("qa review"))
        assertTrue(qa.kept(f))
        assertFalse(dev.kept(f), "the bucket is the same; the column is not")
        assertFalse(todo.kept(f))
        assertFalse(none.kept(f))

        val either = SessionFilters(workStatuses = setOf(WorkStatusFilter.TODO), workStatusNames = setOf("QA Review"))
        assertTrue(qa.kept(either))
        assertTrue(todo.kept(either))
        assertFalse(dev.kept(either))
        assertEquals(1, either.activeCount)
        assertEquals(listOf("Ticket to do/QA Review"), either.summary())
    }

    @Test
    fun status_names_are_offered_once_in_workflow_order() {
        fun w(cat: StatusCategory?, name: String?) = row(work = WorkSummary(key = "K-1", statusCategory = cat, statusName = name))
        assertEquals(
            listOf("Backlog", "In Progress", "QA Review", "Done", "Parked"),
            workStatusNames(
                listOf(
                    w(StatusCategory.Done, "Done"),
                    w(StatusCategory.InProgress, "QA Review"),
                    w(StatusCategory.InProgress, "qa review"),
                    w(StatusCategory.Unknown, "Parked"),
                    w(StatusCategory.Todo, "Backlog"),
                    w(StatusCategory.InProgress, "In Progress"),
                    w(StatusCategory.Todo, "  "),
                    row(work = null),
                ),
            ),
        )
    }

    /** A status a later hub adds reads as Unknown, and no chip asks for that. */
    @Test
    fun an_unknown_ticket_status_matches_no_chip() {
        val odd = row(work = WorkSummary(key = "ABC-4", statusCategory = StatusCategory.Unknown))
        for (w in WorkStatusFilter.entries) assertFalse(odd.kept(SessionFilters(workStatuses = setOf(w))))
    }

    @Test
    fun archived_sessions_are_listed_until_they_are_switched_off() {
        val archived = row(work = WorkSummary(key = "ABC-5", archivedAt = NOW - 60))
        val live = row(work = WorkSummary(key = "ABC-6"))
        assertTrue(archived.kept(SessionFilters()))
        val f = SessionFilters(showArchived = false)
        assertFalse(archived.kept(f))
        assertTrue(live.kept(f))
        assertTrue(row(work = null).kept(f), "a session with no work was never archived")
    }

    @Test
    fun the_summary_names_the_project_and_the_work_filters() {
        val f = SessionFilters(
            projectFilter = 4,
            workStatuses = setOf(WorkStatusFilter.DONE, WorkStatusFilter.TODO),
            showArchived = false,
        )
        assertEquals(
            listOf("acme/api", "Ticket to do/done", "No archived"),
            f.summary(projectName = { "acme/api" }),
        )
        // Without a name to hand, the project reads the way its group heading would.
        assertEquals("project #4", f.summary().first())
    }
}
