@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.model.OrgTracker
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.StatusCategory
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.TicketFacetId
import dev.claudefleet.mobile.model.TicketFilters
import dev.claudefleet.mobile.model.TicketList
import dev.claudefleet.mobile.model.TicketSessionFilter
import dev.claudefleet.mobile.model.TicketSort
import dev.claudefleet.mobile.model.TrackerRow
import dev.claudefleet.mobile.model.WorkStatusFilter
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.model.sortTickets
import dev.claudefleet.mobile.model.ticketFacets
import dev.claudefleet.mobile.model.ticketMatchesQuery
import dev.claudefleet.mobile.model.ticketStatusNames
import dev.claudefleet.mobile.store.FakePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun t(
    id: Long,
    key: String,
    title: String = "",
    category: StatusCategory? = null,
    name: String? = null,
    tracker: Long? = null,
    updated: Long? = null,
    live: List<Long> = emptyList(),
) = Ticket(
    id = id,
    key = key,
    title = title,
    statusCategory = category,
    statusName = name,
    trackerId = tracker,
    updatedExt = updated,
    liveSessionIds = live,
)

private val OM110 = t(1, "OM-110", "Harmonization API", StatusCategory.Todo, "Backlog", tracker = 7, updated = 100)
private val PD2592 = t(2, "PD-2592", "Support access", StatusCategory.InProgress, "In Progress", tracker = 8, updated = 300, live = listOf(5))
private val PD2223 = t(3, "PD-2223", "Cleanup endpoints", StatusCategory.InProgress, "Code Review", tracker = 8, updated = 200)
private val PD2000 = t(4, "PD-2000", "Old thing", StatusCategory.Done, "Done", tracker = 8)

class TicketFiltersTest {

    // ── The pure rules ──

    @Test
    fun a_status_bucket_and_a_column_are_one_or_ed_question() {
        val f = TicketFilters(statuses = setOf(WorkStatusFilter.TODO), statusNames = setOf("code review"))
        val kept = listOf(OM110, PD2592, PD2223, PD2000).filter { f.matches(it, { null }, { false }) }
        assertEquals(listOf("OM-110", "PD-2223"), kept.map { it.key })
    }

    @Test
    fun a_ticket_with_no_status_answers_no_status_question() {
        val bare = t(9, "X-1")
        assertFalse(TicketFilters(statuses = setOf(WorkStatusFilter.TODO)).matches(bare, { null }, { false }))
        assertTrue(TicketFilters().matches(bare, { null }, { false }))
    }

    @Test
    fun the_session_filter_asks_whether_one_is_live() {
        val live = { ticket: Ticket -> ticket.id == 2L }
        assertTrue(TicketFilters(session = TicketSessionFilter.LIVE).matches(PD2592, { null }, live))
        assertFalse(TicketFilters(session = TicketSessionFilter.LIVE).matches(OM110, { null }, live))
        assertTrue(TicketFilters(session = TicketSessionFilter.NONE).matches(OM110, { null }, live))
        assertFalse(TicketFilters(session = TicketSessionFilter.NONE).matches(PD2592, { null }, live))
    }

    @Test
    fun org_and_tracker_narrow_by_the_ticket_s_tracker() {
        val orgOf = { ticket: Ticket -> if (ticket.trackerId == 7L) 1L else 2L }
        assertTrue(TicketFilters(org = 1).matches(OM110, orgOf, { false }))
        assertFalse(TicketFilters(org = 1).matches(PD2592, orgOf, { false }))
        assertTrue(TicketFilters(tracker = 8).matches(PD2592, orgOf, { false }))
        assertFalse(TicketFilters(tracker = 8).matches(OM110, orgOf, { false }))
    }

    @Test
    fun the_query_matches_key_or_title_and_a_url_narrows_nothing() {
        assertTrue(ticketMatchesQuery(PD2592, "pd-25"))
        assertTrue(ticketMatchesQuery(PD2592, "SUPPORT"))
        assertFalse(ticketMatchesQuery(OM110, "pd-25"))
        assertTrue(ticketMatchesQuery(OM110, "  "))
        assertTrue(ticketMatchesQuery(OM110, "https://acme.atlassian.net/browse/PD-1"))
    }

