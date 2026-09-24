package dev.claudefleet.mobile.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
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
 * Auto-scroll, on a device, because that is the only place it exists.
 *
 * `SessionScreen`'s `atBottom` is a `derivedStateOf` over
 * `LazyListState.layoutInfo.visibleItemsInfo` — which is measurement. Off a
 * device there is no measurement, so `visibleItemsInfo` is empty, so `atBottom`
 * is unconditionally true and every assertion about it passes for the wrong
 * reason. That is not hypothetical: the comment above it records two bugs that
 * both shipped, and the second one was *exactly* this — `atBottom` permanently
 * true, the effect firing unconditionally, which is the behaviour the code was
 * written to replace.
 *
 * Both of those were found by reading. This is the first thing that can find
 * the third one by running.
 */
class ConversationScrollTest {

    @get:Rule
    val compose = createComposeRule()

    private fun conversation(count: Int) = Conversation(
        turns = (1..count).map {
            val n = it.toString().padStart(2, '0')
            ConvTurn(
                prompt = "prompt-$n",
                at = "t$n",
                endedAt = "t$n",
                items = listOf(ConvItem.Text("answer-$n")),
            )
        },
    )

    /**
     * Renders the screen over a state the test can replace, as the view model would.
     *
     * Each test passes its own [sessionId]: `ScrollMemory` is a process-wide
     * object that outlives any one test and is `internal` to the shared module,
     * so this source set cannot clear it. Distinct ids keep one test's
     * remembered anchor from being recalled by the next.
     */
    private fun show(sessionId: Long, initial: Int): (Int) -> Unit {
        var state by mutableStateOf(SessionUiState(conversation = conversation(initial), loaded = true))
        // Wrapped, because the bar's `StatusChip` reads `LocalStatusColors`
        // and `FleetTheme` is the only thing that provides it — composing the
        // screen bare throws "FleetTheme is not applied" before anything can
        // be measured.
        compose.setContent {
            FleetTheme {
                SessionScreen(
                    sessionId = sessionId,
                    state = state,
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
                    onSetTags = {},
                    onRename = {},
                    onSendCommand = {},
                    quickReplies = emptyList(),
                    onSendQuick = {},
                    onAddQuickReply = {},
                    onRemoveQuickReply = {},
                    onOpenHistory = { emptyList() },
                )
            }
        }
        // On the UI thread, as the view model's own collector would be. A
        // snapshot write from the test thread usually lands, and "usually" in
        // a device test is a flake nobody can reproduce.
        return { total ->
            compose.runOnUiThread { state = state.copy(conversation = conversation(total)) }
        }
    }

    /**
     * New output brings the view with it — for someone already at the bottom.
     */
    @Test
    fun the_newest_turn_is_shown_when_the_conversation_grows() {
        val grow = show(sessionId = 1L, initial = 30)
        compose.waitForIdle()
        compose.onNodeWithText("answer-30").assertIsDisplayed()

        grow(31)
        compose.waitForIdle()

        compose.onNodeWithText("answer-31").assertIsDisplayed()
    }

    /**
     * And it leaves alone someone who has scrolled up to read.
     *
     * This is the bug the whole `atBottom` derivation exists to prevent: the
     * view yanked to the bottom while a person is reading something further
     * up. It cannot be caught anywhere but here, because without measurement
     * `atBottom` is true no matter what.
     */
    @Test
    fun a_reader_scrolled_up_is_not_dragged_to_the_bottom() {
        val grow = show(sessionId = 2L, initial = 30)
        compose.waitForIdle()

        compose.onNodeWithTag(CONVERSATION_LIST).performScrollToIndex(0)
        compose.waitForIdle()
        compose.onNodeWithText("answer-01").assertIsDisplayed()

        grow(31)
        compose.waitForIdle()

        // The whole claim, and enough of it: had the view jumped to the
        // bottom, the turn the reader was on would not still be on screen.
        compose.onNodeWithText("answer-01").assertIsDisplayed()
    }

    /**
     * Scrolling back down opts back in.
     *
     * `atBottom` is derived rather than latched, so returning to the bottom has
     * to restore the following behaviour without anything resetting it. A
     * latched implementation would pass the two tests above and fail this one.
     */
    @Test
    fun scrolling_back_to_the_bottom_resumes_following() {
        val grow = show(sessionId = 3L, initial = 30)
        compose.waitForIdle()

        compose.onNodeWithTag(CONVERSATION_LIST).performScrollToIndex(0)
        compose.waitForIdle()
        grow(31)
        compose.waitForIdle()

        compose.onNodeWithTag(CONVERSATION_LIST).performScrollToIndex(30)
        compose.waitForIdle()
        grow(32)
        compose.waitForIdle()

        compose.onNodeWithText("answer-32").assertIsDisplayed()
    }
}
