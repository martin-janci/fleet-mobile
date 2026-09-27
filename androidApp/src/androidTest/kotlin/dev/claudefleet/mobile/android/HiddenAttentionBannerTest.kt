package dev.claudefleet.mobile.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.ui.SessionsHandlers
import dev.claudefleet.mobile.ui.SessionsScreen
import dev.claudefleet.mobile.ui.SessionsUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The warning that pays for remembering filters between launches.
 *
 * `SessionsHiddenAttentionTest` pins the *count*; this pins that a person
 * actually sees it and can act on it in one tap. The two are different claims
 * and only one of them can be made off a device.
 */
@RunWith(AndroidJUnit4::class)
class HiddenAttentionBannerTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(hidden: Int, handlers: SessionsHandlers = SessionsHandlers()) {
        compose.setContent {
            FleetTheme {
                SessionsScreen(
                    state = SessionsUiState(
                        status = ConnectionStatus.Connected(hubVersion = "0.2.29"),
                        hiddenAttention = hidden,
                    ),
                    handlers = handlers,
                )
            }
        }
    }

    @Test
    fun nothing_hidden_draws_no_warning() {
        show(hidden = 0)

        compose.onNodeWithText("Clear").assertDoesNotExist()
    }

    /** The count is in the sentence, because "some sessions" is not actionable. */
    @Test
    fun the_warning_names_how_many_are_waiting() {
        show(hidden = 3)

        compose.onNodeWithText("3 sessions are waiting on you and are hidden by these filters.").assertIsDisplayed()
    }

    /** One is singular. A banner that says "1 sessions are" is a banner nobody trusts. */
    @Test
    fun one_hidden_session_reads_as_one() {
        show(hidden = 1)

        compose.onNodeWithText("1 session is waiting on you and is hidden by these filters.").assertIsDisplayed()
    }

    @Test
    fun clearing_from_the_warning_clears_every_filter() {
        var cleared = 0
        show(hidden = 2, handlers = SessionsHandlers(onClearAll = { cleared += 1 }))

        compose.onNodeWithText("Clear").performClick()

        assertEquals(1, cleared)
    }
}