    @Test
    fun sorting_by_status_puts_what_moves_first_and_keeps_the_hub_order_within() {
        val listed = listOf(OM110, PD2000, PD2592, PD2223, t(9, "X-1"))
        assertEquals(listOf("PD-2592", "PD-2223", "OM-110", "PD-2000", "X-1"), sortTickets(listed, TicketSort.STATUS).map { it.key })
        assertEquals(listOf("PD-2592", "PD-2223", "OM-110", "PD-2000", "X-1"), sortTickets(listed, TicketSort.UPDATED).map { it.key })
        assertEquals(listed, sortTickets(listed, TicketSort.TRACKER))
    }

    @Test
    fun the_sort_chip_cycles_through_every_order() {
        assertEquals(TicketSort.STATUS, TicketSort.TRACKER.next)
        assertEquals(TicketSort.UPDATED, TicketSort.STATUS.next)
        assertEquals(TicketSort.TRACKER, TicketSort.UPDATED.next)
    }

    @Test
    fun column_choices_are_in_workflow_order_one_per_name() {
        val listed = listOf(PD2000, PD2223, PD2592, OM110, t(9, "X-1", name = "code review", category = StatusCategory.InProgress))
        assertEquals(listOf("Backlog", "Code Review", "In Progress", "Done"), ticketStatusNames(listed))
    }

    @Test
    fun facets_read_like_the_sessions_list_and_skip_what_narrows_nothing() {
        val f = TicketFilters(
            lists = setOf(TicketList.RECENT, TicketList.MINE),
            statuses = setOf(WorkStatusFilter.IN_PROGRESS),
            statusNames = setOf("Code Review"),
            org = 1,
            tracker = 8,
            session = TicketSessionFilter.NONE,
        )
        val labels = ticketFacets(f, "pd", orgName = { "Acme" }, trackerName = { "Jira" }).map { it.label }
        assertEquals(
            listOf("List: My work/Recent", "Status: In progress/Code Review", "Org: Acme", "Tracker: Jira", "No session", "Search: “pd”"),
            labels,
        )
        assertEquals(listOf("Column: Code Review"), ticketFacets(TicketFilters(statusNames = setOf("Code Review"))).map { it.label })
        assertEquals(emptyList(), ticketFacets(TicketFilters(lists = TicketList.entries.toSet()), "https://x/y"))
    }

    @Test
    fun section_titles_and_empty_lines_say_what_the_filters_hide() {
        assertEquals("My work · 3", sectionTitle(TicketSection("mine", "My work", listOf(OM110, PD2592, PD2223))))
        assertEquals("My work · 1 of 3", sectionTitle(TicketSection("mine", "My work", listOf(OM110), total = 3)))
        assertEquals("My work", sectionTitle(TicketSection("mine", "My work", emptyList())))
        assertEquals("Nothing here.", emptySectionText(TicketSection("mine", "My work", emptyList())))
        assertEquals("2 hidden by filters.", emptySectionText(TicketSection("mine", "My work", emptyList(), total = 2)))
        assertEquals("Show all 4", showTicketsLabel(4, 4, filtered = false))
        assertEquals("Show 1 ticket", showTicketsLabel(1, 4, filtered = true))
        assertEquals("Show 0 tickets", showTicketsLabel(0, 4, filtered = true))
    }

    // ── The view model ──

    private fun vm(fleet: WorkFleet, actions: FakeWorkActions, scope: CoroutineScope, prefs: FakePrefs = FakePrefs()) =
        TicketsViewModel(fleet, actions, scope, canWrite = true, onOpenSession = {}, onStartHere = {}, prefs = prefs)

    private fun actions() = FakeWorkActions().apply {
        ticketsAnswer = mapOf(
            "mine" to listOf(OM110, PD2592, PD2223),
            "sprint" to listOf(PD2592, PD2000),
            "recent" to listOf(PD2000),
        )
    }

    private fun live5() = SessionRow(id = 5, tmuxName = "s5", hostAlias = "pine")

