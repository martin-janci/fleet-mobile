package dev.claudefleet.mobile.android

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.FileDiff
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.AGENT_FULLSCREEN_KEY_TAG
import dev.claudefleet.mobile.ui.AGENT_FULLSCREEN_TAG
import dev.claudefleet.mobile.ui.RepoBody
import dev.claudefleet.mobile.ui.RepoHandlers
import dev.claudefleet.mobile.ui.RepoTab
import dev.claudefleet.mobile.ui.RepoUiState
import dev.claudefleet.mobile.ui.RepoView
import dev.claudefleet.mobile.ui.SIDE_PANE_TAG
import dev.claudefleet.mobile.ui.SPLIT_DIFF_TAG
import dev.claudefleet.mobile.ui.SessionFull
import dev.claudefleet.mobile.ui.SessionScreen
import dev.claudefleet.mobile.ui.SessionTab
import dev.claudefleet.mobile.ui.SessionTabsHost
import dev.claudefleet.mobile.ui.SessionUiState
import dev.claudefleet.mobile.ui.TERMINALS_SPLIT_TAG
import dev.claudefleet.mobile.ui.TerminalsHandlers
import dev.claudefleet.mobile.ui.TerminalsPane
import dev.claudefleet.mobile.ui.TerminalsUiState
import dev.claudefleet.mobile.ui.sessionTabs
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The MobileFullscreen board (redesign 14.21), on the New bar: two panes on
 * a phone on its side, the agent full screen with its key bar, two shells
 * side by side, and a diff split. A phone on its side is the content laid
 * out at 800 × 360 dp, shrunk to fit the emulator's screen.
 */
class MobileLandscapeTest {

    @get:Rule
    val compose = createComposeRule()

    private val row = SessionRow(
        id = 7, tmuxName = "hosts-polish", friendlyName = "Hosts screen polish", hostAlias = "mercury",
        projectId = 3, worktreeId = 11, claudeStatus = "idle",
    )

    private val side = DpSize(800.dp, 360.dp)
    private val upright = DpSize(360.dp, 800.dp)

    private fun shell(id: Long, name: String) =
        SessionRow(id = id, tmuxName = name, hostAlias = "mercury", projectId = 3, worktreeId = 11, kind = "shell")

    @Composable
    private fun Session(
        selected: SessionTab,
        onSelect: (SessionTab) -> Unit = {},
        state: SessionUiState = SessionUiState(session = row, loaded = true),
        full: SessionFull = SessionFull.None,
        onFull: (SessionFull) -> Unit = {},
        onPaneKey: (String) -> Unit = {},
    ) {
        SessionScreen(
            sessionId = 7L,
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
            tabs = SessionTabsHost(
                tabs = sessionTabs(hasWorktree = true, terminals = true),
                selected = selected,
                agent = "Claude Code",
                onSelect = onSelect,
                files = { Text("The worktree's changes") },
                details = { Text("The session's details") },
            ),
            full = full,
            onFull = onFull,
            onPaneKey = onPaneKey,
        )
    }

    @Test
    fun a_phone_on_its_side_shows_the_conversation_and_the_changes() {
        val picked = mutableListOf<SessionTab>()
        compose.setContent {
            FleetTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(side)) {
                    Session(selected = SessionTab.Conversation, onSelect = { picked += it })
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(SIDE_PANE_TAG).assertExists()
        compose.onNodeWithText("The worktree's changes").assertExists()
        compose.onNodeWithText("Details").assertExists()
        assertEquals(listOf(SessionTab.Files), picked)
    }

    @Test
    fun upright_the_session_keeps_one_column() {
        compose.setContent {
            FleetTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(upright)) {
                    Session(selected = SessionTab.Conversation)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SIDE_PANE_TAG).assertDoesNotExist()
        compose.onNodeWithText("Conversation").assertExists()
    }

    @Test
    fun turning_the_phone_back_upright_returns_to_the_conversation() {
        var wide by mutableStateOf(false)
        var selected by mutableStateOf(SessionTab.Conversation)
        compose.setContent {
            FleetTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(if (wide) side else upright)) {
                    Session(selected = selected, onSelect = { selected = it })
                }
            }
        }
        compose.waitForIdle()

