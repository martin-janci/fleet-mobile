package dev.claudefleet.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionExtrasActions
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * A session's Terminals tab on the New bar (redesign 14.14, the
 * MobileSessionExtras board): plain shells next to the agent, 0 to N per
 * session with + New, and a key bar for what a phone keyboard lacks.
 *
 * A terminal is the hub's own shell session (`new_shell_session`, kind
 * `shell`) on the same host, project and worktree as this one, so it lives
 * on the host, not on the phone: switching tabs, leaving the session or
 * closing the app leaves it running, and the desktop shows the same shells.
 */

/** The shells beside [parent]: kind `shell`, same host, project and worktree, still there; oldest first. */
fun terminalsOf(parent: SessionRow?, rows: List<SessionRow>): List<SessionRow> {
    if (parent == null) return emptyList()
    return rows.filter {
        it.id != parent.id && it.kind == "shell" && it.hostAlias == parent.hostAlias &&
            it.projectId == parent.projectId && it.worktreeId == parent.worktreeId &&
            it.lostAt == null && it.status != "ghost"
    }.sortedBy { it.id }
}

/** A new shell's tmux name: the session's own, then `-sh` and the first number its shells do not use. */
fun nextShellName(parent: SessionRow, terminals: List<SessionRow>): String {
    val taken = terminals.map { it.tmuxName }.toSet()
    var n = 1
    while ("${parent.tmuxName}-sh$n" in taken) n++
    return "${parent.tmuxName}-sh$n"
}

/** The shell shown beside [selected] in landscape: the one picked before if still there, else the first other one. */
fun pairedOf(terminals: List<SessionRow>, selected: Long?, paired: Long?): Long? =
    paired?.takeIf { id -> id != selected && terminals.any { it.id == id } }
        ?: terminals.firstOrNull { it.id != selected }?.id

/** A terminal's chip: "shell · 2", numbered as the tab lists them. */
fun terminalLabel(index: Int): String = "shell · ${index + 1}"

/** The tab's words: "Terminals", with how many when there are any. */
fun terminalsTabLabel(count: Int): String = if (count > 0) "Terminals $count" else "Terminals"

/**
 * The key bar. Esc, Tab and ⌃C go to the shell's pane as keys (the hub's
 * `send_prompt { keys }` takes Enter, Escape, Tab, C-c and a digit, nothing
 * else); ↑ and ↓ walk back through what was typed here, into the line; | and
 * ~ go into the line, being what a phone keyboard hides furthest away.
 * There is no free Ctrl key: the hub does not take Ctrl chords beyond C-c.
 */
enum class TerminalKey(val label: String, val key: String? = null, val insert: String? = null) {
    Esc("Esc", key = "Escape"),
    Tab("Tab", key = "Tab"),
    Up("↑"),
    Down("↓"),
    CtrlC("⌃C", key = "C-c"),
    Pipe("|", insert = "|"),
    Tilde("~", insert = "~"),
}

data class TerminalsUiState(
    /** The hub has shells and this pairing may start one here. */
    val canCreate: Boolean = false,
    /** This pairing may type into a shell. */
    val canType: Boolean = false,
    val connected: Boolean = false,
    val terminals: List<SessionRow> = emptyList(),
    val selected: Long? = null,
    /** The selected shell's screen, newest line last; null until read. */
    val screen: String? = null,
    /** In landscape, the shell beside the selected one (redesign 14.21); null in one column or with one shell. */
    val paired: Long? = null,
    val pairedScreen: String? = null,
    val input: String = "",
    val creating: Boolean = false,
    val error: Friendly? = null,
    /** Why ⋮ Archive did not happen; drawn by the session screen, not this tab. */
    val archiveError: Friendly? = null,
)

/**
 * The Terminals tab and the ⋮ menu's Archive. Kept by the session route, so
 * the chosen shell, its last screen and the half-typed line survive a switch
 * to another tab and back; the shell itself survives everything (it is the
 * host's).
 */