    @Test
    fun filters_narrow_every_section_and_count_distinct_tickets() = runTest {
        val tickets = vm(WorkFleet(listOf(live5())), actions(), backgroundScope)
        tickets.open()
        runCurrent()
        assertEquals(4, tickets.state.value.total)
        assertEquals(4, tickets.state.value.shown)

        tickets.toggleStatus(WorkStatusFilter.IN_PROGRESS)
        runCurrent()
        val s = tickets.state.value
        assertEquals(listOf(listOf("PD-2592", "PD-2223"), listOf("PD-2592"), emptyList()), s.sections.map { sec -> sec.tickets.map { it.key } })
        assertEquals(listOf(3, 2, 1), s.sections.map { it.total })
        assertEquals(2, s.shown, "PD-2592 is in two lists and counts once")
        assertEquals(listOf("Status: In progress"), s.stripFacets.map { it.label })
    }

    @Test
    fun the_session_filter_uses_the_fleet_s_live_rows() = runTest {
        val linked = SessionRow(id = 6, tmuxName = "s6", work = WorkSummary(linkId = 1, itemId = 3, key = "PD-2223"))
        val tickets = vm(WorkFleet(listOf(live5(), linked)), actions(), backgroundScope)
        tickets.open()
        runCurrent()
        tickets.setSession(TicketSessionFilter.LIVE)
        runCurrent()
        assertEquals(listOf("PD-2592", "PD-2223"), tickets.state.value.sections[0].tickets.map { it.key })

        // A listed id the fleet no longer has is not live.
        val gone = vm(WorkFleet(), actions(), backgroundScope)
        gone.open()
        runCurrent()
        gone.setSession(TicketSessionFilter.LIVE)
        runCurrent()
        assertEquals(0, gone.state.value.shown)
        assertTrue(gone.state.value.allFiltered)
    }

    @Test
    fun a_list_chip_picks_that_list_and_all_or_none_is_every_list() = runTest {
        val tickets = vm(WorkFleet(), actions(), backgroundScope)
        tickets.open()
        runCurrent()
        tickets.toggleList(TicketList.RECENT)
        runCurrent()
        assertEquals(listOf("Recent"), tickets.state.value.sections.map { it.title }, "from All, a tap shows that list alone")
        assertEquals(listOf("List: Recent"), tickets.state.value.stripFacets.map { it.label })
        tickets.toggleList(TicketList.MINE)
        runCurrent()
        assertEquals(listOf("My work", "Recent"), tickets.state.value.sections.map { it.title })
        tickets.toggleList(TicketList.SPRINT)
        runCurrent()
        assertFalse(tickets.state.value.filters.any, "all three is All")
        assertEquals(3, tickets.state.value.sections.size)
        tickets.toggleList(TicketList.MINE)
        runCurrent()
        tickets.toggleList(TicketList.MINE)
        runCurrent()
        assertFalse(tickets.state.value.filters.any, "none left is All, not an empty sheet")
    }

    @Test
    fun typing_filters_the_lists_and_the_search_chip_clears_the_field() = runTest {
        val tickets = vm(WorkFleet(), actions(), backgroundScope)
        tickets.open()
        runCurrent()
        tickets.onQuery("om-")
        runCurrent()
        assertEquals(1, tickets.state.value.shown)
        assertEquals(listOf(TicketFacetId.SEARCH), tickets.state.value.facets.map { it.id })
        assertEquals(emptyList(), tickets.state.value.stripFacets, "the field shows its own text")
        tickets.clearFacet(TicketFacetId.SEARCH)
        runCurrent()
        assertEquals("", tickets.state.value.query)
        assertEquals(4, tickets.state.value.shown)
    }

    @Test
    fun clear_all_brings_every_ticket_back() = runTest {
        val tickets = vm(WorkFleet(), actions(), backgroundScope)
        tickets.open()
        runCurrent()
        tickets.toggleStatus(WorkStatusFilter.DONE)
        runCurrent()
        tickets.setTracker(7)
        runCurrent()
        tickets.onQuery("pd")
        runCurrent()
        assertEquals(0, tickets.state.value.shown)
        tickets.clearAll()
        runCurrent()
        assertEquals(TicketFilters(), tickets.state.value.filters)
        assertEquals("", tickets.state.value.query)
        assertEquals(4, tickets.state.value.shown)
    }

