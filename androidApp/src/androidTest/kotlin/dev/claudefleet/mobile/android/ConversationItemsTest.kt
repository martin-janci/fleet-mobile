package dev.claudefleet.mobile.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onNodeWithTag
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
 * Every kind the hub emits actually reaches the screen.
 *
 * `ConvItemTest` proves each one *parses*; that is a different claim from each
 * one being *drawn*, and the gap between the two is where this went wrong
 * before. The renderer's `when` is exhaustive, so a new variant cannot be
 * silently skipped — but "the code compiles" is not "the person sees it", and
 * a branch that draws an empty string satisfies the compiler perfectly.
 *
 * So this composes one turn holding all seven and asks for each one's text by
 * name. It needs a device for the same reason the rest of this source set
 * does: nothing here renders off one.
 */
class ConversationItemsTest {

    @get:Rule
    val compose = createComposeRule()

    private val everything = Conversation(
        turns = listOf(
            ConvTurn(
                prompt = "do the thing",
                at = "t1",
                endedAt = "t2",
                items = listOf(
                    ConvItem.Text("plain answer"),
                    ConvItem.Tool(summary = "Read(build.gradle.kts)"),
                    ConvItem.Tool(summary = "Bash(false)", error = true),
                    ConvItem.Subagent(
                        name = "Task",
                        agentType = "general-purpose",
                        description = "search the tree",
                        result = "four call sites",
                    ),
                    ConvItem.Notification(status = "completed", summary = "background job finished"),
                    ConvItem.Compact(trigger = "auto", preTokens = 183_000),
                    ConvItem.Command(name = "review", args = "--fast", output = "no findings"),
                    ConvItem.Interrupt(duringTool = true),
                    ConvItem.Unsupported(kind = "whatever-comes-next"),
                ),
            ),
        ),
    )

    @Test
    fun every_item_kind_puts_something_on_the_screen() {
        // Wrapped, because the bar's `StatusChip` reads `LocalStatusColors`
        // and `FleetTheme` is the only thing that provides it — composing
        // the screen bare throws "FleetTheme is not applied".
        compose.setContent {
            FleetTheme {
                SessionScreen(
                    sessionId = 11L,
                    state = SessionUiState(conversation = everything, loaded = true),
                    status = ConnectionStatus.Connected(hubVersion = "test"),
                    onDraftChange = {},
                    onSend = {},
                    onRefresh = {},
                    onBack = {},
                    onDismissError = {},
                    onAtBottom = {},
                )
            }
        }
        compose.waitForIdle()

        // One turn, so everything is in the same item; scrolling to it keeps
        // this honest on a short screen.
        compose.onNodeWithTag(CONVERSATION_LIST).performScrollToIndex(0)
        compose.waitForIdle()

        for (text in listOf(
            "plain answer",
            "Read(build.gradle.kts)",
            "Bash(false)",
            "general-purpose",
            "search the tree",
            "four call sites",
            "background job finished",
            "Context compacted",
            "/review --fast",
            "no findings",
            "Interrupted during a tool call",
        )) {
            compose.onNodeWithText(text, substring = true).assertIsDisplayed()
        }
    }

    /**
     * And the fallback still says which kind it is holding.
     *
     * That string is what a bug report quotes, so it is the difference between
     * "the app showed a blank" and "the app needs support for X".
     */
    @Test
    fun an_unknown_kind_names_itself_on_the_screen() {
        // Wrapped, because the bar's `StatusChip` reads `LocalStatusColors`
        // and `FleetTheme` is the only thing that provides it — composing
        // the screen bare throws "FleetTheme is not applied".
        compose.setContent {
            FleetTheme {
                SessionScreen(
                    sessionId = 12L,
                    state = SessionUiState(conversation = everything, loaded = true),
                    status = ConnectionStatus.Connected(hubVersion = "test"),
                    onDraftChange = {},
                    onSend = {},
                    onRefresh = {},
                    onBack = {},
                    onDismissError = {},
                    onAtBottom = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("whatever-comes-next", substring = true).assertIsDisplayed()
    }
}
