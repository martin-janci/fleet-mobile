package dev.claudefleet.mobile.android

import androidx.compose.ui.test.getBoundsInRoot
import dev.claudefleet.mobile.ui.CONVERSATION_LIST
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.ConversationSummary
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.BlockedCard
import dev.claudefleet.mobile.ui.SessionScreen
import dev.claudefleet.mobile.ui.SessionUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What gives the conversation its room back beyond the chrome: a long prompt
 * folds, and the blocked card has a ceiling. Both are about measurement, so
 * both are here rather than in commonTest.
 */
class SessionReadingRoomTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        state: SessionUiState,
        showFoldHint: Boolean = false,
        onFoldHintShown: () -> Unit = {},
        onLoadOlder: () -> Unit = {},
        onBackToCurrent: () -> Unit = {},
        onStop: () -> Unit = {},
    ) {
        compose.setContent {
            FleetTheme {
                SessionScreen(
                    sessionId = 201L,
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
                    onEditQuickReply = { _, _ -> },
                    onRemoveQuickReply = {},
                    onOpenHistory = { emptyList() },
                    showFoldHint = showFoldHint,
                    onFoldHintShown = onFoldHintShown,
                    onLoadOlder = onLoadOlder,
                    onBackToCurrent = onBackToCurrent,
                    onStop = onStop,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun oneTurn(prompt: String) = Conversation(
        turns = listOf(ConvTurn(prompt = prompt, at = "t1", endedAt = "t1", items = listOf(ConvItem.Text("the answer")))),
    )

    @Test
    fun a_long_prompt_folds_and_a_tap_unfolds_it() {
        val prompt = (1..40).joinToString("\n") { "log line $it" }
        show(SessionUiState(conversation = oneTurn(prompt), loaded = true))

        compose.onNodeWithText("the answer").assertExists()
        compose.onNodeWithText("Show more").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Show less").assertExists()
    }

    @Test
    fun a_short_prompt_offers_no_fold() {
        show(SessionUiState(conversation = oneTurn("go on"), loaded = true))
        compose.onNodeWithText("Show more").assertDoesNotExist()
    }

    @Test
    fun the_blocked_card_never_takes_more_than_half_the_screen() {
        val card = BlockedCard(
            headline = "Waiting on you",
            answers = emptyList(),
            // Far taller than any screen: uncapped, the card would push the
            // conversation to nothing and this would fail.
            explain = (1..600).joinToString(" ") { "explanation-$it" },
            terminalAvailable = false,
        )
        show(SessionUiState(conversation = oneTurn("go on"), loaded = true, card = card))

        val screen = compose.onRoot().getBoundsInRoot()
        val headline = compose.onNodeWithText("Waiting on you").getBoundsInRoot()
        // The card ends where the footer starts; its headline's top is the
        // card's top give or take its padding. The card is capped at 45 %.
        val room = screen.bottom - headline.top
        val height: Dp = screen.bottom - screen.top
        assertTrue("card and footer took $room of $height", room < height * 0.7f)
    }

    @Test
    fun a_code_block_wraps_its_long_lines_on_request() {
        val long = (1..60).joinToString(" ") { "token$it" }
        val answer = "```sh\n$long\n```"
        show(
            SessionUiState(
                conversation = Conversation(
                    turns = listOf(ConvTurn(prompt = "go on", at = "t1", endedAt = "t1", items = listOf(ConvItem.Text(answer)))),
                ),
                loaded = true,
            ),
        )
        val code = compose.onNodeWithText(long, substring = true)
        val oneLine = code.getBoundsInRoot()

        compose.onNodeWithContentDescription("Wrap long lines").performClick()
        compose.waitForIdle()

        val wrapped = code.getBoundsInRoot()
        assertTrue("wrapped ${wrapped.bottom - wrapped.top} vs ${oneLine.bottom - oneLine.top}",
            (wrapped.bottom - wrapped.top) > (oneLine.bottom - oneLine.top) * 2)
        compose.onNodeWithContentDescription("Scroll long lines").assertExists()
    }

    @Test
    fun the_double_tap_hint_shows_the_first_time_reading_back_folds_the_chrome() {
        var marked = 0
        val turns = Conversation(
            turns = (1..40).map {
                ConvTurn(prompt = "prompt-$it", at = "t$it", endedAt = "t$it", items = listOf(ConvItem.Text("answer-$it")))
            },
        )
        show(SessionUiState(conversation = turns, loaded = true), showFoldHint = true, onFoldHintShown = { marked++ })
        compose.onNodeWithText("Double-tap for the whole screen").assertDoesNotExist()

        compose.onNodeWithTag(CONVERSATION_LIST).performTouchInput { swipeDown() }
        // The hint goes away by itself after a few seconds; with the clock
        // advancing to idle, a wait would step straight past it.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Double-tap for the whole screen").assertExists()
        assertEquals(1, marked)
    }

    @Test
    fun older_turns_can_be_asked_for_where_there_are_some() {
        var asked = 0
        val conversation = oneTurn("go on").copy(truncated = true)
        show(SessionUiState(conversation = conversation, loaded = true, canLoadOlder = true), onLoadOlder = { asked++ })

        compose.onNodeWithText("Load older").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun an_earlier_conversation_says_so_and_offers_the_way_back() {
        var back = 0
        val viewing = ConversationSummary(claudeSessionId = "c-old", turns = 4, startSource = "startup")
        show(SessionUiState(conversation = oneTurn("then"), loaded = true, viewing = viewing), onBackToCurrent = { back++ })

        compose.onNodeWithText("Earlier conversation", substring = true).assertExists()
        compose.onNodeWithText("Back to current").performClick()
        assertEquals(1, back)
    }

    @Test
    fun a_working_agent_offers_stop_in_send_s_place() {
        var stopped = 0
        val working = SessionRow(id = 201, tmuxName = "s", friendlyName = "s", hostAlias = "pine", claudeStatus = "working")
        show(SessionUiState(conversation = oneTurn("go on"), loaded = true, session = working), onStop = { stopped++ })

        compose.onNodeWithContentDescription("Send").assertDoesNotExist()
        compose.onNodeWithContentDescription("Stop the agent").performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun a_long_code_block_shows_its_head_and_a_way_to_the_rest() {
        val code = (1..30).joinToString("\n") { "line-$it" }
        show(
            SessionUiState(
                conversation = Conversation(
                    turns = listOf(ConvTurn(prompt = "go on", at = "t1", endedAt = "t1", items = listOf(ConvItem.Text("```\n$code\n```")))),
                ),
                loaded = true,
            ),
        )
        compose.onNodeWithText("line-30", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Show all 30 lines").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("line-30", substring = true).assertExists()
    }
}
