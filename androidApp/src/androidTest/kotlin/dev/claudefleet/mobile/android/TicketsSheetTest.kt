package dev.claudefleet.mobile.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.claudefleet.mobile.model.StatusCategory
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.TicketFacetId
import dev.claudefleet.mobile.model.TicketFilters
import dev.claudefleet.mobile.model.WorkStatusFilter
import dev.claudefleet.mobile.model.ticketFacets
import dev.claudefleet.mobile.ui.Friendly
import dev.claudefleet.mobile.ui.TicketSection
import dev.claudefleet.mobile.ui.TicketsBody
import dev.claudefleet.mobile.ui.TicketsHandlers
import dev.claudefleet.mobile.ui.TicketsUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the Tickets sheet's FILTER PAGE draws.
 *
 * The page is a sub-page inside the one `ModalBottomSheet`, so everything on
 * the lists page — the error banner, the progress bar, the facet strip holding
 * the search — is removed from the composition while it is open. That made it
 * the one screen in the app where a failed read said nothing at all and a
 * number was driven by a cause the person could not see.
 *
 * `TicketsBody`, not `TicketsSheet`: the sheet draws into a window of its own,
 * which the test tree cannot reach.
 */
class TicketsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val om110 = Ticket(
        id = 1,
        key = "OM-110",
        title = "Harmonization API",
        statusCategory = StatusCategory.Todo,
        statusName = "Backlog",
        trackerId = 7,
    )

    private fun state(
        filtersOpen: Boolean,
        query: String = "",
        filters: TicketFilters = TicketFilters(),
        error: Friendly? = null,
        loading: Boolean = false,
    ): TicketsUiState {
        val sections = listOf(
            TicketSection("mine", "My work", listOf(om110)),
            TicketSection("sprint", "Current sprint", emptyList()),
        )
        return TicketsUiState(
            available = true,
            open = true,
            filtersOpen = filtersOpen,
            sections = sections,
            query = query,
            filters = filters,
            error = error,
            loading = loading,
            facets = ticketFacets(filters, query),
            shown = 1,
            total = 1,
        )
    }

    private fun show(state: TicketsUiState) {
        compose.setContent { FleetTheme { TicketsBody(state, TicketsHandlers()) } }
        compose.waitForIdle()
    }

    /**
     * A read that failed while the filters were open says so, on the page the
     * person is actually looking at.
     */
    @Test
    fun the_filter_page_shows_an_error_and_the_query_behind_it() {
        show(
            state(
                filtersOpen = true,
                query = "login",
                error = Friendly("Couldn't read the tickets", "Couldn't reach the hub.", isError = true),
            ),
        )

        compose.onNodeWithText("Filters").assertIsDisplayed()
        compose.onNodeWithText("Couldn't reach the hub.", substring = true).assertIsDisplayed()
        // The query counts towards this page's numbers and its Clear all, so it
        // has to be ON this page — and removable, in the same chip the lists
        // page draws it in. `FilterStrip` collapses a chip into ONE semantics
        // node named as what the tap does, so this is a content description.
        compose.onNodeWithContentDescription("Search: \u201Clogin\u201D. Remove filter").assertIsDisplayed()
    }

    /**
     * And the button counts what this page's own filters leave, not what the
     * search leaves: with a query typed and no filter set, "Show all 1" is the
     * truth of the filter page.
     */
    @Test
    fun the_pages_button_reads_the_filters_not_the_search() {
        show(state(filtersOpen = true, query = "om"))

        compose.onNodeWithText("Show all 1").assertIsDisplayed()
    }

    /** With a filter on, it says how many that filter leaves. */
    @Test
    fun a_filter_makes_the_button_name_what_it_leaves() {
        show(
            state(
                filtersOpen = true,
                filters = TicketFilters(statuses = setOf(WorkStatusFilter.TODO)),
            ),
        )

        compose.onNodeWithText("Show 1 ticket").assertIsDisplayed()
        assertTrue(
            "a filter is on, so this is not all of them",
            compose.onAllNodesWithText("Show all 1").fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * A section that came back FULL says there may be more — the hub caps a
     * listing and sends no truncation signal of its own.
     */
    @Test
    fun a_capped_section_says_there_may_be_more() {
        val capped = TicketSection("mine", "My work", listOf(om110), total = 200, capped = true)
        show(
            TicketsUiState(
                available = true,
                open = true,
                sections = listOf(capped),
                facets = emptyList(),
                shown = 1,
                total = 200,
            ),
        )

        compose.onNodeWithText("My work · 1 of 200+", substring = true).assertIsDisplayed()
    }

    /** The lists page is unaffected: its own banner and strip are still there. */
    @Test
    fun the_lists_page_still_draws_its_own_error_and_strip() {
        show(
            state(
                filtersOpen = false,
                query = "login",
                error = Friendly("Couldn't read the tickets", "Couldn't reach the hub.", isError = true),
            ),
        )

        compose.onNodeWithText("Tickets").assertIsDisplayed()
        compose.onNodeWithText("Couldn't reach the hub.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("OM-110", substring = true).assertIsDisplayed()
        // The field holds the text on this page, so the chip is not repeated —
        // `stripFacets` leaves out every facet with a control of its own.
        assertTrue(
            "the lists page shows the query in its field, not as a chip",
            compose.onAllNodesWithContentDescription("Search: \u201Clogin\u201D. Remove filter")
                .fetchSemanticsNodes().isEmpty(),
        )
    }
}
