package dev.claudefleet.mobile.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * `SessionScreen`'s `atBottom` is a `derivedStateOf` over the list's first
 * visible item and its offset — which is measurement. Off a device there is
 * no measurement, so the list never leaves item 0, so `atBottom`
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
    private fun show(sessionId: Long, initial: Int, loaded: Boolean = true): (Int) -> Unit {
        var state by mutableStateOf(SessionUiState(conversation = conversation(initial), loaded = loaded))
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
        // On the UI thread, as the view model's own collector would be. A
        // snapshot write from the test thread usually lands, and "usually" in
        // a device test is a flake nobody can reproduce.
        return { total ->
            compose.runOnUiThread {
                // What the view model would count for a reader scrolled away;
                // the screen only draws it while that reader is away.
                val unseen = if (state.loaded) total - initial else 0
                state = state.copy(conversation = conversation(total), loaded = true, unseen = unseen)
            }
        }
    }

    /**
     * The bug this list was rebuilt for: the screen drew the OLDEST turns on
     * the frame the conversation arrived and only then scrolled to the newest,
     * which a person saw as the chat opening at the top and jumping.
     *
     * With the clock held, the one frame that lays the turns out is the frame
     * asserted on — no later scroll gets a chance to put things right.
     */
    @Test
    fun the_newest_turn_is_on_screen_in_the_first_frame_that_has_turns() {
        val load = show(sessionId = 4L, initial = 0, loaded = false)
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        load(30)
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("answer-30").assertIsDisplayed()
        compose.onNodeWithText("answer-01").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
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

        // Newest first: item 29 is the oldest of thirty turns.
        compose.onNodeWithTag(CONVERSATION_LIST).performScrollToIndex(29)
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

        compose.onNodeWithTag(CONVERSATION_LIST).performScrollToIndex(29)
        compose.waitForIdle()
        grow(31)
        compose.waitForIdle()

        // Item 0 is the newest turn.
        compose.onNodeWithTag(CONVERSATION_LIST).performScrollToIndex(0)
        compose.waitForIdle()
        grow(32)
        compose.waitForIdle()

        compose.onNodeWithText("answer-32").assertIsDisplayed()
    }

    /**
     * Away from the bottom, what arrived is counted on the pill, and a tap on
     * it is the way back down.
     */
    @Test
    fun the_pill_counts_new_turns_and_takes_the_reader_to_them() {
        val grow = show(sessionId = 5L, initial = 30)
        compose.waitForIdle()
        compose.onNodeWithTag(CONVERSATION_LIST).performScrollToIndex(29)
        compose.waitForIdle()

        grow(32)
        compose.waitForIdle()
        compose.onNodeWithText("2").assertIsDisplayed()

        compose.onNodeWithText("↓ Latest").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("answer-32").assertIsDisplayed()
    }
}
