package dev.claudefleet.mobile.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.PickedFile
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.SessionScreen
import dev.claudefleet.mobile.ui.SessionUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composer with files queued, on a narrow phone.
 *
 * A chip strip above a field that already grows to six lines is the kind of
 * thing that fits until someone attaches a file with a long name, so this
 * checks bounds rather than only that the nodes exist.
 */
@RunWith(AndroidJUnit4::class)
class ComposerAttachTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun a_queued_file_shows_a_chip_that_fits_and_the_button_is_reachable() {
        val row = SessionRow(
            id = 42,
            tmuxName = "test-session",
            friendlyName = "Test Session",
            hostAlias = "test-host",
            claudeStatus = "idle",
            lastTurnAt = 1000,
            contextPct = 50.0,
            usageCostMicros = 1000000,
            usageModel = "claude-opus-5",
            safeKillState = null,
        )
        compose.setContent {
            FleetTheme {
                Box(Modifier.width(WIDTH)) {
                    sessionScreenWith(
                        state = SessionUiState(
                            session = row,
                            loaded = true,
                            attachments = listOf(
                                PickedFile("a-rather-long-screenshot-name.png", 2048, ByteArray(0)),
                            ),
                        ),
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Attach a file").assertIsDisplayed()
        val chip = compose.onNodeWithText("a-rather-long-screenshot-name.png", substring = true)
        chip.assertIsDisplayed()
        assertTrue("the chip is cut off", chip.getBoundsInRoot().right <= WIDTH)
        compose.onNodeWithContentDescription(
            "Remove a-rather-long-screenshot-name.png",
        ).assertIsDisplayed()
    }

    @Test
    fun a_readonly_device_gets_no_attach_button() {
        val row = SessionRow(
            id = 42,
            tmuxName = "test-session",
            friendlyName = "Test Session",
            hostAlias = "test-host",
            claudeStatus = "idle",
            lastTurnAt = 1000,
            contextPct = 50.0,
            usageCostMicros = 1000000,
            usageModel = "claude-opus-5",
            safeKillState = null,
        )
        compose.setContent {
            FleetTheme {
                Box(Modifier.width(WIDTH)) {
                    sessionScreenWith(
                        state = SessionUiState(
                            session = row,
                            loaded = true,
                            readOnly = true,
                        ),
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Attach a file").assertDoesNotExist()
    }

    @Composable
    private fun sessionScreenWith(
        state: SessionUiState,
    ) {
        SessionScreen(
            sessionId = state.session?.id ?: 0,
            state = state,
            status = ConnectionStatus.Connected(hubVersion = "test"),
            onDraftChange = {},
            onSend = {},
            onPicked = {},
            onRemoveAttachment = {},
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
            quickReplies = listOf(),
            onSendQuick = {},
            onAddQuickReply = {},
            onRemoveQuickReply = {},
            onOpenHistory = { emptyList() },
        )
    }

    private companion object {
        val WIDTH = 320.dp
    }
}
