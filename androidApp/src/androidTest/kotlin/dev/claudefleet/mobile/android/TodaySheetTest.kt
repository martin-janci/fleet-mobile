package dev.claudefleet.mobile.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudefleet.mobile.model.StatusCategory
import dev.claudefleet.mobile.model.TodayFilters
import dev.claudefleet.mobile.model.TodayGroup
import dev.claudefleet.mobile.model.TodaySession
import dev.claudefleet.mobile.model.TodayView
import dev.claudefleet.mobile.ui.TodayHandlers
import dev.claudefleet.mobile.ui.TodayBody
import dev.claudefleet.mobile.ui.TodayUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the Today sheet actually DRAWS.
 *
 * Every other test of this screen is over the view model, and that is why a
 * whole renderer could go missing without one failing: the group's status pill
 * read only `status_name`, which the hub leaves NULL for every local work item,
 * and the base's independent category renderer had been deleted — so the status
 * of exactly the work this sheet is mostly about was drawn nowhere, with a
 * green suite. A composed test is the one thing that would have caught it.
 *
 * It also covers the lazy layout the same fix needed: the sheet composes one
 * item per GROUP now (it used to compose one per section, and every group and
 * session row inside it, against a dimension nothing caps), so a long section's
 * first rows have to be on screen and the whole of it must not be.
 */
@RunWith(AndroidJUnit4::class)
class TodaySheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun session(id: Long, name: String) =
        TodaySession(id = id, name = name, hostAlias = "pine")

    private fun group(key: String?, title: String, category: StatusCategory?, name: String?, bucket: String) =
        TodayGroup(
            bucket = bucket,
            key = key,
            title = title,
            statusCategory = category,
            statusName = name,
            sessions = listOf(session(key.hashCode().toLong(), "s-${key ?: "none"}")),
        )

    private fun show(view: TodayView) {
        compose.setContent {
            FleetTheme {
                // `TodayBody`, not `TodaySheet`: a `ModalBottomSheet` draws into
                // a window of its own, which the test tree cannot reach. The
                // body is everything the sheet holds.
                TodayBody(
                    state = TodayUiState(available = true, open = true, loaded = true, view = view, shown = view),
                    handlers = TodayHandlers(),
                )
            }
        }
    }

    /**
     * A tracker ticket names its own status; LOCAL work has only a category,
     * and it is drawn in the same words the standup and the work chip use.
     */
    @Test
    fun a_groups_status_is_drawn_for_local_work_too() {
        show(
            TodayView(
                inProgress = listOf(
                    group("PAY-9", "Ledger", StatusCategory.InProgress, "Code Review", "in_progress"),
                    group("TASK-4", "Rework the tick", StatusCategory.InProgress, null, "in_progress"),
                ),
            ),
        )

        compose.onNodeWithText("PAY-9 Ledger", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Code Review").assertIsDisplayed()
        // The local one: no `status_name`, and a status all the same.
        compose.onNodeWithText("TASK-4 Rework the tick", substring = true).assertIsDisplayed()
        compose.onNodeWithText("in progress").assertIsDisplayed()
    }

    /** A category a later hub adds names nothing, and nothing is drawn for it. */
    @Test
    fun an_unknown_category_draws_no_pill() {
        show(
            TodayView(
                inProgress = listOf(group("TASK-5", "Unmapped", StatusCategory.Unknown, null, "in_progress")),
            ),
        )

        compose.onNodeWithText("TASK-5 Unmapped", substring = true).assertIsDisplayed()
        assertTrue(
            "an Unknown category must name nothing",
            compose.onAllNodesWithText("to do").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("in progress").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("done").fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * A session's screen-reader node names the WORK it sits under.
     *
     * The node said name, host and reason, which outside the visual grouping is
     * several sessions of the same shape with no way to tell whose ticket is
     * whose — and this is the one reading of the sheet where the grouping is
     * invisible.
     */
    @Test
    fun a_session_is_announced_with_the_work_it_belongs_to() {
        show(
            TodayView(
                inProgress = listOf(
                    group("PAY-9", "Ledger", StatusCategory.InProgress, null, "in_progress"),
                    TodayGroup(bucket = "in_progress", key = null, sessions = listOf(session(77, "loose"))),
                ),
            ),
        )

        compose.onNodeWithContentDescription("PAY-9 Ledger, s-PAY-9, on pine. Open session").assertIsDisplayed()
        compose.onNodeWithContentDescription("not linked to a ticket, loose, on pine. Open session").assertIsDisplayed()
    }

    /**
     * Share says it is handing on a FILTERED standup, as Copy does.
     *
     * The word was on one button only — and the one that leaves the phone was
     * the one that said nothing.
     */
    @Test
    fun both_buttons_say_when_the_text_is_filtered() {
        val view = TodayView(inProgress = listOf(group("PAY-9", "Ledger", StatusCategory.InProgress, null, "in_progress")))
        compose.setContent {
            FleetTheme {
                TodayBody(
                    state = TodayUiState(
                        available = true,
                        open = true,
                        loaded = true,
                        view = view,
                        shown = view,
                        filters = TodayFilters(host = "pine"),
                    ),
                    handlers = TodayHandlers(),
                )
            }
        }
        compose.onNodeWithText("Copy filtered").assertIsDisplayed()
        compose.onNodeWithText("Share filtered").assertIsDisplayed()
    }

    /**
     * A section of many groups composes lazily: the top is on screen, the far
     * end is not yet. The hub caps groups at 200 and never truncates a group's
     * sessions, so composing a whole section eagerly had no bound at all.
     */
    @Test
    fun a_long_section_composes_only_what_is_on_screen() {
        val many = (1..120).map {
            group("TASK-$it", "Task number $it", StatusCategory.InProgress, null, "in_progress")
        }
        show(TodayView(inProgress = many))

        compose.onNodeWithText("TASK-1 Task number 1", substring = true).assertIsDisplayed()
        assertTrue(
            "a group far down the section must not be composed before it is reached",
            compose.onAllNodesWithText("TASK-120 Task number 120", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }
}
