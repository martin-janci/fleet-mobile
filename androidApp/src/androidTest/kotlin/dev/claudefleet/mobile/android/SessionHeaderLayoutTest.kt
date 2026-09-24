package dev.claudefleet.mobile.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.SessionScreen
import dev.claudefleet.mobile.ui.SessionUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The session header on a narrow phone, with everything it can carry at once.
 *
 * It used to be one `TopAppBar` whose `actions` held the status strip, the
 * `/compact` chip, a retirement chip and five icons. `actions` is measured
 * before the title, so on a phone the title got zero width: the host alias
 * wrapped a character per line, the bar grew most of the way down the screen,
 * and the strip ran off the left edge over the back arrow. Every one of those
 * is a claim about bounds, which is why this checks bounds rather than only
 * `assertIsDisplayed` — a node one pixel on screen passes that.
 */
@RunWith(AndroidJUnit4::class)
class SessionHeaderLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun a_crowded_header_fits_a_narrow_screen() {
        val row = SessionRow(
            id = 7,
            tmuxName = "sess-7",
            friendlyName = "analyze PD-2939 vat sums duplication in the admin web",
            hostAlias = "claude-fleet-trn",
            claudeStatus = "working",
            lastTurnAt = 1_000,
            contextPct = 91.0,
            usageCostMicros = 13_220_000,
            usageModel = "claude-opus-5",
            safeKillState = "requested",
        )
        compose.setContent {
            FleetTheme {
                Box(Modifier.width(WIDTH)) {
                    SessionScreen(
                        sessionId = 7L,
                        state = SessionUiState(session = row, loaded = true, nowSeconds = 90_000),
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
                        quickReplies = listOf("go on", "yes"),
                        onSendQuick = {},
                        onAddQuickReply = {},
                        onRemoveQuickReply = {},
                        onOpenHistory = { emptyList() },
                    )
                }
            }
        }

        val back = compose.onNodeWithContentDescription("Back").getBoundsInRoot()
        assertTrue("the back button starts off screen at ${back.left}", back.left >= 0.dp)

        // One line each, so the whole title block is short. The old bar's
        // title measured hundreds of dp tall once it wrapped.
        val host = compose.onNodeWithText("claude-fleet-trn").getBoundsInRoot()
        assertTrue("the host line wrapped: ${host.bottom - host.top} tall", host.bottom - host.top < 24.dp)
        assertTrue("the host line is squeezed to ${host.right - host.left}", host.right - host.left > 80.dp)

        // The strip sits below the title row, not over the back arrow.
        val strip = compose.onNodeWithText("working", substring = true).getBoundsInRoot()
        assertTrue("the strip is left of the screen at ${strip.left}", strip.left >= 0.dp)
        assertTrue("the strip is not under the title: ${strip.top} vs ${host.bottom}", strip.top >= host.bottom)

        // Everything the header offers is on screen and whole.
        for (label in listOf("/compact", "retire: requested")) {
            compose.onNodeWithText(label).assertIsDisplayed()
            val right = compose.onNodeWithText(label).getBoundsInRoot().right
            assertTrue("\"$label\" is cut off at $right", right <= WIDTH)
        }
        for (description in listOf("Previous turn", "Next turn", "Refresh", "Session actions")) {
            val bounds = compose.onNodeWithContentDescription(description).getBoundsInRoot()
            assertTrue("\"$description\" is cut off: ${bounds.left}..${bounds.right}", bounds.left >= 0.dp && bounds.right <= WIDTH)
        }
    }

    private companion object {
        val WIDTH = 320.dp
    }
}