        wide = true
        compose.waitForIdle()
        assertEquals(SessionTab.Files, selected)
        compose.onNodeWithText("Details").performClick()
        compose.waitForIdle()
        assertEquals(SessionTab.Details, selected)
        compose.onNodeWithText("The session's details").assertExists()

        wide = false
        compose.waitForIdle()
        assertEquals(SessionTab.Conversation, selected)
        compose.onNodeWithTag(SIDE_PANE_TAG).assertDoesNotExist()
    }

    @Test
    fun the_agent_full_screen_has_its_key_bar_and_a_way_out() {
        val keys = mutableListOf<String>()
        val fulls = mutableListOf<SessionFull>()
        compose.setContent {
            FleetTheme {
                Session(
                    selected = SessionTab.Agent,
                    state = SessionUiState(session = row, loaded = true, terminal = "✻ Claude Code\n> ready"),
                    full = SessionFull.Agent,
                    onFull = { fulls += it },
                    onPaneKey = { keys += it },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(AGENT_FULLSCREEN_TAG).assertExists()
        compose.onNodeWithTag(AGENT_FULLSCREEN_KEY_TAG + "Tab").performScrollTo().performClick()
        compose.onNodeWithTag(AGENT_FULLSCREEN_KEY_TAG + "⌃C").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Leave full screen").performClick()
        compose.waitForIdle()

        assertEquals(listOf("Tab", "C-c"), keys)
        assertEquals(listOf(SessionFull.None), fulls)
    }

    @Test
    fun the_header_offers_full_screen_on_the_new_bar() {
        val fulls = mutableListOf<SessionFull>()
        compose.setContent {
            FleetTheme { Session(selected = SessionTab.Conversation, onFull = { fulls += it }) }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Full screen").performClick()
        compose.waitForIdle()
        assertEquals(listOf(SessionFull.Conversation), fulls)
    }

    @Test
    fun two_shells_sit_side_by_side_and_a_tap_types_in_the_other() {
        val picked = mutableListOf<Long>()
        val splits = mutableListOf<Boolean>()
        val state = TerminalsUiState(
            canCreate = true,
            canType = true,
            connected = true,
            terminals = listOf(shell(9, "hosts-polish-sh1"), shell(10, "hosts-polish-sh2")),
            selected = 9,
            screen = "mercury fleet-mobile $ git log --oneline -3",
            paired = 10,
            pairedScreen = "212 tests completed, 0 failed",
        )
        compose.setContent {
            FleetTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(side)) {
                    TerminalsPane(state = state, handlers = TerminalsHandlers(onSelect = { picked += it }, onSplit = { splits += it }))
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(TERMINALS_SPLIT_TAG + 0).assertExists()
        compose.onNodeWithTag(TERMINALS_SPLIT_TAG + 1).assertExists()
        compose.onNodeWithText("212 tests completed", substring = true).assertExists()
        compose.onNodeWithTag(TERMINALS_SPLIT_TAG + 1).performClick()
        compose.waitForIdle()

        assertEquals(listOf(true), splits)
        assertEquals(listOf(10L), picked)
    }

    @Test
    fun a_diff_goes_side_by_side_only_where_it_is_wide() {
        val diff = FileDiff(
            path = "HostsScreen.kt",
            diff = "@@ -90,1 +90,2 @@\n-    Text(host.name)\n+    Row(verticalAlignment = Center) {\n+        StatusDot(host.reach)\n",
        )
        val repo = RepoUiState(tabs = listOf(RepoTab.Changes), views = listOf(RepoView.Diff("HostsScreen.kt", diff)))
        var wide by mutableStateOf(true)
        compose.setContent {
            FleetTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(if (wide) side else upright)) {
                    RepoBody(state = repo, handlers = RepoHandlers(), split = true)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SPLIT_DIFF_TAG).assertExists()
        compose.onNodeWithText("StatusDot(host.reach)", substring = true).assertExists()

        wide = false
        compose.waitForIdle()
        compose.onNodeWithTag(SPLIT_DIFF_TAG).assertDoesNotExist()
    }
}
