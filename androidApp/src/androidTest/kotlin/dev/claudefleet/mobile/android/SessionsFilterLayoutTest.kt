package dev.claudefleet.mobile.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.ui.SessionsScreen
import dev.claudefleet.mobile.ui.SessionsUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The filters have to survive a narrow screen.
 *
 * They used to sit in the `TopAppBar`'s `actions`, a `Row` that is measured
 * before the title and neither wraps nor scrolls: on a phone, a host filter
 * next to the "Needs attention" chip overflowed the bar, and whichever of the
 * three lost the race was clipped away. The width here is pinned at 320dp so
 * the assertion does not depend on which device runs it.
 */
@RunWith(AndroidJUnit4::class)
class SessionsFilterLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun the_title_and_both_filters_fit_a_narrow_screen() {
        compose.setContent {
            FleetTheme {
                Box(Modifier.width(320.dp)) {
                    SessionsScreen(
                        state = SessionsUiState(
                            status = ConnectionStatus.Connected(hubVersion = null),
                            needsAttentionOnly = false,
                            // A long-but-plausible alias: the filter is set by
                            // tapping a host, and hosts are named by people.
                            hostFilter = "mefistos-builder",
                            attentionCount = 12,
                        ),
                        onOpenSession = {},
                        onToggleNeedsAttention = {},
                        onClearHostFilter = {},
                        onRefresh = {},
                        onDismissError = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Sessions").assertIsDisplayed()
        compose.onNodeWithText("Needs attention").assertIsDisplayed()
        compose.onNodeWithText("host: mefistos-builder").assertIsDisplayed()

        // `assertIsDisplayed` is satisfied by a single visible pixel, which a
        // chip running off the right edge still has. The filters have to be
        // whole, so each one's right edge is checked against the width.
        for (text in listOf("Needs attention", "host: mefistos-builder")) {
            val right = compose.onNodeWithText(text).getBoundsInRoot().right
            assertTrue("\"$text\" is cut off: its right edge is $right in a 320dp screen", right <= 320.dp)
        }
    }

    /**
     * The work graph adds two chips (M8.2). All four at once is the widest the
     * row gets — a hub with a tracker, By work on, and a host filter — and on
     * 320dp that has to wrap onto lines rather than run off the edge.
     */
    @Test
    fun the_work_chips_wrap_rather_than_clip_on_a_narrow_screen() {
        compose.setContent {
            FleetTheme {
                Box(Modifier.width(320.dp)) {
                    SessionsScreen(
                        state = SessionsUiState(
                            status = ConnectionStatus.Connected(hubVersion = null),
                            hostFilter = "mefistos-builder",
                            attentionCount = 12,
                            workAvailable = true,
                            byWork = true,
                            myWorkAvailable = true,
                        ),
                        onOpenSession = {},
                        onToggleNeedsAttention = {},
                        onClearHostFilter = {},
                        onRefresh = {},
                        onDismissError = {},
                    )
                }
            }
        }

        for (text in listOf("Needs attention", "By work", "My work", "host: mefistos-builder")) {
            compose.onNodeWithText(text).assertIsDisplayed()
            val right = compose.onNodeWithText(text).getBoundsInRoot().right
            assertTrue("\"$text\" is cut off: its right edge is $right in a 320dp screen", right <= 320.dp)
        }
        compose.onNodeWithText("Sessions").assertIsDisplayed()
    }
}
