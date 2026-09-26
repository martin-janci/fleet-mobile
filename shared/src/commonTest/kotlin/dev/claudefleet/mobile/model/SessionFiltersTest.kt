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
        )
        assertEquals(8, all.activeCount)
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
}
