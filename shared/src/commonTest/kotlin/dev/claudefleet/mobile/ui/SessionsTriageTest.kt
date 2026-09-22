package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fixed clock, so "an hour ago" is a subtraction rather than a race. */
private const val NOW = 1_800_000_000L

private const val MINUTE = 60L
private const val HOUR = 60L * MINUTE
private const val DAY = 24L * HOUR

private fun row(
    id: Long,
    host: String = "box",
    project: Long? = 1,
    name: String = "s$id",
    claudeStatus: String? = "idle",
    stuckKind: String? = null,
    kind: String? = "work",
    activity: String? = null,
    lastPrompt: String? = null,
    tags: List<String> = emptyList(),
    branch: String? = null,
    friendlyName: String? = null,
    ago: Long? = MINUTE,
) = SessionRow(
    id = id,
    tmuxName = name,
    friendlyName = friendlyName,
    hostAlias = host,
    projectId = project,
    kind = kind,
    claudeStatus = claudeStatus,
    stuckKind = stuckKind,
    currentActivity = activity,
    lastPrompt = lastPrompt,
    tags = tags,
    branch = branch,
    lastActivityAt = ago?.let { NOW - it },
)

private fun triage(
    sessions: List<SessionRow>,
    lens: Lens = Lens.All,
    query: String = "",
    hideNoise: Boolean = false,
    hostFilter: String? = null,
    collapsedHosts: Set<String> = emptySet(),
    hosts: List<HostRow> = emptyList(),
    projects: List<ProjectRow> = emptyList(),
) = triageSessions(
    sessions = sessions,
    hosts = hosts,
    projects = projects,
    lens = lens,
    query = query,
    hideNoise = hideNoise,
    hostFilter = hostFilter,
    collapsedHosts = collapsedHosts,
    nowSeconds = NOW,
)

/** Every id the grouped part of a triage draws, in the order it draws them. */
private fun Triage.groupedIds(): List<Long> =
    groups.flatMap { host -> host.projects.flatMap { it.sessions } }.map { it.id }

/** Every id the screen draws at all — the live part and the tail together. */
private fun Triage.allIds(): List<Long> = groupedIds() + dormant.map { it.id }

/**
 * Which rows each lens keeps.
 *
 * The lenses answer one question — *how is this session being used right now* —
 * and they are the reason the screen is usable on a fleet of fifty-odd rows,
 * where the single "needs attention" toggle it replaces was empty most of the
 * day and left everything else in one undifferentiated list.
 */
class TheLensesTest {

    @Test
    fun needs_you_keeps_the_blocked_and_the_stuck_and_nothing_else() {
        val kept = triage(
            sessions = listOf(
                row(1, claudeStatus = "blocked"),
                row(2, stuckKind = "press_enter"),
                row(3, claudeStatus = "working"),
                row(4, claudeStatus = "idle"),
            ),
            lens = Lens.NeedsYou,
        ).groupedIds()

        assertEquals(listOf(1L, 2L), kept.sorted())
    }

    @Test
    fun active_keeps_a_working_row_however_long_ago_it_was_stamped() {
        val kept = triage(
            sessions = listOf(row(1, claudeStatus = "working", ago = 9 * DAY)),
            lens = Lens.Active,
        ).groupedIds()

        assertEquals(listOf(1L), kept, "`working` is what the pane says NOW; the stamp is not the authority")
    }

    @Test
    fun active_keeps_a_row_stamped_within_the_quarter_hour_whatever_its_status() {
        val kept = triage(
            sessions = listOf(
                row(1, claudeStatus = "idle", ago = 5 * MINUTE),
                row(2, claudeStatus = "idle", ago = 40 * MINUTE),
            ),
            lens = Lens.Active,
        ).groupedIds()

        assertEquals(listOf(1L), kept)
    }

    /**
     * A blocked session is not "active" by the clock and is exactly what a
     * person watching live work needs to see, so it is kept under [Lens.Active]
     * on the strength of being blocked rather than of being recent.
     */
    @Test
    fun active_keeps_a_row_that_needs_a_person_even_when_it_last_moved_hours_ago() {
        val kept = triage(
            sessions = listOf(row(1, claudeStatus = "blocked", ago = 5 * HOUR)),
            lens = Lens.Active,
        ).groupedIds()

        assertEquals(listOf(1L), kept)
    }

    @Test
    fun today_keeps_this_morning_and_drops_yesterday() {
        val kept = triage(
            sessions = listOf(
                row(1, ago = 6 * HOUR),
                row(2, ago = 30 * HOUR),
            ),
            lens = Lens.Today,
        ).groupedIds()

        assertEquals(listOf(1L), kept)
    }