    @Test
    fun filters_and_sort_are_remembered_across_opens_and_launches_but_the_query_is_not() = runTest {
        val prefs = FakePrefs()
        val first = vm(WorkFleet(), actions(), backgroundScope, prefs)
        first.open()
        runCurrent()
        first.toggleStatusName("Code Review")
        runCurrent()
        first.cycleSort()
        runCurrent()
        first.onQuery("pd")
        runCurrent()
        first.close()
        runCurrent()
        assertEquals(setOf("Code Review"), first.state.value.filters.statusNames, "closing keeps the filters")
        assertEquals("", first.state.value.query)

        val second = vm(WorkFleet(), actions(), backgroundScope, prefs)
        assertEquals(setOf("Code Review"), second.state.value.filters.statusNames)
        assertEquals(TicketSort.STATUS, second.state.value.sort)
    }

    @Test
    fun an_unreadable_stored_filter_is_no_filter() = runTest {
        val prefs = FakePrefs().apply { putStringList("tickets.filters.v1", listOf("{not json")) }
        assertEquals(TicketFilters(), vm(WorkFleet(), actions(), backgroundScope, prefs).state.value.filters)
    }

    @Test
    fun sort_orders_each_section() = runTest {
        val tickets = vm(WorkFleet(), actions(), backgroundScope)
        tickets.open()
        runCurrent()
        tickets.cycleSort()
        runCurrent()
        assertEquals(listOf("PD-2592", "PD-2223", "OM-110"), tickets.state.value.sections[0].tickets.map { it.key })
        tickets.cycleSort()
        runCurrent()
        assertEquals(TicketSort.UPDATED, tickets.state.value.sort)
        assertEquals(listOf("PD-2592", "PD-2223", "OM-110"), tickets.state.value.sections[0].tickets.map { it.key })
        assertTrue(tickets.state.value.stripFacets.isEmpty(), "a sort is not a filter")
    }

    @Test
    fun org_and_tracker_choices_appear_only_when_there_is_a_choice() = runTest {
        val fleet = WorkFleet()
        fleet.trackers.value = listOf(TrackerRow(id = 7, provider = "jira", name = "Qomora"), TrackerRow(id = 8, provider = "jira", name = ""))
        val tickets = vm(fleet, actions(), backgroundScope)
        tickets.open()
        runCurrent()
        assertEquals(emptyList(), tickets.state.value.orgChoices, "one org or none: nothing to choose")
        assertEquals(listOf("jira", "Qomora"), tickets.state.value.trackerChoices.map { trackerName(it) })

        fleet.orgs.value = OrgDirectory.of(
            listOf(OrgDetail(1, "Acme", trackers = listOf(OrgTracker(7))), OrgDetail(2, "Papaya", trackers = listOf(OrgTracker(8)))),
        )
        runCurrent()
        assertEquals(listOf("Acme", "Papaya"), tickets.state.value.orgChoices.map { it.name })
        tickets.setOrg(1)
        runCurrent()
        assertEquals(listOf("OM-110"), tickets.state.value.sections[0].tickets.map { it.key })
        assertEquals(listOf("Org: Acme"), tickets.state.value.stripFacets.map { it.label })
    }

    @Test
    fun the_filter_page_opens_and_closes_and_a_fresh_open_starts_on_the_lists() = runTest {
        val tickets = vm(WorkFleet(), actions(), backgroundScope)
        tickets.open()
        runCurrent()
        tickets.openFilters()
        runCurrent()
        assertTrue(tickets.state.value.filtersOpen)
        tickets.closeFilters()
        runCurrent()
        assertFalse(tickets.state.value.filtersOpen)
        tickets.openFilters()
        runCurrent()
        tickets.close()
        runCurrent()
        tickets.open()
        runCurrent()
        assertFalse(tickets.state.value.filtersOpen)
    }
}