class SessionExtrasViewModel(
    private val sessionId: Long,
    private val fleet: FleetState,
    private val actions: SessionExtrasActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    private val pollMs: Long = TERMINAL_POLL_MS,
) {
    private data class Local(
        val selected: Long? = null,
        val screens: Map<Long, String> = emptyMap(),
        val input: String = "",
        val history: List<String> = emptyList(),
        /** Where ↑/↓ stand in [history]; null when not walking it. */
        val historyAt: Int? = null,
        val creating: Boolean = false,
        val error: Friendly? = null,
        val archiveError: Friendly? = null,
        /** Two shells side by side (landscape); [paired] is the other one shown. */
        val split: Boolean = false,
        val paired: Long? = null,
    )

    private val local = MutableStateFlow(Local())
    private var poll: Job? = null

    val state: StateFlow<TerminalsUiState> = combine(local, fleet.sessions, fleet.capabilities, fleet.status) { l, rows, caps, status ->
        val parent = rows.firstOrNull { it.id == sessionId }
        val terminals = terminalsOf(parent, rows)
        val selected = l.selected?.takeIf { id -> terminals.any { it.id == id } } ?: terminals.firstOrNull()?.id
        val paired = if (l.split) pairedOf(terminals, selected, l.paired) else null
        TerminalsUiState(
            canCreate = canWrite && caps.shellSessions && parent?.projectId != null,
            canType = canWrite,
            connected = status is ConnectionStatus.Connected,
            terminals = terminals,
            selected = selected,
            screen = selected?.let { l.screens[it] },
            paired = paired,
            pairedScreen = paired?.let { l.screens[it] },
            input = l.input,
            creating = l.creating,
            error = l.error,
            archiveError = l.archiveError,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TerminalsUiState())

    /** The shell [selected] reads, from the live rows: `state` trails them by a dispatch. */
    private fun current(): Long? {
        val rows = fleet.sessions.value
        val terminals = terminalsOf(rows.firstOrNull { it.id == sessionId }, rows)
        return local.value.selected?.takeIf { id -> terminals.any { it.id == id } } ?: terminals.firstOrNull()?.id
    }

    /** The tab is showing: read the selected shell now and every [pollMs] while it shows. */
    fun show() {
        poll?.cancel()
        poll = scope.launch {
            while (true) {
                capture()
                delay(pollMs)
            }
        }
    }

    /** The tab is gone: stop reading. The shell keeps running on its host. */
    fun hide() {
        poll?.cancel()
        poll = null
    }

    fun select(id: Long) {
        val was = current()
        val pairedWas = pairedNow()
        // Tapping the other pane of a split swaps the two: both stay on screen.
        local.update { it.copy(selected = id, historyAt = null, paired = if (id == pairedWas) was else it.paired) }
        scope.launch { capture() }
    }

    /** Landscape with two shells or more: read the one beside the selected one as well. */
    fun setSplit(on: Boolean) {
        if (local.value.split == on) return
        local.update { it.copy(split = on) }
        if (on) scope.launch { capture() }
    }

    private fun pairedNow(): Long? {
        if (!local.value.split) return null
        val rows = fleet.sessions.value
        return pairedOf(terminalsOf(rows.firstOrNull { it.id == sessionId }, rows), current(), local.value.paired)
    }

    /** + New: a shell in this session's worktree, selected once the hub has made it. */
    fun newTerminal(): Job = scope.launch {
        val rows = fleet.sessions.value
        val parent = rows.firstOrNull { it.id == sessionId } ?: return@launch
        val projectId = parent.projectId ?: return@launch
        if (!canWrite || !fleet.capabilities.value.shellSessions || local.value.creating) return@launch
        local.update { it.copy(creating = true, error = null) }
        guarded {
            val row = actions.newShell(parent.hostAlias, projectId, parent.worktreeId, nextShellName(parent, terminalsOf(parent, rows)))
            local.update { it.copy(selected = row.id) }
            capture()
        }
        local.update { it.copy(creating = false) }
    }

    fun setInput(text: String) {
        local.update { it.copy(input = text, historyAt = null) }
    }

    /** ⏎: the line to the shell, and pressed; an empty line is a bare Enter. */
    fun submit(): Job = scope.launch {
        val id = current() ?: return@launch
        if (!canWrite) return@launch
        val line = local.value.input
        guarded {
            if (line.isBlank()) {
                actions.press(id, "Enter")
            } else {
                actions.type(id, line)
                local.update { l -> l.copy(input = "", historyAt = null, history = (l.history + line).takeLast(HISTORY_MAX)) }
            }
            delay(AFTER_INPUT_MS)
            capture()
        }
    }

    /** One key of the key bar. */
    fun press(key: TerminalKey): Job = scope.launch {
        val insert = key.insert
        val named = key.key
        when {
            insert != null -> local.update { it.copy(input = it.input + insert, historyAt = null) }
            key == TerminalKey.Up -> local.update { walk(it, -1) }
            key == TerminalKey.Down -> local.update { walk(it, 1) }
            named != null -> {
                val id = current() ?: return@launch
                if (!canWrite) return@launch
                guarded {
                    actions.press(id, named)
                    delay(AFTER_INPUT_MS)
                    capture()
                }
            }
        }
    }

    /** ⋮ Archive: off the work board; [onDone] runs once the hub took it. */
    fun archive(onDone: () -> Unit): Job = scope.launch {
        if (!canWrite || !fleet.capabilities.value.archiveSession) return@launch
        local.update { it.copy(archiveError = null) }
        try {
            actions.archive(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(archiveError = friendly(t)) }
            return@launch
        }
        onDone()
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    fun dismissArchiveError() {
        local.update { it.copy(archiveError = null) }
    }

    private fun walk(l: Local, by: Int): Local {
        if (l.history.isEmpty()) return l
        val at = (l.historyAt ?: l.history.size) + by
        return when {
            at < 0 -> l.copy(historyAt = 0, input = l.history.first())
            at >= l.history.size -> l.copy(historyAt = null, input = "")
            else -> l.copy(historyAt = at, input = l.history[at])
        }
    }

    private suspend fun capture() {
        for (id in listOfNotNull(current(), pairedNow())) {
            try {
                val text = actions.capture(id, CAPTURE_LINES)
                local.update { it.copy(screens = it.screens + (id to text)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A missed read is retried by the next tick; the screen keeps its last capture.
            }
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        }
    }

    companion object {
        const val TERMINAL_POLL_MS = 2_000L
        const val AFTER_INPUT_MS = 300L
        const val CAPTURE_LINES = 200
        const val HISTORY_MAX = 50
    }
}

const val TERMINALS_NEW_TAG = "terminals.new"
const val TERMINALS_INPUT_TAG = "terminals.input"
const val TERMINALS_SCREEN_TAG = "terminals.screen"
const val TERMINALS_EMPTY_TAG = "terminals.empty"
const val TERMINALS_SPLIT_TAG = "terminals.split."

/** One shell's screen, newest line last; in a split, with its name and an outline on the one being typed into. */
@Composable
private fun ShellScreen(
    screen: String?,
    connected: Boolean,
    label: String?,
    active: Boolean,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val down = rememberScrollState()
    val across = rememberScrollState()
    LaunchedEffect(screen, down.maxValue) { down.scrollTo(down.maxValue) }
    Surface(
        color = Fleet.colors.bgSunk,
        contentColor = Fleet.colors.fg,
        shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp),
        border = if (label != null && active) BorderStroke(1.dp, Fleet.colors.accent) else null,
        modifier = modifier.then(if (label != null) Modifier.clickable(onClick = onTap) else Modifier),
    ) {
        Column {
            if (label != null) {
                Text(
                    label,
                    style = Fleet.type.textSm,
                    color = if (active) Fleet.colors.fg else Fleet.colors.fgMuted,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp),
                )
            }
            Text(
                text = screen ?: if (connected) "Reading the shell…" else "The hub is offline; the shell cannot be read.",
                style = Fleet.type.code,
                softWrap = false,
                modifier = Modifier.verticalScroll(down).horizontalScroll(across).padding(12.dp)
                    .then(if (active) Modifier.testTag(TERMINALS_SCREEN_TAG) else Modifier),
            )
        }
    }
}

class TerminalsHandlers(
    val onShow: () -> Unit = {},
    val onHide: () -> Unit = {},
    val onSelect: (Long) -> Unit = {},
    val onNew: () -> Unit = {},
    val onInput: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onKey: (TerminalKey) -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** The pane went side by side (landscape, two shells or more) or back. */
    val onSplit: (Boolean) -> Unit = {},
)

/** The Terminals tab: a chip per shell and + New, the selected shell's screen, the key bar and the line. */
@Composable
fun TerminalsPane(state: TerminalsUiState, handlers: TerminalsHandlers, modifier: Modifier = Modifier) {
    // Read while shown, and not after: the poll is the tab's, not the session's.
    DisposableEffect(Unit) {
        handlers.onShow()
        onDispose { handlers.onHide() }
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.terminals.forEachIndexed { i, row ->
                FilterChip(
                    selected = row.id == state.selected,
                    onClick = { handlers.onSelect(row.id) },
                    label = { Text(terminalLabel(i)) },
                )
            }
            if (state.canCreate) {
                TextButton(
                    onClick = handlers.onNew,
                    enabled = state.connected && !state.creating,
                    modifier = Modifier.testTag(TERMINALS_NEW_TAG),
                ) { Text(if (state.creating) "Starting…" else "+ New") }
            }
        }
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        if (state.selected == null) {
            Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp).testTag(TERMINALS_EMPTY_TAG)) {
                Text("No terminals yet", style = Fleet.type.textLg)
                Text(
                    if (state.canCreate) {
                        "+ New opens a plain shell in this session's worktree, next to the agent. It runs on the host and stays when you leave."
                    } else {
                        "This hub or pairing cannot start a shell here."
                    },
                    style = Fleet.type.textMd,
                    color = Fleet.colors.fgMuted,
                )
            }
        } else {
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val split = twoPaneWide(maxWidth.value, maxHeight.value) && state.terminals.size >= 2
                LaunchedEffect(split) { handlers.onSplit(split) }
                Column(modifier = Modifier.fillMaxSize()) { TerminalScreen(state, handlers, split) }
            }
        }
    }
}

