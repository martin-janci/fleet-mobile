package dev.claudefleet.mobile.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import dev.claudefleet.mobile.model.FleetTask
import dev.claudefleet.mobile.model.SessionEvent
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.EventCategory
import dev.claudefleet.mobile.ui.SessionDetailsHandlers
import dev.claudefleet.mobile.ui.SessionDetailsSheet
import dev.claudefleet.mobile.ui.SessionDetailsUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A session's Details sheet, drawn: its sections, the filter chips, and what a tap hands over. */
class SessionDetailsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val me = SessionRow(id = 7, tmuxName = "me", friendlyName = "login bug", hostAlias = "pine", branch = "fix/login", prUrl = "https://example.invalid/pr/1")
    private val sibling = SessionRow(id = 8, tmuxName = "sibling", friendlyName = "review", hostAlias = "pine")

    private fun show(state: SessionDetailsUiState, handlers: SessionDetailsHandlers) {
        compose.setContent {
            FleetTheme { SessionDetailsSheet(state = state, handlers = handlers, sessions = listOf(me, sibling)) }
        }
        compose.waitForIdle()
    }

    private val full = SessionDetailsUiState(
        open = true,
        session = me,
        historyAvailable = true,
        relatedAvailable = true,
        tasksAvailable = true,
        events = listOf(SessionEvent(id = 1, kind = "turn_done"), SessionEvent(id = 2, kind = "stuck", detail = "auth menu")),
        related = listOf(sibling),
        tasks = listOf(FleetTask(id = 5, requesterSessionId = 7, workerSessionId = 8, prompt = "write the tests", state = "running")),
        canCancel = true,
    )

    @Test
    fun the_sections_draw_and_a_related_session_opens() {
        val opened = mutableListOf<Long>()
        show(full, SessionDetailsHandlers(onOpenSession = { opened += it }))

        compose.onNodeWithText("fix/login").assertExists()
        compose.onNodeWithText("Same worktree").assertExists()
        compose.onNodeWithText("review").performClick()
        assertEquals(listOf(8L), opened)
    }

    @Test
    fun a_running_task_can_be_cancelled_and_a_chip_toggles_its_category() {
        val cancelled = mutableListOf<Long>()
        val toggled = mutableListOf<EventCategory>()
        show(full, SessionDetailsHandlers(onCancelTask = { cancelled += it }, onToggle = { toggled += it }))

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Cancel"))
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf(5L), cancelled)

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Errors"))
        compose.onNodeWithText("Errors").performClick()
        assertEquals(listOf(EventCategory.Errors), toggled)
    }
}