    /**
     * The boundaries, named. Both ceilings were written as `<=` and both are
     * the kind of edge a sweep finds and a reading does not.
     */
    @Test
    fun a_row_exactly_on_a_boundary_is_inside_it() {
        val onActive = triage(sessions = listOf(row(1, ago = ACTIVE_SECONDS)), lens = Lens.Active)
        val onToday = triage(sessions = listOf(row(1, ago = DORMANT_SECONDS)), lens = Lens.Today)

        assertEquals(listOf(1L), onActive.groupedIds(), "a row stamped exactly 15 minutes ago is still active")
        assertEquals(listOf(1L), onToday.groupedIds(), "a row stamped exactly a day ago is still today")
    }

    /**
     * A row the hub has never stamped survives only the widest lens — and
     * lands in the tail even there, which is [TheDormantTailTest]'s business
     * rather than this one's. Asserted over both halves of the screen so the
     * two rules cannot silently start disagreeing.
     */
    @Test
    fun a_row_the_hub_never_stamped_appears_only_under_all() {
        val unstamped = listOf(row(1, claudeStatus = null, ago = null))

        assertEquals(listOf(1L), triage(unstamped, lens = Lens.All).allIds())
        assertTrue(triage(unstamped, lens = Lens.Today).allIds().isEmpty())
        assertTrue(triage(unstamped, lens = Lens.Active).allIds().isEmpty())
    }
}

/**
 * Background agents and shell panes, and the switch that puts them away.
 *
 * On the fleet this screen was written against these are a quarter of the rows
 * and almost none of the taps: a `bg:` row is an agent nobody opens by hand,
 * and a shell session has no conversation to open at all.
 */
class HidingTheNoiseTest {

    @Test
    fun hiding_the_noise_drops_background_and_shell_rows() {
        val result = triage(
            sessions = listOf(
                row(1),
                row(2, name = "bg:aa555569-41d0-480f"),
                row(3, kind = "shell"),
            ),
            hideNoise = true,
        )

        assertEquals(listOf(1L), result.groupedIds())
    }

    @Test
    fun the_hidden_count_is_what_turning_the_switch_off_would_bring_back() {
        val sessions = listOf(row(1), row(2, name = "bg:x"), row(3, kind = "shell"))

        assertEquals(2, triage(sessions, hideNoise = true).hiddenNoise)
        assertEquals(0, triage(sessions, hideNoise = false).hiddenNoise)
    }

    /**
     * A background agent that is blocked is the single most urgent row on the
     * screen — it is waiting on a person and nobody is looking at its pane.
     * Hiding it because of the shape of its name would be the worst thing this
     * switch could do.
     */
    @Test
    fun a_row_that_needs_a_person_is_never_hidden_as_noise() {
        val result = triage(
            sessions = listOf(row(1, name = "bg:x", claudeStatus = "blocked")),
            hideNoise = true,
        )

        assertEquals(listOf(1L), result.groupedIds())
        assertEquals(0, result.hiddenNoise, "it was not hidden, so it is not counted as hidden")
    }
}

/**
 * The tail: what has gone quiet for more than a day.
 *
 * Not filtered away — a dormant session is still a session, and killing one is
 * a decision a person makes by looking at it — but out of the way of the rows
 * that moved today.
 */
class TheDormantTailTest {

    @Test
    fun a_row_silent_for_more_than_a_day_goes_to_the_tail() {
        val result = triage(sessions = listOf(row(1, ago = MINUTE), row(2, ago = 3 * DAY)))

        assertEquals(listOf(1L), result.groupedIds())
        assertEquals(listOf(2L), result.dormant.map { it.id })
    }

    @Test
    fun a_row_the_hub_never_stamped_is_dormant() {
        val result = triage(sessions = listOf(row(1, claudeStatus = null, ago = null)))

        assertEquals(listOf(1L), result.dormant.map { it.id }, "nothing has ever happened on it that the hub saw")
    }

    @Test
    fun a_row_that_needs_a_person_is_never_dormant() {
        val result = triage(sessions = listOf(row(1, claudeStatus = "blocked", ago = 9 * DAY)))

        assertEquals(listOf(1L), result.groupedIds())
        assertTrue(result.dormant.isEmpty())
    }

    @Test
    fun a_working_row_is_never_dormant_however_stale_its_stamp() {
        val result = triage(sessions = listOf(row(1, claudeStatus = "working", ago = 9 * DAY)))

        assertEquals(listOf(1L), result.groupedIds())
        assertTrue(result.dormant.isEmpty())
    }

    @Test
    fun the_tail_is_newest_first() {
        val result = triage(
            sessions = listOf(row(1, ago = 5 * DAY), row(2, ago = 2 * DAY), row(3, ago = 9 * DAY)),
        )

        assertEquals(listOf(2L, 1L, 3L), result.dormant.map { it.id })
    }

