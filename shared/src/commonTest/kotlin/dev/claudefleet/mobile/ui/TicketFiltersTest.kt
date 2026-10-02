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
import dev.claudefleet.mobile.model.isLookupText
import dev.claudefleet.mobile.model.sortTickets
import dev.claudefleet.mobile.model.ticketFacets
import dev.claudefleet.mobile.model.ticketMatchesQuery
import dev.claudefleet.mobile.model.ticketStatusNames
import dev.claudefleet.mobile.net.TICKETS_LIMIT
import dev.claudefleet.mobile.store.FakePrefs
import kotlinx.coroutines.CompletableDeferred
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

    /**
     * Two different tickets, which the suite used to conflate.
     *
     * `t(9, "X-1")` has a NULL `statusCategory`, which a `work tickets`
     * listing cannot produce at this contract — the hub always maps a status
     * to a category. So the only case under test was the defensive branch,
     * while the realistic shape (a category, no tracker column name) behaves
     * the OPPOSITE way on the second question and had no test at all.
     */
    @Test
    fun a_ticket_with_no_status_answers_no_status_question() {
        val bare = t(9, "X-1")
        assertFalse(TicketFilters(statuses = setOf(WorkStatusFilter.TODO)).matches(bare, { null }, { false }))
        assertTrue(TicketFilters().matches(bare, { null }, { false }))
    }

    @Test
    fun a_ticket_with_a_category_and_no_column_name_answers_the_bucket_but_not_the_column() {
        val unnamed = t(9, "X-1", category = StatusCategory.Todo, tracker = 7)
        assertTrue(
            TicketFilters(statuses = setOf(WorkStatusFilter.TODO)).matches(unnamed, { null }, { false }),
            "a bucket is answered by the category, which every listed ticket has",
        )
        assertFalse(
            TicketFilters(statusNames = setOf("Code Review")).matches(unnamed, { null }, { false }),
            "a tracker column is answered by the NAME, which this ticket has none of",
        )
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

    /**
     * STATUS and UPDATED are not the same order.
     *
     * Every fixture in the suite happened to order identically under both, so
     * the two branches of `sortTickets` were indistinguishable to it: either
     * could have been deleted, or the chip wired to the wrong one, with a green
     * suite. This is the fixture where they diverge.
     */
    @Test
    fun status_and_updated_are_different_orders() {
        val moving = t(11, "A-1", category = StatusCategory.InProgress, updated = 50)
        val next = t(12, "B-1", category = StatusCategory.Todo, updated = 400)
        val listed = listOf(moving, next)

        assertEquals(listOf("A-1", "B-1"), sortTickets(listed, TicketSort.STATUS).map { it.key })
        assertEquals(listOf("B-1", "A-1"), sortTickets(listed, TicketSort.UPDATED).map { it.key })
    }

    /** And STATUS is STABLE, so two of equal rank keep the hub's own order. */
    @Test
    fun equal_rank_keeps_the_order_the_hub_listed() {
        val older = t(13, "C-1", category = StatusCategory.InProgress, updated = 10)
        val newer = t(14, "D-1", category = StatusCategory.InProgress, updated = 900)
        assertEquals(
            listOf("D-1", "C-1"),
            sortTickets(listOf(newer, older), TicketSort.STATUS).map { it.key },
            "a stable sort does not reshuffle what it cannot tell apart",
        )
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

    /**
     * The strip counts ROWS, summed per section — what the sheet draws and what
     * a person can count under the three headers right beneath it.
     *
     * The hub's `views_of` makes *Current sprint* and *Recent* subsets of *My
     * work*, so a ticket is routinely in two lists and is drawn in both. These
     * two numbers used to be the only deduped ones in the sheet, so the
     * headline read "2 of 4" directly above headers adding to "3 / 2 / 1" and
     * three drawn rows — and the filter page's "Show N tickets" promised a
     * count pressing it did not deliver.
     */
    @Test
    fun filters_narrow_every_section_and_count_the_rows_the_sheet_draws() = runTest {
        val tickets = vm(WorkFleet(listOf(live5())), actions(), backgroundScope)
        tickets.open()
        runCurrent()
        // Six rows over three sections, from four distinct tickets.
        assertEquals(6, tickets.state.value.total)
        assertEquals(6, tickets.state.value.shown)

        tickets.toggleStatus(WorkStatusFilter.IN_PROGRESS)
        runCurrent()
        val s = tickets.state.value
        assertEquals(listOf(listOf("PD-2592", "PD-2223"), listOf("PD-2592"), emptyList()), s.sections.map { sec -> sec.tickets.map { it.key } })
        assertEquals(listOf(3, 2, 1), s.sections.map { it.total })
        assertEquals(3, s.shown, "the headline is the sum of the headers beneath it")
        assertEquals(6, s.total, "and its denominator is theirs")
        assertEquals(listOf("Status: In progress"), s.stripFacets.map { it.label })
    }

    /** The denominator counts only the lists the sheet is SHOWING. */
    @Test
    fun switching_a_list_off_takes_it_out_of_both_numbers() = runTest {
        val tickets = vm(WorkFleet(), actions(), backgroundScope)
        tickets.open()
        runCurrent()
        tickets.toggleList(TicketList.RECENT)
        runCurrent()
        val s = tickets.state.value
        assertEquals(listOf(1), s.sections.map { it.total }, "only Recent is drawn")
        assertEquals(1, s.shown)
        assertEquals(1, s.total, "not 4: My work and Current sprint are switched off")
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
        assertEquals(6, tickets.state.value.shown)
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
        assertEquals(6, tickets.state.value.shown)
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

    /**
     * The stored keys, by their LITERAL names, with VALID values.
     *
     * The only literal-key case in the suite was a seed of broken JSON, whose
     * assertion ("no filter") is satisfied equally by "parsed and rejected" and
     * by "never read at all" — so a renamed key would have passed it. The
     * round trip used the same two constants on both sides, which cannot catch
     * a rename either. This reads what a previous launch wrote.
     */
    @Test
    fun the_stored_keys_are_read_by_the_names_a_previous_launch_wrote() = runTest {
        val prefs = FakePrefs().apply {
            putStringList("tickets.filters.v1", listOf("""{"statusNames":["Code Review"]}"""))
            putStringList("tickets.sort", listOf("UPDATED"))
        }
        val tickets = vm(WorkFleet(), actions(), backgroundScope, prefs)

        assertEquals(setOf("Code Review"), tickets.state.value.filters.statusNames)
        assertEquals(TicketSort.UPDATED, tickets.state.value.sort)

        // And what it writes back goes under the same name, readably.
        tickets.open()
        runCurrent()
        tickets.toggleStatusName("Backlog")
        runCurrent()
        val written = prefs.getStringList("tickets.filters.v1")?.firstOrNull()
        assertTrue(written != null && "Backlog" in written, "written under its own key: $written")
    }

    /**
     * A remembered filter the lists no longer hold is still offered, and still
     * clearable.
     *
     * Three branches were written for a stale or unreadable stored value and
     * none had a test. A person who filtered by an org or a tracker that has
     * since gone would otherwise be left with a sheet narrowed by something
     * with no chip to turn off.
     */
    @Test
    fun a_stale_stored_org_or_tracker_is_still_offered_and_clearable() = runTest {
        val prefs = FakePrefs().apply {
            putStringList("tickets.filters.v1", listOf("""{"org":99,"tracker":98}"""))
        }
        val tickets = vm(WorkFleet(), actions(), backgroundScope, prefs)
        tickets.open()
        runCurrent()

        val s = tickets.state.value
        assertEquals(99L, s.filters.org)
        assertEquals(98L, s.filters.tracker)
        assertTrue(s.orgChoices.any { it.id == 99L }, "the chosen org must stay on screen: ${s.orgChoices}")
        assertTrue(s.trackerChoices.any { it.id == 98L }, "and the chosen tracker: ${s.trackerChoices}")

        tickets.clearAll()
        runCurrent()
        assertEquals(TicketFilters(), tickets.state.value.filters)
    }

    /** A stored value of the right SHAPE but an unknown name falls back, per facet. */
    @Test
    fun an_unknown_stored_name_falls_back_to_the_default() = runTest {
        val prefs = FakePrefs().apply {
            putStringList("tickets.filters.v1", listOf("""{"session":"BOGUS"}"""))
            putStringList("tickets.sort", listOf("BOGUS"))
        }
        val tickets = vm(WorkFleet(), actions(), backgroundScope, prefs)

        assertEquals(TicketFilters(), tickets.state.value.filters, "an unknown enum name is no filter")
        assertEquals(TicketSort.TRACKER, tickets.state.value.sort)
    }

    /** Every field at once, there and back. */
    @Test
    fun a_fully_populated_filter_survives_a_round_trip() = runTest {
        val prefs = FakePrefs()
        val first = vm(WorkFleet(), actions(), backgroundScope, prefs)
        first.open()
        runCurrent()
        first.toggleList(TicketList.SPRINT)
        first.toggleStatus(WorkStatusFilter.IN_PROGRESS)
        first.toggleStatusName("Code Review")
        first.setOrg(1)
        first.setTracker(8)
        first.setSession(TicketSessionFilter.LIVE)
        runCurrent()
        val saved = first.state.value.filters

        assertEquals(saved, vm(WorkFleet(), actions(), backgroundScope, prefs).state.value.filters)
        assertTrue(saved.any, "and every one of them is a filter")
    }

    /**
     * The filters and the sort read the ticket CACHE, not the listing.
     *
     * `assemble` maps each row through `current()` before the predicate, so a
     * ticket the sync has moved since the sheet was opened is filtered by where
     * it is NOW. No test populated `fleet.tickets`, so `current()` always
     * returned `this` and the whole refresh was dead to the suite.
     */
    @Test
    fun a_ticket_the_cache_has_moved_is_filtered_where_it_is_now() = runTest {
        val fleet = WorkFleet(listOf(live5()))
        val tickets = vm(fleet, actions(), backgroundScope)
        tickets.open()
        runCurrent()

        // OM-110 was listed as Todo; the sync has since moved it.
        tickets.toggleStatus(WorkStatusFilter.IN_PROGRESS)
        runCurrent()
        assertEquals(listOf("PD-2592", "PD-2223"), tickets.state.value.sections[0].tickets.map { it.key })

        fleet.tickets.value = listOf(OM110.copy(statusCategory = StatusCategory.InProgress))
        runCurrent()
        val s = tickets.state.value
        assertEquals(
            listOf("OM-110", "PD-2592", "PD-2223"),
            s.sections[0].tickets.map { it.key },
            "the refreshed ticket answers the filter",
        )
        assertEquals(6, s.total, "the denominator is still what was listed")
    }

    /**
     * A listing that came back FULL may have more behind it, and says so.
     *
     * The hub caps a `work tickets` listing and sends no truncation signal of
     * its own, so a person with 300 tickets in *Recent* read the cap as the
     * whole of their work.
     */
    @Test
    fun a_full_page_is_marked_as_possibly_truncated() = runTest {
        val many = (1..TICKETS_LIMIT).map { t(1000L + it, "Z-$it", category = StatusCategory.Todo) }
        val actions = actions().apply { ticketsAnswer = mapOf("mine" to many, "sprint" to emptyList(), "recent" to emptyList()) }
        val tickets = vm(WorkFleet(), actions, backgroundScope)
        tickets.open()
        runCurrent()

        val sections = tickets.state.value.sections
        assertTrue(sections[0].capped, "a section of exactly the asked-for size may have more")
        assertFalse(sections[1].capped, "an empty one has not")
        assertEquals("My work · $TICKETS_LIMIT+", sectionTitle(sections[0]))
        assertEquals("Current sprint", sectionTitle(sections[1]))
    }

    /**
     * Clearing the search takes with it everything the search put on screen.
     *
     * Resetting `query` and `found` alone left the lookup still in flight — it
     * landed afterwards and re-selected the ticket the person had just
     * dismissed — along with its error banner and its actions row.
     */
    @Test
    fun clearing_the_search_cancels_the_lookup_and_drops_what_it_found() = runTest {
        val actions = actions().apply { lookupGate = CompletableDeferred() }
        val tickets = vm(WorkFleet(), actions, backgroundScope)
        tickets.open()
        runCurrent()
        tickets.onQuery("PAY-7")
        val lookup = tickets.search()
        runCurrent()
        assertTrue(tickets.state.value.busy)

        tickets.clearFacet(TicketFacetId.SEARCH)
        runCurrent()
        // The read itself is stopped, not merely ignored: a lookup is a live
        // tracker fetch on the hub, and leaving it running to discard its
        // answer spends a rate-limited call on a question nobody is asking.
        assertTrue(lookup?.isCancelled == true, "the lookup in flight is cancelled")

        actions.lookupGate!!.complete(Unit)
        runCurrent()
        val s = tickets.state.value
        assertEquals("", s.query)
        assertEquals(null, s.found, "an answer to a question nobody is asking any more")
        assertEquals(null, s.selected)
        assertEquals(null, s.error)
    }

    /** A lookup's error goes with the field too: the lists match perfectly well. */
    @Test
    fun clearing_the_search_clears_its_error() = runTest {
        val actions = actions().apply { failLookup = IllegalStateException("no such key") }
        val tickets = vm(WorkFleet(), actions, backgroundScope)
        tickets.open()
        runCurrent()
        tickets.onQuery("NOPE-1")
        tickets.search()
        runCurrent()
        assertTrue(tickets.state.value.error != null, "the failed lookup says so")

        tickets.clearFacet(TicketFacetId.SEARCH)
        runCurrent()
        assertEquals(null, tickets.state.value.error)
    }

    /**
     * Only text that could BE a ticket is offered to the hub's lookup.
     *
     * The field is a live filter and kept `ImeAction.Search`, so the natural
     * key for dismissing the keyboard sent ordinary filter words out as a key
     * lookup — a live tracker fetch for the word "login".
     */
    @Test
    fun only_a_key_or_a_url_is_lookup_text() {
        assertTrue(isLookupText("PAY-9"))
        assertTrue(isLookupText("OM-110"))
        assertTrue(isLookupText("  pd-2592  "))
        assertTrue(isLookupText("https://acme.atlassian.net/browse/PD-1"))

        assertFalse(isLookupText("login"))
        assertFalse(isLookupText("support access"))
        assertFalse(isLookupText(""))
        assertFalse(isLookupText("   "))
        assertFalse(isLookupText("PAY-"), "a prefix with no number is not a key yet")
        assertFalse(isLookupText("-9"))
        assertFalse(isLookupText("fix the API-ish thing"))
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
