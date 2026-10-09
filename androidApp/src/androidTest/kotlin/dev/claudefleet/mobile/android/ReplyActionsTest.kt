package dev.claudefleet.mobile.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.SessionScreen
import dev.claudefleet.mobile.ui.SessionUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The ⋯ under a reply, drawn: what it offers, that the actions which rewrite
 * the session ask first, and what they hand the screen's caller.
 */
class ReplyActionsTest {

    @get:Rule
    val compose = createComposeRule()

    private val row = SessionRow(id = 5, tmuxName = "sess-5", friendlyName = "login bug", hostAlias = "pine", claudeStatus = "idle")

    private fun show(
        rewindAvailable: Boolean,
        onRewind: (String) -> Unit = {},
        onFork: (String?, String?) -> Unit = { _, _ -> },
    ) {
        // One turn, with older ones dropped: the first turn on screen is not
        // the conversation's first, so it may be rewound.
        val conversation = Conversation(
            turns = listOf(ConvTurn(prompt = "fix it", at = "t1", promptUuid = "u1", items = listOf(ConvItem.Text("done")))),
            truncated = true,
        )
        compose.setContent {
            FleetTheme {
                SessionScreen(
                    sessionId = 301L,
                    state = SessionUiState(session = row, conversation = conversation, loaded = true, rewindAvailable = rewindAvailable),
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
                    onRewind = onRewind,
                    onFork = onFork,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun a_hub_without_the_tool_offers_copy_and_quote_only() {
        show(rewindAvailable = false)
        compose.onNodeWithContentDescription("Reply actions").performClick()
        compose.onNodeWithText("Copy").assertExists()
        compose.onNodeWithText("Quote").assertExists()
        compose.onNodeWithText("Rewind here").assertDoesNotExist()
        compose.onNodeWithText("Fork here").assertDoesNotExist()
    }

    @Test
    fun rewind_asks_first_and_hands_over_the_turn_anchor() {
        val rewound = mutableListOf<String>()
        show(rewindAvailable = true, onRewind = { rewound += it })

        compose.onNodeWithContentDescription("Reply actions").performClick()
        compose.onNodeWithText("Rewind here").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), rewound)

        compose.onNodeWithText("Rewind").performClick()
        compose.waitForIdle()
        assertEquals(listOf("u1"), rewound)
    }

    @Test
    fun fork_suggests_a_worktree_named_after_the_session() {
        val forked = mutableListOf<Pair<String?, String?>>()
        show(rewindAvailable = true, onFork = { a, w -> forked += a to w })

        compose.onNodeWithContentDescription("Reply actions").performClick()
        compose.onNodeWithText("Fork here").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fork").performClick()
        compose.waitForIdle()

        // The newest turn: fork keeps all of it, so no anchor.
        assertEquals(listOf<Pair<String?, String?>>(null to "fork-of-login-bug"), forked)
    }
}
