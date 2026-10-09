package dev.claudefleet.mobile.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/*
 * Landscape and full screen on the New bar (redesign 14.21, the
 * MobileFullscreen board). Turned on its side a session shows two panes, the
 * conversation and one of its other tabs; a diff goes side by side; two
 * shells sit next to each other; and the agent's own screen can take the
 * whole phone with a key bar. Nothing here is drawn on the Classic bar.
 */

/** Wide enough for two panes: a phone on its side (or a tablet held so). Portrait never is. */
fun twoPaneWide(widthDp: Float, heightDp: Float): Boolean = widthDp >= TWO_PANE_MIN_DP && widthDp > heightDp

/** Narrower than this, half the screen cannot hold a diff line or a shell prompt. */
const val TWO_PANE_MIN_DP: Float = 560f

/** The tabs the right-hand pane offers: every tab but the conversation, which is the left pane. */
fun sideTabs(tabs: List<SessionTab>): List<SessionTab> = tabs.filter { it != SessionTab.Conversation }

/**
 * What the right-hand pane shows on the way into two panes: the tab that was
 * open, if it is not the conversation; otherwise the worktree's changes,
 * where the hub has them, and the agent's screen where it does not.
 */
fun sideTabFor(selected: SessionTab, tabs: List<SessionTab>): SessionTab = when {
    selected != SessionTab.Conversation && selected in tabs -> selected
    SessionTab.Files in tabs -> SessionTab.Files
    else -> SessionTab.Agent
}

/** One row of a side-by-side diff: the old line and the new, or one line across both (a hunk header, file metadata). */
internal data class SplitRow(val old: CodeLine?, val new: CodeLine?, val across: CodeLine? = null)

/**
 * A unified diff's lines as side-by-side rows. Unchanged lines sit on both
 * sides; a run of removals is set against the run of additions after it, line
 * for line, the shorter side padded with blanks so the next unchanged line
 * lines up again.
 */
internal fun splitDiffRows(lines: List<CodeLine>): List<SplitRow> {
    val out = mutableListOf<SplitRow>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when (line.kind) {
            LineKind.Hunk, LineKind.Meta -> {
                out += SplitRow(null, null, across = line)
                i++
            }
            LineKind.Plain -> {
                out += SplitRow(line, line)
                i++
            }
            LineKind.Removed, LineKind.Added -> {
                val removed = mutableListOf<CodeLine>()
                val added = mutableListOf<CodeLine>()
                while (i < lines.size && lines[i].kind == LineKind.Removed) removed += lines[i++]
                while (i < lines.size && lines[i].kind == LineKind.Added) added += lines[i++]
                for (k in 0 until maxOf(removed.size, added.size)) out += SplitRow(removed.getOrNull(k), added.getOrNull(k))
            }
        }
    }
    return out
}

/** Where each hunk starts among the split rows. */
internal fun splitHunkStarts(rows: List<SplitRow>): List<Int> = rows.indices.filter { rows[it].across?.kind == LineKind.Hunk }

/** What one key of the full-screen agent's bar does: answer the question up now, or press a key in the pane. */
sealed interface AgentPress {
    /** Through the question card's own path, which re-reads the pane first. */
    data class Card(val answer: Answer) : AgentPress
    /** A key for the pane while nothing is asked: Escape, Tab, Enter or C-c. */
    data class Key(val key: String) : AgentPress
}

/** One key of the bar: its cap and what it does, or null where it would do nothing safe right now (drawn dimmed). */
data class AgentBarKey(val label: String, val press: AgentPress?) {
    /** What TalkBack says for the cap: a glyph alone is read by its Unicode name (review r11). */
    val spoken: String
        get() = when (label) {
            "Esc" -> "Escape"
            "⏎" -> "Enter"
            "⌃C" -> "Control C"
            else -> label
        }
}

/**
 * The full-screen agent's key bar, always the same caps in the same places:
 * Esc, Tab, ⏎, ⌃C and 1, 2, 3 for a question's numbered answers.
 *
 * While a question is up every key goes through its card, as the card's own
 * buttons do — the pane is re-read before a key is pressed, since Enter on a
 * permission question approves — and Tab and ⌃C wait. With no question, Esc,
 * Tab, ⏎ and ⌃C are pressed in the pane and the digits wait. The hub takes
 * no other keys, so ⇧Tab and the arrows of the board are not here.
 */
