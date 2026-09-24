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
import dev.claudefleet.mobile.ui.Lens
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
 * three lost the race was clipped away. There are six chips now — four lenses,
 * the noise switch and the host — so the `FlowRow` they live in is carrying
 * considerably more than the layout that first broke. The width is pinned at
 * 320dp so the assertion does not depend on which device runs it.
 */
@RunWith(AndroidJUnit4::class)
class SessionsFilterLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val chips = listOf(
        "Needs you",
        "Active",
        "Today",
        "All",
        "bg + shell (14)",
        "host: mefistos-builder",
    )

    @Test
    fun the_title_and_every_filter_fit_a_narrow_screen() {
        compose.setContent {
            FleetTheme {
                Box(Modifier.width(320.dp)) {
                    SessionsScreen(
                        state = SessionsUiState(
                            status = ConnectionStatus.Connected(hubVersion = null),
                            lens = Lens.All,
                            hideNoise = true,
                            hiddenNoise = 14,
                            // A long-but-plausible alias: the filter is set by
                            // tapping a host, and hosts are named by people.
                            hostFilter = "mefistos-builder",
                            attentionCount = 12,
                        ),
                        onOpenSession = {},
                        onLensChange = {},
                        onQueryChange = {},
                        onToggleHideNoise = {},
                        onToggleHost = {},
                        onToggleDormant = {},
                        onClearHostFilter = {},
                        onRefresh = {},
                        onDismissError = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Sessions").assertIsDisplayed()
        for (text in chips) compose.onNodeWithText(text).assertIsDisplayed()

        // `assertIsDisplayed` is satisfied by a single visible pixel, which a
        // chip running off the right edge still has. The filters have to be
        // whole, so each one's right edge is checked against the width.
        for (text in chips) {
            val right = compose.onNodeWithText(text).getBoundsInRoot().right
            assertTrue("\"$text\" is cut off: its right edge is $right in a 320dp screen", right <= 320.dp)
        }
    }

    /**
     * The search box is the widest thing on the screen and the one most likely
     * to push the chips off it, so its placeholder is checked at the same
     * width — a placeholder that is clipped is a search box nobody knows is
     * a search box.
     */
    @Test
    fun the_search_box_fits_too() {
        compose.setContent {
            FleetTheme {
                Box(Modifier.width(320.dp)) {
                    SessionsScreen(
                        state = SessionsUiState(status = ConnectionStatus.Connected(hubVersion = null)),
                        onOpenSession = {},
                        onLensChange = {},
                        onQueryChange = {},
                        onToggleHideNoise = {},
                        onToggleHost = {},
                        onToggleDormant = {},
                        onClearHostFilter = {},
                        onRefresh = {},
                        onDismissError = {},
                    )
                }
            }
        }

        val box = compose.onNodeWithText("Search name, prompt, branch, tag…")
        box.assertIsDisplayed()
        assertTrue("the search box is cut off", box.getBoundsInRoot().right <= 320.dp)
    }
}
