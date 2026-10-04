package dev.claudefleet.mobile.android

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.ui.BlockedCard
import dev.claudefleet.mobile.ui.SessionScreen
import dev.claudefleet.mobile.ui.SessionUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
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

    private fun show(state: SessionUiState) {
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
}
