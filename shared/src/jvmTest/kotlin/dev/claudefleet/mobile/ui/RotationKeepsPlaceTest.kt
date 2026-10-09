package dev.claudefleet.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Redesign 14.21's Verified by: rotating keeps the session and the scroll
 * position. The real session screen on the New bar, drawn upright, scrolled
 * up away from the newest turn, then turned on its side (the two-pane
 * landscape layout) and back: the same session is on screen, a turn that was
 * in view still is, and the screen has not jumped back to the newest one.
 *
 * Android does not recreate the activity on a rotation (`configChanges`,
 * `AppLifecycleTest`), so what a rotation does to this screen is exactly a
 * new size; that is what this test gives it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RotationKeepsPlaceTest {

    private val density = 2f
    private fun px(dp: Int) = (dp * density).toInt()

    private val turns = (1..40).map { n ->
        ConvTurn(prompt = "Prompt number $n", at = "2026-10-09T10:%02d:00Z".format(n % 60), items = listOf(ConvItem.Text("Answer number $n")))
    }
    private val row = SessionRow(id = 7, tmuxName = "rotating", friendlyName = "Rotating session", hostAlias = "pine", claudeStatus = "idle")

    private fun visiblePrompts(scene: ImageComposeScene, heightPx: Int): List<Int> {
        val out = mutableListOf<Int>()
        fun walk(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
            Regex("""^Prompt number (\d+)$""").find(text)?.let { m ->
                val b = n.boundsInRoot
                if (b.bottom > 0f && b.top < heightPx && b.height > 0f) out += m.groupValues[1].toInt()
            }
            n.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return out.distinct().sorted()
    }

    private fun titleShown(scene: ImageComposeScene): Boolean {
        var found = false
        fun walk(n: SemanticsNode) {
            if (n.config.getOrNull(SemanticsProperties.Text)?.any { "Rotating session" in it.text } == true) found = true
            n.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return found
    }

    @Test
    fun rotating_keeps_the_session_and_the_scroll_position() {
        var selected by mutableStateOf(SessionTab.Conversation)
        val (w, h) = 360 to 760
        val scene = ImageComposeScene(width = px(w), height = px(h), density = Density(density)) {
            FleetTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Fleet.colors.bg)) {
                    SessionScreen(
                        sessionId = row.id,
                        state = SessionUiState(session = row, conversation = Conversation(turns = turns), loaded = true, nowSeconds = 1_000),
                        status = ConnectionStatus.Connected("0.9.3"),
                        onDraftChange = {}, onSend = {}, onRefresh = {}, onBack = {}, onDismissError = {}, onAtBottom = {},
                        onAnswer = {}, onShowTerminal = {}, onHideTerminal = {}, onRestart = {}, onSafeKill = {}, onKill = {},
                        onSetTags = {}, onRename = {}, onSendCommand = {}, quickReplies = emptyList(), onSendQuick = {},
                        onAddQuickReply = {}, onEditQuickReply = { _, _ -> }, onRemoveQuickReply = {}, onOpenHistory = { emptyList() },
                        tabs = SessionTabsHost(
                            tabs = listOf(SessionTab.Conversation, SessionTab.Agent, SessionTab.Details),
                            selected = selected,
                            agent = "Claude Code",
                            onSelect = { selected = it },
                        ),
                    )
                }
            }
        }
        try {
            var t = 0L
            fun frame() { repeat(4) { scene.render(t * 16_000_000L); t++ } }
            frame()
            val newest = turns.size
            assertTrue(newest in visiblePrompts(scene, px(h)), "it opens on the newest turn")
            assertTrue(titleShown(scene), "the session's name is in its header")

            // Scroll up, well away from the newest turn.
            repeat(30) {
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(px(w) / 2f, px(h) / 2f), scrollDelta = Offset(0f, -3f))
                frame()
            }
            val upright = visiblePrompts(scene, px(h))
            assertTrue(upright.isNotEmpty(), "some turn is in view")
            assertFalse(newest in upright, "scrolled up: the newest turn is out of view ($upright)")

            // On its side: landscape, two panes.
            scene.constraints = Constraints.fixed(px(h), px(w))
            frame()
            val sideways = visiblePrompts(scene, px(w))
            assertTrue(titleShown(scene), "the same session is on screen")
            assertFalse(newest in sideways, "turning it did not jump to the newest turn ($sideways)")
            assertTrue(sideways.any { it in upright }, "a turn that was in view still is: upright $upright, sideways $sideways")

            // And upright again.
            scene.constraints = Constraints.fixed(px(w), px(h))
            frame()
            val back = visiblePrompts(scene, px(h))
            assertFalse(newest in back, "back upright, still where it was ($back)")
            assertTrue(back.any { it in sideways }, "upright again keeps the place: $sideways then $back")
            assertEquals(SessionTab.Conversation, selected, "the conversation tab is back after the turn")
        } finally {
            scene.close()
        }
    }
}