    /**
     * Under a lens that has already excluded everything dormant, a tail would
     * be a heading over a list the lens just said not to show.
     */
    @Test
    fun a_lens_that_excludes_the_old_has_no_tail() {
        val sessions = listOf(row(1, ago = MINUTE), row(2, ago = 3 * DAY))

        assertTrue(triage(sessions, lens = Lens.Today).dormant.isEmpty())
        assertTrue(triage(sessions, lens = Lens.Active).dormant.isEmpty())
        assertEquals(listOf(2L), triage(sessions, lens = Lens.All).dormant.map { it.id })
    }
}

/**
 * Search, which is the only way to find one session by what it is *doing*
 * rather than by where it lives.
 */
class SearchingTheFleetTest {

    @Test
    fun a_blank_query_is_not_a_search() {
        assertNull(triage(listOf(row(1)), query = "   ").results, "whitespace is not a question")
    }

    @Test
    fun a_query_matches_the_name_the_row_draws() {
        val result = triage(listOf(row(1, friendlyName = "improve mobile app UX"), row(2)), query = "mobile")

        assertEquals(listOf(1L), assertNotNull(result.results).map { it.id })
    }

    @Test
    fun a_query_matches_the_tmux_name_even_when_a_friendly_name_hides_it() {
        val result = triage(
            listOf(row(1, name = "dev-papayapos-backend--violet-pulsar", friendlyName = "something else")),
            query = "violet-pulsar",
        )

        assertEquals(listOf(1L), assertNotNull(result.results).map { it.id })
    }

    @Test
    fun a_query_matches_the_last_prompt() {
        val result = triage(listOf(row(1, lastPrompt = "analyzuj mobilnu appku"), row(2)), query = "APPKU")

        assertEquals(listOf(1L), assertNotNull(result.results).map { it.id }, "matching ignores case")
    }

    @Test
    fun a_query_matches_a_tag() {
        val result = triage(listOf(row(1, tags = listOf("release", "urgent")), row(2)), query = "urg")

        assertEquals(listOf(1L), assertNotNull(result.results).map { it.id })
    }

    @Test
    fun a_query_matches_the_branch() {
        val result = triage(listOf(row(1, branch = "feat/pager-phase-1"), row(2)), query = "pager")

        assertEquals(listOf(1L), assertNotNull(result.results).map { it.id })
    }

    @Test
    fun a_query_matches_the_host() {
        val result = triage(listOf(row(1, host = "mefistos"), row(2, host = "box")), query = "mefi")

        assertEquals(listOf(1L), assertNotNull(result.results).map { it.id })
    }

    @Test
    fun a_query_matches_the_project_label_the_screen_shows() {
        val result = triage(
            listOf(row(1, project = 7), row(2, project = 8)),
            query = "openmarket",
            projects = listOf(ProjectRow(id = 7, owner = "papayapos", repo = "openmarket-ai")),
        )

        assertEquals(listOf(1L), assertNotNull(result.results).map { it.id })
    }

    /**
     * The activity is searched as the screen *shows* it, not as the hub sent
     * it. Raw `current_activity` carries ANSI escapes and the REPL's own
     * footer, and a query that matched those would find rows on the strength
     * of terminal punctuation nobody can see.
     */
    @Test
    fun a_query_matches_the_activity_after_it_has_been_sanitised() {
        val noisy = "\u001B[38;5;244mReading SessionsViewModel.kt\u001B[0m"
        val chrome = "⏵⏵ bypass permissions on (shift+tab to cycle)"

        val found = triage(listOf(row(1, activity = noisy)), query = "SessionsViewModel")
        val notFound = triage(listOf(row(1, activity = chrome)), query = "bypass")

        assertEquals(listOf(1L), assertNotNull(found.results).map { it.id })
        assertTrue(assertNotNull(notFound.results).isEmpty(), "chrome is not shown, so it is not searched")
    }

    /**
     * A search looks at the whole fleet. A search that only looked inside the
     * current lens would answer "no such session" about a session that is
     * plainly there, which is the one answer a search must never give wrongly.
     */
    @Test
    fun a_query_searches_past_the_lens_the_noise_switch_and_the_tail() {
        val sessions = listOf(
            row(1, name = "bg:quiet-one", lastPrompt = "find me", ago = 9 * DAY, claudeStatus = "idle"),
        )

        val result = triage(sessions, query = "find me", lens = Lens.Active, hideNoise = true)

        assertEquals(listOf(1L), assertNotNull(result.results).map { it.id })
    }

