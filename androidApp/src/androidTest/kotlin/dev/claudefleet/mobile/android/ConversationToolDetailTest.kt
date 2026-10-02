package dev.claudefleet.mobile.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.EditDetail
import dev.claudefleet.mobile.model.ToolDetail
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.ui.SessionScreen
import dev.claudefleet.mobile.ui.SessionUiState
import dev.claudefleet.mobile.ui.ToolDetailLoad
import dev.claudefleet.mobile.ui.ToolDetailsHost
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The EXPANDED tool card, drawn.
 *
 * `ConversationItemsTest` composes the screen with no [ToolDetailsHost], so
 * `LocalToolDetails` is [ToolDetailsHost.None], every row is unexpandable, and
 * the whole expand path — the card, the diff, the error tail, Retry — was
 * composed by no test on any platform. A view model test cannot stand in for
 * it: the defects this path had were a figure drawn wrongly and a card that
 * said "running…", neither of which is in any state object.
 *
 * It is one class of its own because each `setContent` costs a host-activity
 * launch and two in one class raced on a loaded emulator — the note in
 * `ConversationItemsTest` records that. So one composition holds every case:
 * a loaded Bash call that failed, a loaded Write, and a read that failed.
 *
 * `expandAll = true` is why [ToolDetailsHost.expandAll] exists: a test cannot
 * tap a row and then wait on a hub read.
 */
class ConversationToolDetailTest {

    @get:Rule
    val compose = createComposeRule()

    private val conversation = Conversation(
        turns = listOf(
            ConvTurn(
                prompt = "check the build",
                at = "t1",
                endedAt = "t2",
                items = listOf(
                    ConvItem.Tool(
                        summary = "Bash(command=./gradlew check)",
                        error = true,
                        id = "tu_1",
                        name = "Bash",
                        target = "./gradlew check",
                        done = true,
                    ),
                    ConvItem.Text("and the file"),
                    ConvItem.Tool(
                        summary = "Write(poll.ts)",
                        id = "tu_2",
                        name = "Write",
                        target = "/repo/src/lib/poll.ts",
                        done = true,
                    ),
                    ConvItem.Text("and one that would not load"),
                    ConvItem.Tool(
                        summary = "Read(missing.ts)",
                        id = "tu_3",
                        name = "Read",
                        target = "/repo/missing.ts",
                        done = true,
                    ),
                ),
            ),
        ),
    )

    private val host = ToolDetailsHost(
        available = true,
        states = mapOf(
            "tu_1" to ToolDetailLoad.Loaded(
                ToolDetail(
                    id = "tu_1",
                    name = "Bash",
                    command = "./gradlew check",
                    result = "FAILURE: build failed",
                    isError = true,
                ),
            ),
            "tu_2" to ToolDetailLoad.Loaded(
                ToolDetail(
                    id = "tu_2",
                    name = "Write",
                    edit = EditDetail(
                        filePath = "/repo/src/lib/poll.ts",
                        old = "",
                        new = "const a = 1\nconst b = 2\n",
                    ),
                ),
            ),
            "tu_3" to ToolDetailLoad.Failed(
                HubError.Transport(IllegalStateException("connection reset")),
            ),
        ),
        request = { _, _, _ -> },
        expandAll = true,
    )

    @Test
    fun an_expanded_card_draws_the_command_the_diff_and_the_failure() {
        compose.setContent {
            FleetTheme {
                SessionScreen(
                    sessionId = 11L,
                    state = SessionUiState(conversation = conversation, loaded = true),
                    status = ConnectionStatus.Connected(hubVersion = "test"),
                    toolDetails = host,
                    onDraftChange = {},
                    onSend = {},
                    onRefresh = {},
                    onBack = {},
                    onDismissError = {},
                    onAtBottom = {},
                    onAnswer = { _, _ -> },
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

        // The Bash card: the full command, and the hub's error text — which a
        // one-line row has no room for and is the only thing that says the
        // build broke rather than merely ran.
        compose.onNodeWithText("FAILURE: build failed", substring = true).performScrollTo().assertIsDisplayed()

        // The Write card. "whole file", not "new file": the hub hard-codes a
        // Write's `old` to "" whether or not the path existed, so the tool name
        // cannot tell a creation from an overwrite.
        compose.onNodeWithText("whole file").performScrollTo().assertIsDisplayed()
        assertTrue(
            "a Write is not known to be a creation, so it must not claim one",
            compose.onAllNodesWithText("new file").fetchSemanticsNodes().isEmpty(),
        )
        // Two added lines, and the removal count is NOT suppressed: `−0` is the
        // figure that tells an overwrite from a creation.
        compose.onNodeWithText("+2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("−0").performScrollTo().assertIsDisplayed()
        // The trailing newline is not a third line.
        assertTrue(
            "a newline-terminated file must not report a phantom addition",
            compose.onAllNodesWithText("+3").fetchSemanticsNodes().isEmpty(),
        )

        // The failed read: what to do about it, and that Retry is offered —
        // a transport failure can read differently next time.
        compose.onNodeWithText("Couldn't load details").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry").performScrollTo().assertIsDisplayed()
    }
}
