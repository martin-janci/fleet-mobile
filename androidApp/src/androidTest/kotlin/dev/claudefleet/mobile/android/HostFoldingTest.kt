package dev.claudefleet.mobile.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.HostGroup
import dev.claudefleet.mobile.ui.ProjectGroup
import dev.claudefleet.mobile.ui.SessionsHandlers
import dev.claudefleet.mobile.ui.SessionsScreen
import dev.claudefleet.mobile.ui.SessionsUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Folding a host, on a device, which is the only place the claim means
 * anything.
 *
 * `SessionsViewModel` decides *whether* a host is folded and `CollapsingAHostTest`
 * pins that; what it cannot know is whether the rows then leave the screen,
 * because folding here is a drawing decision — `HostGroup.projects` still holds
 * every row, and `SessionsScreen` is what declines to emit them. A `LazyColumn`
 * that emitted them anyway would satisfy every assertion in the view model's
 * suite and show the person exactly what they had just folded away.
 */
@RunWith(AndroidJUnit4::class)
class HostFoldingTest {

    @get:Rule
    val compose = createComposeRule()

    private fun state(collapsed: Boolean) = SessionsUiState(
        groups = listOf(
            HostGroup(
                alias = "mefistos",
                reachable = true,
                collapsed = collapsed,
                projects = listOf(
                    ProjectGroup(
                        projectId = 3,
                        label = "fleet-mobile",
                        sessions = listOf(
                            SessionRow(
                                id = 1,
                                tmuxName = "sess-1",
                                friendlyName = "the folded one",
                                hostAlias = "mefistos",
                                claudeStatus = "working",
                            ),
                        ),
                    ),
                ),
            ),
        ),
        status = ConnectionStatus.Connected(hubVersion = "0.2.29"),
        nowSeconds = 1_758_153_600,
    )

    private fun show(collapsed: Boolean, handlers: SessionsHandlers = SessionsHandlers()) {
        compose.setContent { FleetTheme { SessionsScreen(state = state(collapsed), handlers = handlers) } }
    }

    @Test
    fun an_open_host_draws_its_project_and_its_rows() {
        show(collapsed = false)

        compose.onNodeWithText("mefistos").assertIsDisplayed()
        compose.onNodeWithText("fleet-mobile").assertIsDisplayed()
        compose.onNodeWithText("the folded one").assertIsDisplayed()
    }

    /**
     * The heading and the count stay; everything under them goes.
     *
     * The count is asserted too, and not only as decoration: it is built from
     * `projects`, which a folded group still carries, and emptying that list
     * instead of skipping the items would read "0 sessions" — the one number
     * that would stop anyone unfolding it again.
     */
    @Test
    fun a_folded_host_keeps_its_heading_and_count_and_draws_nothing_else() {
        show(collapsed = true)

        compose.onNodeWithText("mefistos").assertIsDisplayed()
        compose.onNodeWithText("1 session").assertIsDisplayed()
        compose.onNodeWithText("fleet-mobile").assertDoesNotExist()
        compose.onNodeWithText("the folded one").assertDoesNotExist()
    }

    /** And the heading is the thing you tap, naming the host it would fold. */
    @Test
    fun tapping_the_heading_reports_the_host() {
        val folded = mutableListOf<String>()
        show(collapsed = false, handlers = SessionsHandlers(onToggleHost = { folded += it }))

        compose.onNodeWithText("mefistos").performClick()

        assertEquals(listOf("mefistos"), folded)
    }
}
