package dev.claudefleet.mobile.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.QuickReply
import dev.claudefleet.mobile.ui.SessionScreen
import dev.claudefleet.mobile.ui.SessionUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The chip row's gestures, on a real device, because that is the only place
 * they were ever wrong.
 *
 * The row used to put a `combinedClickable` Box *around* a `SuggestionChip`
 * whose own `onClick` was empty. Compose hit-tests the innermost node first,
 * so the chip swallowed every tap and called nothing: the buttons were
 * visible, did nothing when tapped, and the long-press that was the only way
 * to edit them never fired either. Nothing off-device could see that — the
 * composable's arguments were all correct — which is why this test runs on
 * the emulator with the secure store and the deep link.
 */
@RunWith(AndroidJUnit4::class)
class QuickReplyChipsTest {

    @get:Rule
    val compose = createComposeRule()

    private val chips = listOf(
        QuickReply(label = "Go on", text = "go on"),
        QuickReply(label = "Review", text = "review the diff"),
    )

    private var sent = mutableListOf<String>()
    private var removed = mutableListOf<QuickReply>()

    private fun screen(draft: String = "") {
        compose.setContent {
            FleetTheme {
                SessionScreen(
                    sessionId = 3L,
                    state = SessionUiState(loaded = true, draft = draft),
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
                    quickReplies = chips,
                    onSendQuick = { sent += it },
                    onAddQuickReply = {},
                    onEditQuickReply = { _, _ -> },
                    onRemoveQuickReply = { removed += it },
                    onOpenHistory = { emptyList() },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun a_chip_shows_its_label_and_sends_its_prompt() {
        screen()

        compose.onNodeWithText("Go on").performClick()
        compose.waitForIdle()

        assertEquals(listOf("go on"), sent)
    }

    @Test
    fun long_pressing_a_chip_opens_its_editor_with_the_prompt_in_it() {
        screen()

        compose.onNodeWithText("Review").performTouchInput { longClick() }
        compose.waitForIdle()

        // The dialog, not a send: the prompt is in an editable field and the
        // row's own tap handler did not fire.
        compose.onNodeWithText("Quick reply").assertIsDisplayed()
        compose.onNodeWithText("review the diff").assertIsDisplayed()
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun the_row_says_out_loud_that_the_chips_can_be_edited() {
        // The bug this file exists for was as much about discoverability as
        // about the gesture: an invisible long-press was the only editor.
        screen()

        compose.onNodeWithText("Edit chips").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Quick replies").assertIsDisplayed()
        compose.onNodeWithText("Go on").assertIsDisplayed()
    }

    @Test
    fun a_chip_can_be_removed_from_the_editor() {
        screen()

        compose.onNodeWithText("Edit chips").performClick()
        compose.waitForIdle()
        // The first Remove in the dialog — the rows are in list order.
        compose.onAllNodesWithText("Remove")[0].performClick()
        compose.waitForIdle()

        assertEquals(listOf(chips.first()), removed)
    }
}
