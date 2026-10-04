package dev.claudefleet.mobile.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.claudefleet.mobile.model.AccountRow
import dev.claudefleet.mobile.model.SessionUsage
import dev.claudefleet.mobile.model.UsageReport
import dev.claudefleet.mobile.model.UsageTotals
import dev.claudefleet.mobile.ui.UsageHandlers
import dev.claudefleet.mobile.ui.UsageScreen
import dev.claudefleet.mobile.ui.UsageUiState
import dev.claudefleet.mobile.ui.UsageWindow
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The Usage screen, drawn: the total, a window chip, a session a tap away, an account. */
class UsageScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun the_report_draws_and_its_parts_are_a_tap_away() {
        val windows = mutableListOf<UsageWindow>()
        val opened = mutableListOf<Long>()
        val state = UsageUiState(
            available = true,
            accountsAvailable = true,
            report = UsageReport(
                total = UsageTotals(costMicros = 12_340_000, outputTokens = 1_500),
                byHost = mapOf("pine" to UsageTotals(costMicros = 12_340_000)),
                sessions = listOf(SessionUsage(sessionId = 9, tmuxName = "s9", friendlyName = "the costly one", costMicros = 12_340_000)),
            ),
            accounts = listOf(AccountRow(uuid = "u", nickname = "work account")),
        )
        compose.setContent {
            FleetTheme {
                UsageScreen(state, UsageHandlers(onSelect = { windows += it }, onOpenSession = { opened += it }), nowSeconds = 0)
            }
        }
        compose.onNodeWithText("$12.34").assertExists()
        compose.onNodeWithText("24 h").performClick()
        assertEquals(listOf(UsageWindow.Day), windows)
        compose.onNodeWithText("the costly one").performClick()
        assertEquals(listOf(9L), opened)
        compose.onNodeWithText("work account").assertExists()
    }
}
