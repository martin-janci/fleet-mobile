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
import dev.claudefleet.mobile.model.SessionFilters
import dev.claudefleet.mobile.ui.GroupMode
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
 * three lost the race was clipped away. Wrapping fixed that and then the row
 * spent the room it bought — a host token, *My work*, and one chip per
 * organisation — so the second failure this file guards is the header growing
 * back. The chip row is capped at three by construction now; the rest is in
 * the sheet *Filters* opens, and the summary line names it.
 *
 * The width is pinned at 320dp so the assertion does not depend on which
 * device runs it.
 */
@RunWith(AndroidJUnit4::class)
class SessionsFilterLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(state: SessionsUiState) {
        compose.setContent {
            FleetTheme {
                Box(Modifier.width(NARROW)) { SessionsScreen(state = state) }
            }
        }
    }

    /**
     * `assertIsDisplayed` is satisfied by a single visible pixel, which a chip
     * running off the right edge still has. Each control has to be *whole*, so
     * its right edge is checked against the width too.
     */
    private fun assertWhole(vararg texts: String) {
        for (text in texts) {
            compose.onNodeWithText(text, substring = true).assertIsDisplayed()
            val right = compose.onNodeWithText(text, substring = true).getBoundsInRoot().right
            assertTrue("\"$text\" is cut off: its right edge is $right in a ${NARROW.value.toInt()}dp screen", right <= NARROW)
        }
    }

    @Test
    fun the_title_and_the_chips_fit_a_narrow_screen() {
        show(
            SessionsUiState(
                status = ConnectionStatus.Connected(hubVersion = null),
                attentionCount = 12,
            ),
        )

        compose.onNodeWithText("Sessions").assertIsDisplayed()
        assertWhole("Needs attention", "Filters")
    }

    /**
     * The widest the row ever gets, and the reason the other filters moved
     * into a sheet: three chips, one of them carrying a count.
     *
     * This test used to assert four — *Needs attention*, *By work*, *My work*
     * and a `host:` token — and the list they were filtering had a four-line
     * header above it on a 320dp screen. Three is now the cap by construction:
     * *My work*, the host and the organisations live in the sheet that
     * *Filters* opens, and what is on is named on the line below rather than
     * held as one chip each.
     */
    @Test
    fun the_widest_the_row_gets_is_three_chips_and_they_wrap_rather_than_clip() {
        show(
            SessionsUiState(
                status = ConnectionStatus.Connected(hubVersion = null),
                filters = SessionFilters(hostFilter = "mefistos-builder", myWorkOnly = true),
                attentionCount = 12,
                workAvailable = true,
                groupMode = GroupMode.WORK,
                myWorkAvailable = true,
            ),
        )

        assertWhole("Needs attention", "Filters · 2", "Group: work")
    }

    /**
     * What is filtering the list is named under the chips, with the counts —
     * the line that answers "where did my session go" now that the controls
     * are behind a sheet. A long alias is ellipsized rather than allowed to
     * push the *Clear* button off the edge.
     */
    @Test
    fun the_summary_line_names_the_active_filters_and_fits() {
        show(
            SessionsUiState(
                status = ConnectionStatus.Connected(hubVersion = null),
                filters = SessionFilters(hostFilter = "mefistos-builder", needsAttentionOnly = true),
                shown = 3,
                total = 87,
            ),
        )

        compose.onNodeWithText("3 of 87", substring = true).assertIsDisplayed()
        assertWhole("Clear")
    }

    /** Pinned so the assertion does not depend on which device runs it. */
    private companion object {
        val NARROW = 320.dp
    }
}
