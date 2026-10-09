package dev.claudefleet.mobile.android

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.hasSetTextAction
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.ui.CONVERSATION_LIST
import dev.claudefleet.mobile.ui.SessionScreen
import dev.claudefleet.mobile.ui.SessionUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Rule
import org.junit.Test

/**
 * The session screen's chrome folding, on a device: the policy is pinned in
 * `SessionChromeTest` (commonTest), but which way a drag on a `reverseLayout`
 * list reports — and so whether reading back folds the chrome or unfolds it —
 * is measurement and input dispatch, which only a device has.
 *
 * Full chrome is told apart by what only it draws: the header's Refresh and
 * the field's Draft history icon.
 */
class SessionChromeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun conversation(count: Int) = Conversation(
        turns = (1..count).map {
            val n = it.toString().padStart(2, '0')
            ConvTurn(prompt = "prompt-$n", at = "t$n", endedAt = "t$n", items = listOf(ConvItem.Text("answer-$n")))
        },
    )

    /** Distinct ids per test: `ScrollMemory` outlives a test, see `ConversationScrollTest`. */
    private fun show(sessionId: Long, turns: Int) {
        compose.setContent {
            FleetTheme {
                SessionScreen(
                    sessionId = sessionId,
                    state = SessionUiState(conversation = conversation(turns), loaded = true),
                    status = ConnectionStatus.Connected(hubVersion = "test"),
                    onDraftChange = {},
                    onSend = {},
                    onRefresh = {},
                    onBack = {},
                    onDismissError = {},
                    onAtBottom = {},
                    onAnswer = {},
                    onShowTerminal = {},
                    onHideTerminal = {},
                    onRestart = {},
                    onSafeKill = {},
                    onKill = {},
                    onEdit = {},
                    onSendCommand = {},
                    quickReplies = emptyList(),
                    onSendQuick = {},
                    onAddQuickReply = {},
                    onEditQuickReply = { _, _ -> },
                    onRemoveQuickReply = {},
                    onOpenHistory = { emptyList() },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun assertFull() {
        compose.onNodeWithContentDescription("Refresh").assertExists()
        compose.onNodeWithContentDescription("Draft history").assertExists()
    }

    private fun assertFolded() {
        compose.onNodeWithContentDescription("Refresh").assertDoesNotExist()
        compose.onNodeWithContentDescription("Draft history").assertDoesNotExist()
    }

    @Test
    fun reading_back_folds_the_chrome_and_heading_down_unfolds_it() {
        show(sessionId = 101L, turns = 40)
        assertFull()

        // Finger down: older turns come into view from above.
        compose.onNodeWithTag(CONVERSATION_LIST).performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertFolded()

        compose.onNodeWithTag(CONVERSATION_LIST).performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertFull()
    }

    @Test
    fun a_double_tap_toggles_the_whole_screen() {
        // No turns: the empty state has nothing clickable of its own, so the
        // double tap is the conversation area's.
        show(sessionId = 102L, turns = 0)
        assertFull()

        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        assertFolded()

        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        assertFull()
    }

    @Test
    fun the_pill_unfolds_the_footer_with_the_cursor_in_the_field() {
        show(sessionId = 103L, turns = 0)
        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        assertFolded()

        compose.onNodeWithText("Message session…").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Draft history").assertExists()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun the_folded_header_unfolds_on_a_tap() {
        show(sessionId = 104L, turns = 0)
        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        assertFolded()

        compose.onNodeWithText("Session").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Refresh").assertExists()
    }
}
