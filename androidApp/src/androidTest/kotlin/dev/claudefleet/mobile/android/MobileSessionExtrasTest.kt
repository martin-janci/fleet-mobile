package dev.claudefleet.mobile.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.FIND_SCOPE_TAG
import dev.claudefleet.mobile.ui.SessionScreen
import dev.claudefleet.mobile.ui.SessionTab
import dev.claudefleet.mobile.ui.SessionTabsHost
import dev.claudefleet.mobile.ui.SessionUiState
import dev.claudefleet.mobile.ui.TERMINALS_EMPTY_TAG
import dev.claudefleet.mobile.ui.TERMINALS_INPUT_TAG
import dev.claudefleet.mobile.ui.TERMINALS_NEW_TAG
import dev.claudefleet.mobile.ui.TerminalKey
import dev.claudefleet.mobile.ui.TerminalsHandlers
import dev.claudefleet.mobile.ui.TerminalsPane
import dev.claudefleet.mobile.ui.TerminalsUiState
import dev.claudefleet.mobile.ui.sessionTabs
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The MobileSessionExtras board (redesign 14.14), drawn on the New bar: the
 * Terminals tab with its key bar, Find's scopes, and the written-out ⋮ menu.
 */
class MobileSessionExtrasTest {

    @get:Rule
    val compose = createComposeRule()

    private val row = SessionRow(
        id = 7, tmuxName = "hosts-polish", friendlyName = "Hosts screen polish", hostAlias = "mercury",
        projectId = 3, worktreeId = 11, claudeStatus = "idle",
    )

    private fun shell(id: Long, name: String) =
        SessionRow(id = id, tmuxName = name, hostAlias = "mercury", projectId = 3, worktreeId = 11, kind = "shell")

    @Test
    fun terminals_offer_each_shell_new_and_the_key_bar() {
        val keys = mutableListOf<TerminalKey>()
        val typed = mutableListOf<String>()
        var news = 0
        var shown = 0
        val state = TerminalsUiState(
            canCreate = true,
            canType = true,
            connected = true,
            terminals = listOf(shell(9, "hosts-polish-sh1"), shell(10, "hosts-polish-sh2")),
            selected = 9,
            screen = "$ git status -sb\n## claude/hosts-polish",
        )
        compose.setContent {
            FleetTheme {
                TerminalsPane(
                    state = state,
                    handlers = TerminalsHandlers(
                        onShow = { shown++ },
                        onNew = { news++ },
                        onKey = { keys += it },
                        onInput = { typed += it },
                    ),
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("shell · 1").assertExists()
        compose.onNodeWithText("shell · 2").assertExists()
        compose.onNodeWithText("## claude/hosts-polish", substring = true).assertExists()
        compose.onNodeWithTag(TERMINALS_NEW_TAG).performClick()
        compose.onNodeWithText("Esc").performScrollTo().performClick()
        // The bar scrolls sideways on a narrow screen: brought into view before the tap.
        compose.onNodeWithText("⌃C").performScrollTo().performClick()
        compose.onNodeWithTag(TERMINALS_INPUT_TAG).performTextInput("ls")
        compose.waitForIdle()

        assertEquals(1, shown)
        assertEquals(1, news)
        assertEquals(listOf(TerminalKey.Esc, TerminalKey.CtrlC), keys)
        assertEquals("ls", typed.last())
    }

    @Test
    fun no_terminal_yet_says_what_new_does() {
        compose.setContent {
            FleetTheme { TerminalsPane(state = TerminalsUiState(canCreate = true, canType = true, connected = true), handlers = TerminalsHandlers()) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TERMINALS_EMPTY_TAG).assertExists()
        compose.onNodeWithText("No terminals yet").assertExists()
    }

    private fun show(newBar: Boolean, onArchive: (() -> Unit)? = null) {
        compose.setContent {
            FleetTheme {
                SessionScreen(
                    sessionId = 7L,
                    state = SessionUiState(session = row, loaded = true),
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
                    tabs = if (newBar) {
                        SessionTabsHost(
                            tabs = sessionTabs(hasWorktree = true, terminals = true),
                            selected = SessionTab.Conversation,
                            agent = "Claude Code",
                            onSelect = {},
                            terminalCount = 2,
                        )
                    } else {
                        null
                    },
                    onArchive = onArchive,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun the_terminals_tab_counts_its_shells() {
        show(newBar = true)
        compose.onNodeWithText("Terminals 2").assertExists()
    }

    @Test
    fun find_has_scopes_on_the_new_bar_only() {
        show(newBar = true)
        compose.onNodeWithContentDescription("Find in conversation").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("My messages").assertExists()
        compose.onNodeWithTag(FIND_SCOPE_TAG + "Errors").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("none").assertExists()
    }

    @Test
    fun the_classic_find_has_no_scopes() {
        show(newBar = false)
        compose.onNodeWithContentDescription("Find in conversation").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("My messages").assertDoesNotExist()
    }

    @Test
    fun the_menu_says_what_each_item_does_and_kill_asks_first() {
        var archived = 0
        show(newBar = true, onArchive = { archived++ })
        compose.onNodeWithContentDescription("Session actions").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Copy tmux attach command").assertExists()
        compose.onNodeWithText("tmux attach -t hosts-polish").assertExists()
        compose.onNodeWithText("host, branch, model, timeline").assertExists()
        compose.onNodeWithText("asks first").assertExists()

        // Near the bottom of a long menu: scrolled to before the tap, on a short screen too.
        compose.onNodeWithText("Archive").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, archived)
    }
}
