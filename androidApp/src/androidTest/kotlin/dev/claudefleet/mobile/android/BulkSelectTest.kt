package dev.claudefleet.mobile.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.BulkUiState
import dev.claudefleet.mobile.ui.HostGroup
import dev.claudefleet.mobile.ui.ProjectGroup
import dev.claudefleet.mobile.ui.SessionsHandlers
import dev.claudefleet.mobile.ui.SessionsScreen
import dev.claudefleet.mobile.ui.SessionsUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The list's multi-select, drawn: a long press picks, a tap then picks too, and the bar offers the bulk actions. */
class BulkSelectTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = SessionsUiState(
        groups = listOf(
            HostGroup(
                alias = "pine",
                reachable = true,
                projects = listOf(
                    ProjectGroup(
                        projectId = 1,
                        label = "app",
                        sessions = listOf(
                            SessionRow(id = 1, tmuxName = "s1", friendlyName = "first", hostAlias = "pine", claudeStatus = "idle"),
                            SessionRow(id = 2, tmuxName = "s2", friendlyName = "second", hostAlias = "pine", claudeStatus = "idle"),
                        ),
                    ),
                ),
            ),
        ),
        status = ConnectionStatus.Connected(hubVersion = "0.9.3"),
    )

    @Test
    fun a_long_press_picks_a_row_instead_of_opening_it() {
        val picked = mutableListOf<Long>()
        val opened = mutableListOf<Long>()
        compose.setContent {
            FleetTheme {
                SessionsScreen(
                    state = state,
                    handlers = SessionsHandlers(onOpenSession = { opened += it }, onToggleSelect = { picked += it }),
                    bulk = BulkUiState(enabled = true),
                )
            }
        }
        compose.onNodeWithText("first").performTouchInput { longClick() }
        assertEquals(listOf(1L), picked)
        assertEquals(emptyList<Long>(), opened)
    }

    @Test
    fun while_rows_are_picked_a_tap_picks_and_the_bar_offers_send_and_kill() {
        val picked = mutableListOf<Long>()
        compose.setContent {
            FleetTheme {
                SessionsScreen(
                    state = state,
                    handlers = SessionsHandlers(onToggleSelect = { picked += it }),
                    bulk = BulkUiState(enabled = true, selected = setOf(1L), killable = 1),
                )
            }
        }
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithText("second").performClick()
        assertEquals(listOf(2L), picked)

        compose.onNodeWithText("Send").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("The same prompt, to each").performClick()
        compose.onNodeWithText("Send to 1 session").assertExists()
    }

    @Test
    fun a_readonly_pairing_has_no_long_press_select() {
        val picked = mutableListOf<Long>()
        val opened = mutableListOf<Long>()
        compose.setContent {
            FleetTheme {
                SessionsScreen(
                    state = state,
                    handlers = SessionsHandlers(onOpenSession = { opened += it }, onToggleSelect = { picked += it }),
                    bulk = BulkUiState(enabled = false),
                )
            }
        }
        compose.onNodeWithText("first").performTouchInput { longClick() }
        assertEquals(emptyList<Long>(), picked)
    }

    @Test
    fun select_is_offered_in_the_header_menu_without_a_long_press() {
        var started = 0
        compose.setContent {
            FleetTheme {
                SessionsScreen(
                    state = state,
                    handlers = SessionsHandlers(onStartSelect = { started++ }),
                    bulk = BulkUiState(enabled = true),
                )
            }
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Select sessions").performClick()
        assertEquals(1, started)
    }

    @Test
    fun select_mode_with_nothing_picked_says_what_to_do_and_a_tap_picks() {
        val picked = mutableListOf<Long>()
        compose.setContent {
            FleetTheme {
                SessionsScreen(
                    state = state,
                    handlers = SessionsHandlers(onToggleSelect = { picked += it }),
                    bulk = BulkUiState(enabled = true, selecting = true),
                )
            }
        }
        compose.onNodeWithText("Tap sessions to pick").assertExists()
        compose.onNodeWithText("first").performClick()
        assertEquals(listOf(1L), picked)
    }
}
