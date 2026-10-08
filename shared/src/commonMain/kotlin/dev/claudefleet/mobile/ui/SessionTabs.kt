package dev.claudefleet.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/**
 * One session's tabs on the New bar (redesign 14.4), the desktop's tabs on a
 * phone: the conversation, the agent's own screen named after the agent, the
 * worktree (which replaces the separate worktree screen) and Details (which
 * replaces the Details sheet), and the shells beside the agent (14.14). On
 * the Classic bar none of this is drawn.
 */
enum class SessionTab { Conversation, Agent, Terminals, Files, Details }

/**
 * The agent tab's name: the agent's, never "Terminal". The row's `agent`
 * (hub contract 11) in the desktop's words (`AGENT_LABELS` in claude-fleet's
 * `row_groups.ts`); a hub too old to send it runs only Claude Code, and an
 * agent this build has no name for is shown as the hub spells it.
 */
fun agentName(row: SessionRow?): String {
    val agent = row?.agent?.takeIf { it.isNotBlank() } ?: return DEFAULT_AGENT_NAME
    return AGENT_NAMES[agent] ?: agent
}

/** The desktop's agent names, by the hub's `agent` value. */
private val AGENT_NAMES: Map<String, String> = mapOf(
    "claude" to DEFAULT_AGENT_NAME,
    "codex" to "Codex",
    "agy" to "Agy",
    "shell" to "Shell",
)

/** What the phone calls the agent of a session whose hub does not say. */
const val DEFAULT_AGENT_NAME: String = "Claude Code"

/** The tab's words. */
fun SessionTab.label(agent: String): String = when (this) {
    SessionTab.Conversation -> "Conversation"
    SessionTab.Agent -> agent
    SessionTab.Terminals -> terminalsTabLabel(0)
    SessionTab.Files -> "Files"
    SessionTab.Details -> "Details"
}

/**
 * The tabs a session shows, in the desktop's order. Files only where the hub
 * serves a worktree at all (`repo_changes`, `repo_log` or `repo_tree`): an
 * empty tab that can only say "this hub has none" is a tab nobody needs.
 * Terminals likewise only where [terminals]: the hub can start a shell here,
 * or one is already running beside the session.
 */
fun sessionTabs(hasWorktree: Boolean, terminals: Boolean = false): List<SessionTab> =
    SessionTab.entries.filter {
        when (it) {
            SessionTab.Files -> hasWorktree
            SessionTab.Terminals -> terminals
            else -> true
        }
    }

/**
 * What the session screen needs to draw its tabs: which one is showing, how to
 * change it, and the panes of the tabs this screen does not draw itself
 * (Files and Details, whose view models live in the route). Null on the
 * Classic bar, where the screen is one conversation as before.
 */
