package dev.claudefleet.mobile.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.SessionFacetId
import dev.claudefleet.mobile.model.SessionFilters
import dev.claudefleet.mobile.model.StatusFilter
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.GroupMode
import dev.claudefleet.mobile.ui.HostGroup
import dev.claudefleet.mobile.ui.ProjectGroup
import dev.claudefleet.mobile.ui.SessionsHandlers
import dev.claudefleet.mobile.ui.SessionsScreen
import dev.claudefleet.mobile.ui.SessionsUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The filters have to survive a narrow screen.
 *
 * They used to sit in the `TopAppBar`'s `actions`, a `Row` that is measured
 * before the title and neither wraps nor scrolls: on a phone, a host filter
 * next to the "Needs you" chip overflowed the bar, and whichever of the
 * three lost the race was clipped away. Wrapping fixed that and then the row
 * spent the room it bought — a host token, *My work*, and one chip per
 * organisation — so the second failure this file guards is the header growing
 * back. The chip row is capped at three by construction now; the rest is in
 * the sheet *Filters (n)* opens, and the strip under it names each one with
 * its own ✕ — claude-fleet's filter model (2026-09-28 sidebar cleanup).
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
        assertWhole("Needs you", "Filters")
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

        assertWhole("Needs you", "Filters (2)", "Group: work")
    }

    /**
     * What is filtering the list is a strip of chips under the controls, with
     * the counts first — what answers "where did my session go" now that the
     * controls are behind a sheet. The chips scroll sideways, so a long alias
     * cannot push *Clear all* off the edge.
     */
    @Test
    fun the_strip_names_the_active_filters_and_fits() {
        // With rows on screen, because "3 of 87" over an empty list is a state
        // the view model cannot produce. The first version of this test left
        // `groups` empty, so the empty state drew its own *Clear all filters*
        // beside the summary's *Clear* and the substring matcher found two
        // nodes — a test failing on a screen it had built wrong, not on a bug.
        show(
            SessionsUiState(
                status = ConnectionStatus.Connected(hubVersion = null),
                groups = listOf(
                    HostGroup(
                        alias = "mefistos-builder",
                        reachable = true,
                        projects = listOf(
                            ProjectGroup(
                                projectId = 1,
                                label = "martin-janci/fleet-mobile",
                                sessions = (1L..3L).map { SessionRow(id = it, tmuxName = "sess-$it") },
                            ),
                        ),
                    ),
                ),
                filters = SessionFilters(hostFilter = "mefistos-builder", needsAttentionOnly = true),
                shown = 3,
                total = 87,
            ),
        )

        compose.onNodeWithText("3 of 87", substring = true).assertIsDisplayed()
        assertWhole("Clear all")
        // Needs you has its own chip on screen, so the strip names only the host.
        compose.onNodeWithContentDescription("Host: mefistos-builder. Remove filter").assertIsDisplayed()
        compose.onNodeWithContentDescription("Needs you. Remove filter").assertDoesNotExist()
    }

    /** A chip's ✕ clears that one filter — the host through the handler the navigator is behind. */
    @Test
    fun a_chip_removes_only_its_own_filter() {
        val cleared = mutableListOf<SessionFacetId>()
        compose.setContent {
            FleetTheme {
                Box(Modifier.width(NARROW)) {
                    SessionsScreen(
                        state = SessionsUiState(
                            status = ConnectionStatus.Connected(hubVersion = null),
                            filters = SessionFilters(hostFilter = "box", statuses = setOf(StatusFilter.BLOCKED)),
                            shown = 1,
                            total = 4,
                        ),
                        handlers = SessionsHandlers(onClearFacet = { cleared += it }),
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("State: Blocked. Remove filter").performClick()
        assertEquals(listOf(SessionFacetId.STATE), cleared)
    }

    /**
     * Archived sessions are hidden by default; the list's last row says how
     * many and brings them back, and it is a real 48 dp target.
     */
    @Test
    fun the_archived_row_says_how_many_are_hidden_and_shows_them() {
        val shown = mutableListOf<Boolean>()
        compose.setContent {
            FleetTheme {
                Box(Modifier.width(NARROW)) {
                    SessionsScreen(
                        state = SessionsUiState(
                            status = ConnectionStatus.Connected(hubVersion = null),
                            groups = listOf(
                                HostGroup(
                                    alias = "box",
                                    reachable = true,
                                    projects = listOf(ProjectGroup(1, "acme/api", listOf(SessionRow(id = 1, tmuxName = "sess-1")))),
                                ),
                            ),
                            shown = 1,
                            total = 4,
                            workAvailable = true,
                            archivedHidden = 3,
                        ),
                        handlers = SessionsHandlers(onSetShowArchived = { shown += it }),
                    )
                }
            }
        }

        compose.onNodeWithText("3 archived sessions hidden").assertIsDisplayed()
        val button = compose.onNodeWithText("Show archived")
        assertTrue(button.getBoundsInRoot().let { it.bottom - it.top } >= 40.dp)
        button.performClick()
        assertEquals(listOf(true), shown)
    }

    /** Pinned so the assertion does not depend on which device runs it. */
    private companion object {
        val NARROW = 320.dp
    }
}