    /** The host filter is where you are, not what you asked, so it still holds. */
    @Test
    fun a_query_still_obeys_the_host_filter() {
        val result = triage(
            listOf(row(1, host = "box", lastPrompt = "find me"), row(2, host = "pine", lastPrompt = "find me")),
            query = "find me",
            hostFilter = "pine",
        )

        assertEquals(listOf(2L), assertNotNull(result.results).map { it.id })
    }

    @Test
    fun results_are_newest_first() {
        val result = triage(
            listOf(
                row(1, lastPrompt = "hit", ago = 5 * HOUR),
                row(2, lastPrompt = "hit", ago = 1 * MINUTE),
                row(3, lastPrompt = "hit", ago = 2 * DAY),
            ),
            query = "hit",
        )

        assertEquals(listOf(2L, 1L, 3L), assertNotNull(result.results).map { it.id })
    }

    /** While searching there are no groups and no tail — one flat answer. */
    @Test
    fun a_search_replaces_the_grouping_rather_than_decorating_it() {
        val result = triage(listOf(row(1, lastPrompt = "hit"), row(2, ago = 3 * DAY)), query = "hit")

        assertTrue(result.groups.isEmpty())
        assertTrue(result.dormant.isEmpty())
    }
}

/** Collapsing a host keeps its heading and its count and puts its rows away. */
class CollapsingAHostTest {

    @Test
    fun a_collapsed_host_still_says_how_many_it_has() {
        val result = triage(
            listOf(row(1, host = "box"), row(2, host = "box"), row(3, host = "pine")),
            collapsedHosts = setOf("box"),
        )

        val box = result.groups.single { it.alias == "box" }
        assertTrue(box.collapsed)
        assertEquals(2, box.sessionCount, "the count is what the heading promises is behind it")
        assertEquals(listOf(3L), result.groupedIds(), "a collapsed host draws no rows")
    }

    @Test
    fun collapsing_a_host_that_is_not_there_changes_nothing() {
        val result = triage(listOf(row(1, host = "box")), collapsedHosts = setOf("gone"))

        assertEquals(listOf(1L), result.groupedIds())
    }
}

/**
 * The two boundaries a mutation sweep found nothing was holding.
 *
 * Both survived a first sweep, and neither is visible from the screen: one is
 * a row that has been quiet for exactly a day, the other a count that only
 * differs once a lens is narrower than everything.
 */
class TheEdgesOfTheTailAndTheCountTest {

    /**
     * `Today` and "not dormant" are one boundary read from two sides, and the
     * comparison at each end has to agree: a row stamped exactly a day ago is
     * *today*, so it must also not be in the tail. Read the other way it would
     * be both — drawn in the tail under `All` while the `Today` lens claimed
     * it, which is a picture of two different rules.
     */
    @Test
    fun a_row_stamped_exactly_a_day_ago_is_today_and_is_not_dormant() {
        val onTheEdge = listOf(row(1, ago = DORMANT_SECONDS))

        assertEquals(listOf(1L), triage(onTheEdge, lens = Lens.Today).groupedIds())
        assertTrue(triage(onTheEdge, lens = Lens.All).dormant.isEmpty(), "the two ends of one boundary must agree")
        assertEquals(listOf(1L), triage(onTheEdge, lens = Lens.All).groupedIds())
    }

    /** And a second past it is dormant, so the boundary is where it says. */
    @Test
    fun a_row_stamped_a_second_later_than_that_is_dormant() {
        val result = triage(listOf(row(1, ago = DORMANT_SECONDS + 1)), lens = Lens.All)

        assertEquals(listOf(1L), result.dormant.map { it.id })
        assertTrue(result.groupedIds().isEmpty())
    }

    /**
     * The count on the noise chip promises what turning it off would bring
     * back *here*. Counted over the whole fleet instead, it would promise rows
     * the lens had already excluded: the chip would offer fourteen and deliver
     * none, which is worse than offering nothing.
     */
    @Test
    fun the_hidden_count_is_taken_after_the_lens_not_before_it() {
        val sessions = listOf(
            row(1, claudeStatus = "blocked"),
            row(2, name = "bg:quiet", claudeStatus = "idle", ago = 3 * DAY),
        )

        assertEquals(
            0,
            triage(sessions, lens = Lens.NeedsYou, hideNoise = true).hiddenNoise,
            "the lens dropped that row before the switch ever saw it",
        )
        assertEquals(1, triage(sessions, lens = Lens.All, hideNoise = true).hiddenNoise)
    }

    /** The same for the host filter, which narrows before the switch too. */
    @Test
    fun the_hidden_count_is_taken_after_the_host_filter() {
        val sessions = listOf(row(1, host = "box"), row(2, host = "pine", name = "bg:elsewhere"))

        assertEquals(0, triage(sessions, hostFilter = "box", hideNoise = true).hiddenNoise)
    }
}