fun agentBarKeys(card: BlockedCard?): List<AgentBarKey> {
    if (card == null) {
        return listOf(
            AgentBarKey("Esc", AgentPress.Key("Escape")),
            AgentBarKey("Tab", AgentPress.Key("Tab")),
            AgentBarKey("⏎", AgentPress.Key("Enter")),
            AgentBarKey("⌃C", AgentPress.Key("C-c")),
            AgentBarKey("1", null),
            AgentBarKey("2", null),
            AgentBarKey("3", null),
        )
    }
    val option = { n: Int -> card.answers.firstOrNull { it is Answer.Option && it.n == n }?.let { AgentPress.Card(it) } }
    return listOf(
        AgentBarKey("Esc", AgentPress.Card(Answer.Escape).takeIf { Answer.Escape in card.answers }),
        AgentBarKey("Tab", null),
        AgentBarKey("⏎", AgentPress.Card(Answer.Enter).takeIf { Answer.Enter in card.answers }),
        AgentBarKey("⌃C", null),
        AgentBarKey("1", option(1)),
        AgentBarKey("2", option(2)),
        AgentBarKey("3", option(3)),
    )
}

const val AGENT_FULLSCREEN_TAG = "agent.fullscreen"
const val AGENT_FULLSCREEN_KEY_TAG = "agent.fullscreen.key."
const val AGENT_FULLSCREEN_LINE_TAG = "agent.fullscreen.line"

/**
 * The agent's own screen edge to edge, with its key bar: the board's
 * "Claude Code, full screen". Swipe down on the top line, Back, or ✕ leaves.
 * ⌨ opens a line for words, sent as the composer sends them.
 */
@Composable
internal fun AgentFullscreen(
    state: SessionUiState,
    agent: String,
    onPress: (AgentPress) -> Unit,
    onSendLine: (String) -> Unit,
    onCapture: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val exitPx = with(LocalDensity.current) { SWIPE_EXIT.toPx() }
    var typing by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }
    Surface(color = Fleet.colors.bgSunk, contentColor = Fleet.colors.fg, modifier = modifier.fillMaxSize().testTag(AGENT_FULLSCREEN_TAG)) {
        Column(modifier = Modifier.fillMaxSize()) {
            var dragged by remember { mutableStateOf(0f) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(exitPx) {
                        detectVerticalDragGestures(
                            onDragStart = { dragged = 0f },
                            onDragEnd = { if (dragged > exitPx) onExit() },
                        ) { _, dy -> dragged += dy }
                    }
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "✻ $agent · ${state.session?.displayName ?: "Session"}",
                    style = Fleet.type.textSm,
                    color = Fleet.colors.fgMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("Swipe down to exit", style = Fleet.type.textSm, color = Fleet.colors.fgMuted)
                IconButton(onClick = onCapture, enabled = state.connected) {
                    Icon(FleetIcons.Refresh, contentDescription = "Read the screen again")
                }
                IconButton(onClick = onExit) { Icon(FleetIcons.Collapse, contentDescription = "Leave full screen") }
            }
            val down = rememberScrollState()
            val across = rememberScrollState()
            val pane = state.terminal
            LaunchedEffect(pane, down.maxValue) { down.scrollTo(down.maxValue) }
            Text(
                text = pane ?: if (state.connected) "Reading the screen…" else "The hub is offline; the screen cannot be read.",
                style = Fleet.type.code,
                softWrap = false,
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(down).horizontalScroll(across).padding(horizontal = 12.dp),
            )
            if (!state.readOnly) {
                val keys = agentBarKeys(state.card)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val can = if (state.card != null) state.canAnswer else state.connected && !state.sending
                    for (key in keys) {
                        val press = key.press
                        OutlinedButton(
                            onClick = { if (press != null) onPress(press) },
                            enabled = press != null && can,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .heightIn(min = OrbitTokens.spacing("touch-min").dp)
                                .widthIn(min = 44.dp)
                                .testTag(AGENT_FULLSCREEN_KEY_TAG + key.label),
                        ) { Text(key.label, style = Fleet.type.code, modifier = Modifier.clearAndSetSemantics { contentDescription = key.spoken }) }
                    }
                    OutlinedButton(
                        onClick = { typing = !typing },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp).widthIn(min = 44.dp),
                    ) { Text("⌨", style = Fleet.type.code, modifier = Modifier.clearAndSetSemantics { contentDescription = if (typing) "Hide the keyboard" else "Type a line" }) }
                }
                if (typing) {
                    val send = {
                        if (line.isNotBlank()) {
                            onSendLine(line)
                            line = ""
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = line,
                            onValueChange = { line = it },
                            singleLine = true,
                            placeholder = { Text("Message $agent") },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                            modifier = Modifier.weight(1f).testTag(AGENT_FULLSCREEN_LINE_TAG),
                        )
                        IconButton(onClick = send, enabled = state.connected && state.card == null) {
                            Icon(FleetIcons.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }
}

/** How far down the top line a finger travels to leave full screen. */
private val SWIPE_EXIT = 72.dp

/** What has the whole screen on a session (redesign 14.21). */
enum class SessionFull {
    None,
    /** ⤢ in the header: the conversation and its composer, no header or tabs. */
    Conversation,
    /** ⤢ on the agent's pane: its screen edge to edge with the key bar. */
    Agent,
}