@Composable
private fun ColumnScope.TerminalScreen(state: TerminalsUiState, handlers: TerminalsHandlers, split: Boolean = false) {
    val paired = state.paired
    if (split && paired != null) {
        // Two shells side by side; a tap on one is where the keys and the line go.
        Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val first = state.terminals.indexOfFirst { it.id == state.selected }
            val second = state.terminals.indexOfFirst { it.id == paired }
            // In the tab's own order, so a tap that swaps them does not move them.
            val panes = listOf(Triple(state.selected, state.screen, first), Triple(paired, state.pairedScreen, second)).sortedBy { it.third }
            for ((id, screen, index) in panes) {
                ShellScreen(
                    screen = screen,
                    connected = state.connected,
                    label = terminalLabel(index),
                    active = id == state.selected,
                    onTap = { if (id != null && id != state.selected) handlers.onSelect(id) },
                    modifier = Modifier.weight(1f).fillMaxHeight().testTag(TERMINALS_SPLIT_TAG + index),
                )
            }
        }
    } else {
        ShellScreen(
            screen = state.screen,
            connected = state.connected,
            label = null,
            active = true,
            onTap = {},
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
        )
    }
    if (state.canType) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (key in TerminalKey.entries) {
                OutlinedButton(
                    onClick = { handlers.onKey(key) },
                    enabled = state.connected || key.key == null,
                    // Narrow keys, so the whole bar fits a phone's width.
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp).widthIn(min = 44.dp),
                ) { Text(key.label, style = Fleet.type.code) }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.input,
                onValueChange = handlers.onInput,
                singleLine = true,
                placeholder = { Text("Type a command") },
                textStyle = Fleet.type.code,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { handlers.onSubmit() }),
                modifier = Modifier.weight(1f).testTag(TERMINALS_INPUT_TAG),
            )
            IconButton(onClick = handlers.onSubmit, enabled = state.connected) {
                Icon(FleetIcons.Send, contentDescription = "Run")
            }
        }
    }
}
