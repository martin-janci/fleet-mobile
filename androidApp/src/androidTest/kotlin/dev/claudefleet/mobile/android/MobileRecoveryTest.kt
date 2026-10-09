package dev.claudefleet.mobile.android

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.RepairReport
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.FAILED_DETAILS_TAG
import dev.claudefleet.mobile.ui.Friendly
import dev.claudefleet.mobile.ui.MoveHandlers
import dev.claudefleet.mobile.ui.MoveSheet
import dev.claudefleet.mobile.ui.MoveUiState
import dev.claudefleet.mobile.ui.NotSent
import dev.claudefleet.mobile.ui.SessionScreen
import dev.claudefleet.mobile.ui.SessionTab
import dev.claudefleet.mobile.ui.SessionTabsHost
import dev.claudefleet.mobile.ui.SessionUiState
import dev.claudefleet.mobile.ui.sessionTabs
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Each state on the MobileRecovery board (redesign 14.5), drawn on the New
 * bar: the failed-session card, "Not sent" with Retry and Edit, the repair
 * result in the conversation, and Move with no host pre-selected.
 */
class MobileRecoveryTest {

    @get:Rule
    val compose = createComposeRule()

    private val failedRow = SessionRow(
        id = 7, tmuxName = "api", friendlyName = "Api tenant resolution", hostAlias = "oci-arm",
        claudeStatus = "failed", currentActivity = "Claude's API was overloaded (529)",
    )

    private val failedTurns = Conversation(
        turns = listOf(
            ConvTurn(
                prompt = "Run the suite and fix what breaks.",
                at = "t1",
                items = listOf(ConvItem.Tool("Run npm test", error = true)),
            ),
        ),
    )

    private class Calls {
        val tabs = mutableListOf<SessionTab>()
        val retriedTurns = mutableListOf<String>()
        var repaired = 0
        var retriedNotSent = 0
        var edited = 0
        var dismissedRepair = 0
        val drafts = mutableListOf<String>()
        var sent = 0
    }

    private fun show(state: SessionUiState, calls: Calls, newBar: Boolean = true) {
        compose.setContent {
            FleetTheme {
                SessionScreen(
                    sessionId = 7L,
                    state = state,
                    status = ConnectionStatus.Connected(hubVersion = "test"),
                    onDraftChange = { calls.drafts += it },
                    onSend = { calls.sent++ },
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
                    onRepair = { calls.repaired++ },
                    onDismissRepair = { calls.dismissedRepair++ },
                    tabs = if (newBar) {
                        SessionTabsHost(
                            tabs = sessionTabs(hasWorktree = true),
                            selected = SessionTab.Conversation,
                            agent = "Claude Code",
                            onSelect = { calls.tabs += it },
                        )
                    } else {
                        null
                    },
                    onRetryNotSent = { calls.retriedNotSent++ },
                    onEditNotSent = { calls.edited++ },
                    onRetryLastTurn = { calls.retriedTurns += it },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun a_failed_session_says_why_and_offers_retry_repair_and_details() {
        val calls = Calls()
        show(SessionUiState(session = failedRow, conversation = failedTurns, loaded = true, repairAvailable = true), calls)

        compose.onNodeWithText("Claude's API was overloaded (529)").assertExists()
        compose.onNodeWithText("⊗ Run npm test · failed").assertExists()

        compose.onNodeWithText("Retry the last turn").performClick()
        compose.onNodeWithText("Repair session").performClick()
        compose.onNodeWithTag(FAILED_DETAILS_TAG).performClick()
        compose.waitForIdle()

        assertEquals(listOf("Run the suite and fix what breaks."), calls.retriedTurns)
        assertEquals(1, calls.repaired)
        assertTrue(SessionTab.Details in calls.tabs)
    }

    @Test
    fun a_failure_changes_the_quick_replies() {
        val calls = Calls()
        show(SessionUiState(session = failedRow, conversation = failedTurns, loaded = true), calls)
        compose.onNodeWithText("Show the error").performClick()
        compose.waitForIdle()
        assertTrue(SessionTab.Agent in calls.tabs)
        compose.onNodeWithText("Retry").assertExists()
    }

    @Test
    fun the_classic_bar_draws_no_recovery_card() {
        show(SessionUiState(session = failedRow, conversation = failedTurns, loaded = true, repairAvailable = true), Calls(), newBar = false)
        compose.onNodeWithText("Retry the last turn").assertDoesNotExist()
    }

    @Test
    fun not_sent_keeps_the_words_with_retry_and_edit() {
        val calls = Calls()
        val row = failedRow.copy(friendlyName = "Hosts screen polish", hostAlias = "mercury", claudeStatus = "idle")
        val notSent = NotSent("Run the full suite", Friendly("Mercury did not answer", "", isError = true))
        show(SessionUiState(session = row, loaded = true, notSent = notSent), calls)

        compose.onNodeWithText("Run the full suite").assertExists()
        compose.onNodeWithText("Not sent: mercury did not answer").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.waitForIdle()

        assertEquals(1, calls.retriedNotSent)
        assertEquals(1, calls.edited)
    }

    @Test
    fun send_says_queue_while_claude_works() {
        val row = failedRow.copy(claudeStatus = "working")
        show(SessionUiState(session = row, loaded = true, draft = "Run the full suite"), Calls())
        compose.onNodeWithText("Queue").assertIsEnabled()
    }

    @Test
    fun the_repair_result_sits_in_the_conversation_with_real_next_steps() {
        val calls = Calls()
        val row = failedRow.copy(claudeStatus = "idle")
        val report = RepairReport(
            healthy = true,
            actions = listOf("Restarted the tmux pane", "Re-attached the conversation"),
            deferred = listOf("3 files are not committed"),
        )
        show(SessionUiState(session = row, loaded = true, repair = report), calls)

        compose.onNodeWithText("✓  Restarted the tmux pane").assertExists()
        compose.onNodeWithText("•  3 files are not committed").assertExists()
        compose.onNodeWithText("OK").assertDoesNotExist()

        compose.onNodeWithText("Show changes").performClick()
        compose.onNodeWithText("Ask Claude Code to commit").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()

        assertTrue(SessionTab.Files in calls.tabs)
        assertTrue("the request goes into the box", calls.drafts.lastOrNull()?.contains("Commit") == true)
        assertEquals("nothing is sent", 0, calls.sent)
        assertEquals(1, calls.dismissedRepair)
    }

    @Test
    fun move_preselects_nothing_and_names_the_missing_choice() {
        val picked = mutableListOf<String>()
        val state = MoveUiState(
            available = true,
            open = true,
            targets = listOf(HostRow("hetzner-1", reachable = true), HostRow("nas", reachable = true, transport = "agent")),
            offline = listOf(HostRow("oci-arm")),
            running = mapOf("hetzner-1" to 1, "nas" to 1),
        )
        compose.setContent {
            FleetTheme {
                MoveSheet(state = state, handlers = MoveHandlers(onTarget = { picked += it }), nowSeconds = 0, orbit = true)
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Choose a host").assertIsNotEnabled()
        compose.onNodeWithText("agent · 1 running").assertExists()
        compose.onNodeWithText("Signal lost · cannot move there now").assertExists()

        compose.onNodeWithText("oci-arm").performClick()
        compose.onNodeWithText("nas").performClick()
        compose.waitForIdle()
        assertEquals(listOf("nas"), picked)
    }
}