class SessionTabsHost(
    val tabs: List<SessionTab>,
    val selected: SessionTab,
    val agent: String,
    val onSelect: (SessionTab) -> Unit,
    val files: @Composable () -> Unit = {},
    val details: @Composable () -> Unit = {},
    val terminals: @Composable () -> Unit = {},
    /** How many shells run beside the session, for the tab's label. */
    val terminalCount: Int = 0,
    /**
     * The Files pane has a diff open: in landscape it takes the whole width,
     * side by side (redesign 14.21), rather than half of it.
     */
    val sideWhole: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionTabRow(
    host: SessionTabsHost,
    /** The tabs drawn: all of them, or in landscape's right-hand pane all but the conversation. */
    shown: List<SessionTab> = host.tabs,
    selected: SessionTab = host.selected,
) {
    PrimaryScrollableTabRow(
        selectedTabIndex = shown.indexOf(selected).coerceAtLeast(0),
        edgePadding = 8.dp,
        containerColor = Fleet.colors.bgPane,
        contentColor = Fleet.colors.fg,
    ) {
        for (tab in shown) {
            Tab(
                selected = tab == selected,
                onClick = { host.onSelect(tab) },
                text = {
                    val words = if (tab == SessionTab.Terminals) terminalsTabLabel(host.terminalCount) else tab.label(host.agent)
                    Text(words, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                selectedContentColor = Fleet.colors.fg,
                unselectedContentColor = Fleet.colors.fgMuted,
            )
        }
    }
}

/**
 * The agent tab: the agent's own screen, captured from its pane, newest line
 * last. While a prompt waits it explains itself and offers the keys the pane
 * understands, each saying what it does ("Enter · trust", "Esc · exit") — the
 * Classic card's bare Enter and Esc chips moved here, with their meaning. The
 * numbered answers stay on the question card in the conversation.
 *
 * Capturing is the view model's (`showTerminal`, re-captured on each session
 * event while shown); this draws what it has and a refresh.
 */
@Composable
internal fun AgentPane(
    state: SessionUiState,
    agent: String,
    onCapture: () -> Unit,
    onAnswer: (Answer) -> Unit,
    modifier: Modifier = Modifier,
    /** ⤢: the agent's screen edge to edge with a key bar (redesign 14.21); null draws no button. */
    onFullScreen: (() -> Unit)? = null,
) {
    val keys = state.card?.let { agentKeys(it, state.session?.stuckKind) }.orEmpty()
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$agent's screen",
                style = Fleet.type.textSm,
                color = Fleet.colors.fgMuted,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCapture, enabled = state.connected) {
                Icon(FleetIcons.Refresh, contentDescription = "Read the screen again")
            }
            if (onFullScreen != null) {
                IconButton(onClick = onFullScreen) { Icon(FleetIcons.Expand, contentDescription = "$agent full screen") }
            }
        }
        val down = rememberScrollState()
        val across = rememberScrollState()
        val pane = state.terminal
        LaunchedEffect(pane, down.maxValue) { down.scrollTo(down.maxValue) }
        Surface(
            color = Fleet.colors.bgSunk,
            contentColor = Fleet.colors.fg,
            shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            Text(
                text = pane ?: if (state.connected) "Reading the screen…" else "The hub is offline; the screen cannot be read.",
                style = Fleet.type.code,
                // A pane is fixed-width text: wrapping it breaks every box-drawn prompt.
                softWrap = false,
                modifier = Modifier.verticalScroll(down).horizontalScroll(across).padding(12.dp),
            )
        }
        state.card?.let { card ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                (card.explain ?: card.headline).let {
                    Text(it, style = Fleet.type.textMd, color = Fleet.colors.fg2)
                }
                if (!state.readOnly && keys.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (key in keys) {
                            // One weight for every key: none is the default.
                            OutlinedButton(
                                onClick = { onAnswer(key.answer) },
                                enabled = state.canAnswer,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(key.label) }
                        }
                    }
                }
            }
        }
    }
}

/** One key the agent tab offers on a waiting prompt, and what pressing it does. */
data class AgentKey(val answer: Answer, val label: String)

/**
 * The card's raw keys, each with what it does on this prompt. A key's
 * meaning depends on the prompt: Enter trusts on the trust prompt, but on a
 * permission question it picks whichever answer is highlighted — so there it
 * says exactly that rather than pretending to be "Yes".
 */
fun agentKeys(card: BlockedCard, stuckKind: String?): List<AgentKey> =
    card.answers.mapNotNull { answer ->
        when (answer) {
            Answer.Enter -> AgentKey(answer, "Enter · ${enterMeaning(stuckKind)}")
            Answer.Escape -> AgentKey(answer, "Esc · ${escapeMeaning(stuckKind)}")
            else -> null
        }
    }

internal fun enterMeaning(stuckKind: String?): String = when (stuckKind) {
    "trust_prompt" -> "trust"
    "press_enter" -> "continue"
    else -> "the highlighted answer"
}

internal fun escapeMeaning(stuckKind: String?): String = when (stuckKind) {
    "trust_prompt" -> "exit"
    else -> "cancel"
}
