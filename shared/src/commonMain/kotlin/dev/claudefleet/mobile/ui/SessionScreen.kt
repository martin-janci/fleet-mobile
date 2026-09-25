package dev.claudefleet.mobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.tailMarker
import dev.claudefleet.mobile.ui.components.BlockedCardView
import dev.claudefleet.mobile.ui.components.CompactChip
import dev.claudefleet.mobile.ui.components.ConnectionBanner
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.MarkdownText
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.components.StatusStrip
import dev.claudefleet.mobile.ui.components.WorkChip
import dev.claudefleet.mobile.ui.components.contextIsTight
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.data.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** The conversation list, for the device test that checks it follows new output. */
const val CONVERSATION_LIST: String = "conversation-list"

/**
 * One session: what has been said, newest at the bottom, and a box to answer.
 *
 * Reports typing and taps like any other screen, but it also owns its own
 * scroll — [ScrollMemory] round-trips a scroll position across a visit to
 * this screen keyed on [sessionId], and [onAtBottom] tells [SessionViewModel]
 * whether the reader is at the newest turn, which is what decides the "↓
 * Latest" / "↓ New reply" pill and (through [SessionUiState.newReply])
 * its label. Only a device can show whether any of this reads well; what
 * *should* happen for a given state is in [SessionViewModel] and tested
 * there, and the pure index arithmetic behind the pill and the turn-stepping
 * buttons is in [newestItemIndex] and [adjacentTurn].
 */
@Composable
fun SessionScreen(
    sessionId: Long,
    state: SessionUiState,
    status: ConnectionStatus,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onDismissError: () -> Unit,
    onAtBottom: (Boolean) -> Unit,
    onAnswer: (Answer) -> Unit,
    onShowTerminal: () -> Unit,
    onHideTerminal: () -> Unit,
    onRestart: () -> Unit,
    onSafeKill: () -> Unit,
    onKill: () -> Unit,
    onSetTags: (List<String>) -> Unit,
    onRename: (String) -> Unit,
    onSendCommand: (String) -> Unit,
    /** The chip row's own content — see `ui/QuickReplies.kt`. */
    quickReplies: List<String>,
    onSendQuick: (String) -> Unit,
    onAddQuickReply: (String) -> Unit,
    onRemoveQuickReply: (String) -> Unit,
    /**
     * Pulled fresh each time the field's leading icon opens the history
     * sheet — [dev.claudefleet.mobile.ui.QuickReplies.history] is a plain
     * read, not a flow, so there is nothing to collect here.
     */
    onOpenHistory: () -> List<String>,
    modifier: Modifier = Modifier,
    /** The ticket chip and its sheet; the default draws nothing (a hub without the work graph). */
    work: SessionWorkUiState = SessionWorkUiState(),
    workHandlers: SessionWorkHandlers = SessionWorkHandlers(),
    /** A one-off line from the form that made this session — what a multi-repo start left out. */
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
) {
    val turns = state.conversation.turns
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val newest = newestItemIndex(turns.size, state.conversation.truncated)
    // Newest at the bottom, so new output should bring the view with it —
    // but only for someone who was already at the bottom. This used to fire
    // unconditionally and yank the view down while a person was scrolled up
    // reading, and it keyed on `turns.size`, so the live bottom turn growing
    // — the usual case, since the agent appends items to it while it works —
    // did not scroll at all.
    //
    // The key has to include `newest`. `remember(listState)` alone
    // allocated the lambda once and closed over the `newest` of the FIRST
    // composition — which is null, because `SessionRoute` composes this
    // with `SessionUiState`'s initial empty `Conversation` and only then
    // runs `vm.load()`. `newest == null` is the second disjunct, so
    // `atBottom` was permanently true and the effect below fired
    // unconditionally: exactly the behaviour it was written to replace.
    // `listState` comes from `rememberLazyListState()` and never changes, so
    // the key could never have invalidated on its own.
    val atBottom by remember(listState, newest) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || newest == null || last.index >= newest - 1
        }
    }

    // The view model's own copy of `atBottom` — used for `newReply` and the
    // pill's label — follows the screen's, not the other way round: the
    // screen is the one thing that can actually see the list.
    LaunchedEffect(atBottom) { onAtBottom(atBottom) }

    // Remembers where this session was scrolled to across a visit to this
    // screen — closing it (navigating away; the composable leaving
    // composition) is the only place that can see the final position, so
    // that is where it is captured. Keyed on `sessionId` rather than `Unit`:
    // going from session A's screen straight to session B's re-enters this
    // composable at the same call site, and without the key the dispose here
    // would fire for A only when the WHOLE screen (both A's and B's) leaves
    // composition, which is too late to have recorded A's position at all.
    DisposableEffect(sessionId) {
        onDispose {
            ScrollMemory.remember(
                sessionId,
                ScrollAnchor(
                    firstVisibleIndex = listState.firstVisibleItemIndex,
                    firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                    atBottom = atBottom,
                ),
            )
        }
    }

    // Whether this screen's one shot at applying a recalled anchor has
    // already been taken. Keyed on `sessionId` for the same reason as the
    // `DisposableEffect` above: a straight A-to-B navigation must get its own
    // fresh consideration for B rather than inheriting "already considered"
    // from A's.
    var recallConsidered by remember(sessionId) { mutableStateOf(false) }

    // `state.conversation.tailMarker()` — turn count and the last turn's
    // `endedAt` together — is the one rule for "did the tail move", shared
    // with `SessionViewModel.runGeneration`'s own `tailGrew`, rather than
    // spelling the same pair of keys out by hand in both places.
    LaunchedEffect(state.conversation.tailMarker(), newest, state.loaded) {
        // On the first `loaded` this screen ever sees, a remembered anchor
        // — one the reader was NOT at the bottom of when it was taken, see
        // [ScrollMemory.remember] — wins over the newest turn: that is
        // "open where you left off". Every other pass through this effect
        // (a later turn arriving, a recall that came back empty or at the
        // bottom) falls through to the ordinary stick-to-the-newest rule.
        if (!recallConsidered && state.loaded) {
            recallConsidered = true
            val recalled = ScrollMemory.recall(sessionId)
            if (recalled != null && !recalled.atBottom) {
                listState.scrollToItem(recalled.firstVisibleIndex, recalled.firstVisibleOffset)
                return@LaunchedEffect
            }
        }
        if (newest != null && atBottom) listState.scrollToItem(newest)
    }

    Column(modifier = modifier.fillMaxSize()) {
        SessionBar(
            state = state,
            onBack = onBack,
            onRefresh = onRefresh,
            listState = listState,
            turnCount = turns.size,
            truncated = state.conversation.truncated,
            scope = scope,
            onRestart = onRestart,
            onSafeKill = onSafeKill,
            onKill = onKill,
            onSetTags = onSetTags,
            onRename = onRename,
            onSendCommand = onSendCommand,
            work = work,
            workHandlers = workHandlers,
        )
        ConnectionBanner(status, state.hubReachable)
        ErrorBanner(state.error, onDismiss = onDismissError)
        ErrorBanner(notice?.let { Friendly("Started, with gaps", it, isError = false) }, onDismiss = onDismissNotice)
        // Behind an open sheet a banner cannot be read: the sheet shows it instead.
        if (!work.sheetOpen) ErrorBanner(work.error, onDismiss = workHandlers.onDismissError)
        if (work.sheetOpen) WorkTicketSheet(work, workHandlers)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.loaded && turns.isEmpty()) {
                EmptyConversation(state)
            } else {
                // Tagged so a device test can address this list rather than
                // guessing which of the screen's scrollable nodes it meant.
                // `atBottom` above is derived from measurement, so it only
                // means anything where there is measurement, and the test that
                // checks it has to run on a device — see `ConversationScrollTest`.
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag(CONVERSATION_LIST),
                ) {
                    if (state.conversation.truncated) {
                        item(key = "truncated") { TruncationNote() }
                    }
                    turnItems(turns)
                }
            }
            // The fast way back down, for whoever scrolled up to read
            // something and either wants the bottom again or just got a
            // fresh reply while they were up there — see `SessionUiState.newReply`.
            if (!atBottom) {
                JumpToLatest(
                    newReply = state.newReply,
                    onClick = {
                        newest?.let { target -> scope.launch { listState.animateScrollToItem(target) } }
                        // Optimistic: this fires before `animateScrollToItem`
                        // has actually finished, on the assumption that the
                        // animation it just started will land there. The
                        // `atBottom` derived above will confirm it once the
                        // list settles; nothing here waits for that.
                        onAtBottom(true)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
            }
        }

        // Between the conversation and the composer: a person who opened this
        // screen because the agent is waiting should not have to scroll to
        // answer it, and the card sits where the answer goes.
        state.card?.let { card ->
            BlockedCardView(
                card = card,
                answering = state.answering,
                stillWaiting = state.stillWaiting,
                terminal = state.terminal,
                readOnly = state.readOnly,
                canAnswer = state.canAnswer,
                connected = state.connected,
                onAnswer = onAnswer,
                onShowTerminal = onShowTerminal,
                onHideTerminal = onHideTerminal,
                // The same `canRestart` the ⋮ menu's own Restart item gates
                // on (see [SessionOverflowMenu]) — one source of truth for
                // "is a restart worth offering", not `canManage` re-read here
                // as a stand-in for it.
                onRestart = if (state.canRestart) onRestart else null,
            )
        }

        // The footer: quick replies and the composer on one surface, the
        // mirror of the header. The chips used to sit on the conversation's
        // own background with the composer on a tinted band under them, so
        // they read as the last line of the transcript rather than as part
        // of the answer box.
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // Hidden outright, not merely dimmed, in the same two cases
                // the card itself takes over the space for: while it is up
                // (the answer goes there instead) and on a readonly device (no
                // chip may offer a write it cannot make) — spec 1.1.
                if (state.card == null && !state.readOnly) {
                    QuickRepliesRow(
                        chips = quickReplies,
                        draft = state.draft,
                        enabled = state.canSendQuick,
                        onSendQuick = onSendQuick,
                        onAdd = onAddQuickReply,
                        onRemove = onRemoveQuickReply,
                    )
                }
                PromptBox(
                    state = state,
                    onDraftChange = onDraftChange,
                    onSend = onSend,
                    onOpenHistory = onOpenHistory,
                )
            }
        }
    }
}

/**
 * Turns have no id on the wire, so they are keyed by position. That is correct
 * here and only here: the list only ever grows at the bottom (see
 * `Conversation.appending`), so an index is stable for every turn but the last.
 */
private fun LazyListScope.turnItems(turns: List<ConvTurn>) {
    for ((index, turn) in turns.withIndex()) {
        item(key = "turn-$index") { Turn(turn) }
    }
}

/**
 * The session's header: back, the session's name over its host, refresh and
 * the ⋮ menu on the first line; the [StatusStrip], a retirement in progress
 * and the turn-stepping arrows on a line of their own under it.
 *
 * All of that used to share one `TopAppBar`'s `actions` slot, which is
 * measured before the title and does not wrap. On a phone it was wider than
 * the screen: the title was left zero width, its host line broke one
 * character per row and stretched the bar down the screen, and the strip ran
 * off the left edge over the back arrow. See [ScreenHeader].
 */
@Composable
private fun SessionBar(
    state: SessionUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    listState: LazyListState,
    turnCount: Int,
    truncated: Boolean,
    scope: CoroutineScope,
    onRestart: () -> Unit,
    onSafeKill: () -> Unit,
    onKill: () -> Unit,
    onSetTags: (List<String>) -> Unit,
    onRename: (String) -> Unit,
    onSendCommand: (String) -> Unit,
    work: SessionWorkUiState,
    workHandlers: SessionWorkHandlers,
) {
    val busy = state.loading || state.refreshing
    val angle = refreshAngle(busy)
    // Recomputed from `listState.firstVisibleItemIndex` — a snapshot-backed
    // read — whenever it moves, same as `atBottom` above it in the file; see
    // [adjacentTurn] for what "adjacent" means once the truncation note is
    // in the count.
    val prevTurn by remember(listState, turnCount, truncated) {
        derivedStateOf { adjacentTurn(listState.firstVisibleItemIndex, turnCount, truncated, -1) }
    }
    val nextTurn by remember(listState, turnCount, truncated) {
        derivedStateOf { adjacentTurn(listState.firstVisibleItemIndex, turnCount, truncated, 1) }
    }
    ScreenHeader(
        title = state.session?.displayName ?: "Session",
        // The host is in the header because a prompt goes to a machine, not
        // just to a name.
        subtitle = state.session?.hostAlias ?: "no longer in the fleet",
        titleStyle = MaterialTheme.typography.titleMedium,
        navigation = {
            IconButton(onClick = onBack) {
                Icon(FleetIcons.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = onRefresh, enabled = !busy) {
                Icon(
                    FleetIcons.Refresh,
                    contentDescription = "Refresh",
                    // `graphicsLayer {}` rather than `Modifier.rotate(angle)`:
                    // the lambda form reads the angle in the draw phase, so a
                    // frame of the spin invalidates drawing alone instead of
                    // recomposing the bar sixty times a second.
                    modifier = Modifier.graphicsLayer { rotationZ = angle },
                )
            }
            // Hidden outright rather than drawn dark: a readonly credential,
            // a session gone from the fleet, or the controller itself (the
            // hub refuses every one of these calls against it with
            // `E_INVALID_STATE`) has no management action to offer at all —
            // see [SessionUiState.canManage].
            if (state.canManage || work.canSetWork) {
                SessionOverflowMenu(
                    state = state,
                    onRestart = onRestart,
                    onSafeKill = onSafeKill,
                    onKill = onKill,
                    onSetTags = onSetTags,
                    onRename = onRename,
                    onSetWork = workHandlers.onSetWork.takeIf { work.canSetWork },
                )
            }
        },
        below = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Takes what the arrows leave and gives way (ellipsis) before
                // they do. A status word alone told a person nothing about how
                // long the agent had been at it, how full its context window
                // was, or what the turn had cost — see `StatusStrip.kt`.
                StatusStrip(
                    row = state.session,
                    context = state.conversation.context,
                    nowSeconds = state.nowSeconds,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { prevTurn?.let { target -> scope.launch { listState.animateScrollToItem(target) } } },
                    enabled = prevTurn != null,
                ) {
                    Icon(
                        FleetIcons.ArrowBack,
                        contentDescription = "Previous turn",
                        modifier = Modifier.rotate(90f),
                    )
                }
                IconButton(
                    onClick = { nextTurn?.let { target -> scope.launch { listState.animateScrollToItem(target) } } },
                    enabled = nextTurn != null,
                ) {
                    Icon(
                        FleetIcons.ArrowBack,
                        contentDescription = "Next turn",
                        modifier = Modifier.rotate(-90f),
                    )
                }
            }
            // The two things that ask something of the reader get a line of
            // their own, and only while one of them is up: beside the strip
            // they left it no room at all on a phone.
            val tight = contextIsTight(state.session, state.conversation.context)
            val retiring = state.safeKillState
            val ticket = work.chip
            if (tight || retiring != null || ticket != null) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // The ticket: key, status and title; a tap opens the sheet.
                    if (ticket != null) {
                        WorkChip(
                            work = ticket,
                            suggested = work.work == null,
                            showTitle = true,
                            onClick = workHandlers.onOpen,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                    if (tight) CompactChip(onCompact = { onSendCommand("/compact") })
                    // A `safe_kill_session` retirement in progress — shown for
                    // as long as the row carries one, independent of which
                    // screen armed it (the desktop can start one too). Named,
                    // because a bare "requested" beside the status read as if
                    // the status itself were "requested".
                    if (retiring != null) {
                        SuggestionChip(onClick = {}, label = { Text("retire: $retiring", maxLines = 1) })
                    }
                }
            }
        },
    )
}

/**
 * The session's management actions, behind a ⋮ icon: rename, edit tags,
 * restart, retire safely (`safe_kill_session`), and kill now.
 *
 * Only drawn when [SessionUiState.canManage] — the caller's job, not this
 * composable's, so that "does this session have a menu at all" stays decided
 * in one place. Within the menu, *Kill now* is further narrowed by
 * [SessionUiState.canKill] (hidden for an `external` session, which the hub
 * refuses with `E_INVALID_STATE`); every item is disabled rather than hidden
 * while [SessionUiState.busy] or the hub is unreachable, the same rule Send
 * and the card's own chips already draw by.
 */
@Composable
private fun SessionOverflowMenu(
    state: SessionUiState,
    onRestart: () -> Unit,
    onSafeKill: () -> Unit,
    onKill: () -> Unit,
    onSetTags: (List<String>) -> Unit,
    onRename: (String) -> Unit,
    /** *Set work…*; null when this token or this hub cannot link work. */
    onSetWork: ((String) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showSetWork by remember { mutableStateOf(false) }
    val manage = state.canManage
    var showTags by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    var showKillConfirm by remember { mutableStateOf(false) }
    val actionable = !state.busy && state.connected

    IconButton(onClick = { expanded = true }) {
        Icon(FleetIcons.MoreVert, contentDescription = "Session actions")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        if (onSetWork != null) {
            DropdownMenuItem(
                text = { Text("Set work…") },
                enabled = state.connected,
                onClick = { expanded = false; showSetWork = true },
            )
        }
        if (manage) {
            SessionManageItems(
                state = state,
                actionable = actionable,
                onSafeKill = onSafeKill,
                close = { expanded = false },
                showRename = { showRename = true },
                showTags = { showTags = true },
                showRestartConfirm = { showRestartConfirm = true },
                showKillConfirm = { showKillConfirm = true },
            )
        }
    }

    if (showSetWork && onSetWork != null) {
        SetWorkDialog(
            onConfirm = { showSetWork = false; onSetWork(it) },
            onDismiss = { showSetWork = false },
        )
    }
    ManageDialogs(
        state = state,
        showRename = showRename,
        hideRename = { showRename = false },
        showTags = showTags,
        hideTags = { showTags = false },
        showRestartConfirm = showRestartConfirm,
        hideRestartConfirm = { showRestartConfirm = false },
        showKillConfirm = showKillConfirm,
        hideKillConfirm = { showKillConfirm = false },
        onRename = onRename,
        onSetTags = onSetTags,
        onRestart = onRestart,
        onKill = onKill,
    )
}

/** The management half of the menu — only when [SessionUiState.canManage]. */
@Composable
private fun SessionManageItems(
    state: SessionUiState,
    actionable: Boolean,
    onSafeKill: () -> Unit,
    close: () -> Unit,
    showRename: () -> Unit,
    showTags: () -> Unit,
    showRestartConfirm: () -> Unit,
    showKillConfirm: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text("Rename…") },
        enabled = actionable,
        onClick = { close(); showRename() },
    )
    DropdownMenuItem(
        text = { Text("Tags…") },
        enabled = actionable,
        onClick = { close(); showTags() },
    )
    if (state.canRestart) {
        DropdownMenuItem(
            text = { Text("Restart") },
            enabled = actionable,
            onClick = { close(); showRestartConfirm() },
        )
    }
    DropdownMenuItem(
        text = { Text("Retire safely") },
        enabled = actionable,
        onClick = { close(); onSafeKill() },
    )
    if (state.canKill) {
        DropdownMenuItem(
            text = { Text("Kill now", color = MaterialTheme.colorScheme.error) },
            enabled = actionable,
            onClick = { close(); showKillConfirm() },
        )
    }
}

@Composable
private fun ManageDialogs(
    state: SessionUiState,
    showRename: Boolean,
    hideRename: () -> Unit,
    showTags: Boolean,
    hideTags: () -> Unit,
    showRestartConfirm: Boolean,
    hideRestartConfirm: () -> Unit,
    showKillConfirm: Boolean,
    hideKillConfirm: () -> Unit,
    onRename: (String) -> Unit,
    onSetTags: (List<String>) -> Unit,
    onRestart: () -> Unit,
    onKill: () -> Unit,
) {
    if (showRename) {
        RenameDialog(
            initial = state.session?.friendlyName.orEmpty(),
            onConfirm = { name -> hideRename(); onRename(name) },
            onDismiss = hideRename,
        )
    }
    if (showTags) {
        TagsDialog(
            tags = state.session?.tags.orEmpty(),
            onConfirm = { tags -> hideTags(); onSetTags(tags) },
            onDismiss = hideTags,
        )
    }
    if (showRestartConfirm) {
        AlertDialog(
            onDismissRequest = hideRestartConfirm,
            title = { Text("Restart this session?") },
            text = { Text("This kills and recreates the tmux session in place — for a wedged REPL.") },
            confirmButton = {
                TextButton(onClick = { hideRestartConfirm(); onRestart() }) { Text("Restart") }
            },
            dismissButton = { TextButton(onClick = hideRestartConfirm) { Text("Cancel") } },
        )
    }
    if (showKillConfirm) {
        KillConfirmDialog(
            onConfirm = { hideKillConfirm(); onKill() },
            onDismiss = hideKillConfirm,
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            TextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The tag editor: a [FlowRow] of removable [InputChip]s plus a field to add
 * one more. Local until Save, which is the point it becomes one `setTags`
 * call replacing the whole list — the hub has no per-tag add/remove of its
 * own.
 */
@Composable
private fun TagsDialog(tags: List<String>, onConfirm: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf(tags) }
    var draft by remember { mutableStateOf("") }
    fun addDraft() {
        val t = draft.trim()
        if (t.isNotEmpty() && t !in current) current = current + t
        draft = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            Column {
                if (current.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (tag in current) {
                            InputChip(
                                selected = false,
                                // Tapping the chip removes it — there is no
                                // "selected" state for a tag, so the whole
                                // chip is the remove affordance, not just its
                                // trailing icon.
                                onClick = { current = current - tag },
                                label = { Text(tag) },
                                trailingIcon = {
                                    Icon(
                                        FleetIcons.Close,
                                        contentDescription = "Remove $tag",
                                        modifier = Modifier.size(InputChipDefaults.IconSize),
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        placeholder = { Text("Add a tag") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addDraft() }),
                    )
                    TextButton(onClick = ::addDraft, enabled = draft.isNotBlank()) { Text("Add") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(current) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * *Kill now*'s own confirmation: there is no "hold to confirm" gesture worth
 * building for one button, so the desktop's press-and-hold becomes a
 * delayed-enable instead — the confirm button stays disabled for
 * [KILL_CONFIRM_DELAY] after the dialog opens, which is long enough that a
 * dialog dismissed by a stray tap cannot also kill the session.
 */
@Composable
private fun KillConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var enabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(KILL_CONFIRM_DELAY)
        enabled = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kill this session now?") },
        text = {
            Text(
                "This kills it immediately, without waiting for it to persist anything. " +
                    "This cannot be undone.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text("Kill now", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * How long *Kill now*'s confirm button stays disabled after the dialog opens
 * — the phone's stand-in for the desktop's press-and-hold, per the brief:
 * "hold to confirm" on a phone is a delayed enable, not a gesture.
 */
private val KILL_CONFIRM_DELAY = 800.milliseconds

/**
 * The refresh icon's angle: spinning while [busy], and a flat `0f` otherwise.
 *
 * The transition used to be created unconditionally and only *applied* when
 * busy, which meant an idle screen — the normal state of a session screen —
 * ran an infinite 900 ms animation forever, waking the frame clock and
 * recomposing the bar to draw an icon at the angle it was already at.
 * Creating it inside the branch is what actually stops it: an
 * `InfiniteTransition` that is not composed is not running.
 *
 * A conditional `rememberInfiniteTransition` is legal — `busy` gates the whole
 * composable call, so the two branches are separate groups in the slot table
 * and leaving one discards its state, which is exactly the intent here.
 */
@Composable
private fun refreshAngle(busy: Boolean): Float = if (busy) {
    rememberInfiniteTransition(label = "refresh").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
        label = "angle",
    ).value
} else {
    0f
}

@Composable
private fun Turn(turn: ConvTurn) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val prompt = turn.prompt
        if (!prompt.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        for ((index, item) in turn.items.withIndex()) {
            Item(item)
            if (index != turn.items.lastIndex) Spacer(Modifier.height(4.dp))
        }
    }
    HorizontalDivider()
}

@Composable
private fun Item(item: ConvItem) {
    when (item) {
        // Hub text is untrusted Markdown, not plain text: `**bold**`, `- `
        // lists and fenced code are common in a transcript (a test summary
        // line, a diff, a shell command) and used to show as literal
        // characters here. `MiniMarkdown.kt` parses a small, deliberately
        // non-general subset (see its file comment for why it exists instead
        // of a library) into native Compose `Text`/spans -- no HTML, no
        // WebView, nothing that fetches a remote image.
        is ConvItem.Text -> MarkdownText(
            text = item.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        // A tool call is a one-liner, and a failed one has to look failed: it is
        // the single most useful thing to spot while scrolling.
        is ConvItem.Tool -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = if (item.error) "✗" else "·",
                style = MaterialTheme.typography.bodySmall,
                color = if (item.error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (item.error) FontWeight.Bold else FontWeight.Normal,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (item.error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // A subagent gets a block rather than a line: it is a whole piece of
        // work, and its result is the part somebody scrolls back for.
        is ConvItem.Subagent -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = buildString {
                        append(item.agentType?.takeIf { it.isNotBlank() } ?: item.name.ifBlank { "subagent" })
                        if (!item.done) append(" — running")
                        if (item.error) append(" — failed")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.error) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    fontWeight = FontWeight.Bold,
                )
                item.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                item.result?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // A background job reporting in. Before the hub parsed these they
        // arrived as raw XML in a text item; the point of drawing them is that
        // they stay on the screen now they arrive tagged.
        is ConvItem.Notification -> Note(
            marker = "◆",
            text = item.label,
            detail = item.result?.takeIf { it.isNotBlank() && it != item.summary },
        )
        // Quiet by design: what matters is that it happened and roughly where.
        is ConvItem.Compact -> Note(marker = "⋯", text = item.label)
        is ConvItem.Interrupt -> Note(marker = "■", text = item.label)
        is ConvItem.Command -> Note(
            marker = "›",
            text = item.label,
            detail = item.output?.takeIf { it.isNotBlank() },
            monospace = true,
        )
        // A `!` shell line: the person typed it, so it reads back as a command
        // with its output under it — the same treatment a slash command gets.
        is ConvItem.Bash -> Note(
            marker = "›",
            text = item.label,
            detail = item.output,
            monospace = true,
        )
        // A harness block the hub had no item for. Quiet, and labelled by its
        // tag, because the reason it exists is that raw XML was worse.
        is ConvItem.Harness -> Note(
            marker = "⋯",
            text = item.tag.ifBlank { "harness" },
            detail = item.body.takeIf { it.isNotBlank() },
        )
        // A kind this build does not know: say so rather than drop it.
        is ConvItem.Unsupported -> Text(
            text = item.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/**
 * One quiet line, optionally with a second under it.
 *
 * The transcript's shape is prompts and answers; compactions, interrupts,
 * slash commands and task notifications are none of those. They are markers —
 * they say something happened without claiming the reader's attention the way
 * a turn does — so they share one understated treatment rather than each
 * inventing their own.
 */
@Composable
private fun Note(
    marker: String,
    text: String,
    detail: String? = null,
    monospace: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (monospace) FontFamily.Monospace else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * What the turn list shows instead of a `LazyColumn` once a read has answered
 * and there is still nothing to draw: a killed session, a shell session (which
 * has no conversation at all), a session that has gone silent
 * ([SessionUiState.silent] — `E_NO_TRANSCRIPT`, not a failure), or, failing all
 * of those, an empty read.
 *
 * A plain composable, not a `ColumnScope` extension: its caller already wraps
 * it in the `Box` that also anchors the jump pill, and that `Box` — not this
 * one — carries the `Modifier.weight` that gives it the `LazyColumn`'s space.
 */
@Composable
private fun EmptyConversation(state: SessionUiState) {
    val text = when {
        state.session == null -> "This session was killed."
        state.session.kind == "shell" -> "Shell session — no conversation to show."
        state.silent -> "Nothing has been said yet — send a prompt to start."
        else -> "No turns yet."
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

/**
 * The fast way back down: a filled pill that floats over the conversation.
 *
 * Was an outlined `AssistChip`, whose container is transparent — floated over
 * a transcript, the monospace tool lines showed straight through its label
 * and "↓ New reply" was unreadable exactly when there was one.
 */
@Composable
private fun JumpToLatest(newReply: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (newReply) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (newReply) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 4.dp,
        modifier = modifier,
    ) {
        Text(
            text = if (newReply) "↓ New reply" else "↓ Latest",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TruncationNote() {
    Text(
        text = "Older turns are not shown.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

@Composable
private fun PromptBox(
    state: SessionUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onOpenHistory: () -> List<String>,
) {
    var showHistory by remember { mutableStateOf(false) }
    // No surface of its own: it sits in the footer `SessionScreen` draws.
    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
        val why = when {
            state.readOnly -> "This device is paired read-only."
            !state.connected -> "The hub is offline; the prompt will not be delivered."
            state.session == null -> "This session is gone."
            else -> null
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = state.draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                enabled = !state.sending && !state.readOnly,
                // One line: a long session name wrapped the placeholder
                // onto a second row and made an empty field look filled.
                placeholder = {
                    Text(
                        "Message ${state.session?.displayName ?: "session"}…",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                shape = CircleShape,
                // The "swipe up on the field shows history" spec, realised
                // as a tap on the field's own leading icon rather than a
                // gesture: a `TextField` already owns vertical drag for
                // text selection and cursor placement, so a swipe on it is
                // not free real estate the way it would be on a plain
                // `Row`.
                leadingIcon = {
                    IconButton(onClick = { showHistory = true }) {
                        Icon(FleetIcons.History, contentDescription = "Draft history")
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                // `maxLines = 6` says this box takes more than one line,
                // and `ImeAction.Send` took the key that would have written
                // them: Enter sent, so a prompt with a second line could
                // not be typed on a phone at all. The send button is beside
                // the field, always has been, and is the only thing that
                // sends now.
                //
                // Autocorrect and the leading capital are off for the same
                // reason they are off on the Pair screen: a prompt carries
                // paths, flags and identifiers — `--rerun-tasks`,
                // `SessionsViewModel.kt`, `feat/pager-phase-1` — and a
                // dictionary that rewrites those is not a convenience, it
                // is a silent edit to something about to be sent to an
                // agent.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Default,
                ),
                maxLines = 6,
            )
            // Bottom-aligned so it stays by the last line of a tall draft;
            // the 4 dp lifts it to the middle of the 56 dp field while the
            // draft is a single line, which is most of the time.
            FilledIconButton(
                onClick = onSend,
                enabled = state.canSend,
                modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
            ) {
                if (state.sending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(FleetIcons.Send, contentDescription = "Send")
            }
        }
        if (why != null) Text(why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
    if (showHistory) {
        HistoryDialog(
            entries = onOpenHistory(),
            // Puts the picked entry in the draft; it is not sent — the
            // person still taps Send (or edits it first), same as tapping a
            // suggestion anywhere else in this screen never fires by itself.
            onPick = { entry -> onDraftChange(entry); showHistory = false },
            onDismiss = { showHistory = false },
        )
    }
}

/** What was actually sent, most recent first — picking one loads it into the draft, unsent. */
@Composable
private fun HistoryDialog(entries: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Draft history") },
        text = {
            if (entries.isEmpty()) {
                Text("Nothing sent yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    for ((index, entry) in entries.withIndex()) {
                        Text(
                            text = entry,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(entry) }.padding(vertical = 10.dp),
                        )
                        if (index != entries.lastIndex) HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * The chip row above the composer: a [LazyRow] of [SuggestionChip]s, one tap
 * away from [onSendQuick], plus a trailing `+` chip that saves the current
 * draft as a new one. Long-pressing an existing chip opens
 * [EditQuickReplyDialog] rather than firing [onSendQuick] — see its own doc
 * for how "edit" and "remove" share one dialog. Visibility (hidden while
 * blocked or readonly) is the caller's decision, same as every other
 * card-vs-composer choice on this screen — see `SessionScreen`'s own body.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickRepliesRow(
    chips: List<String>,
    draft: String,
    enabled: Boolean,
    onSendQuick: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp),
    ) {
        items(chips) { chip ->
            Box(
                modifier = Modifier.combinedClickable(
                    onClick = { if (enabled) onSendQuick(chip) },
                    onLongClick = { editing = chip },
                ),
            ) {
                // `SuggestionChip`'s own `onClick` is not what fires here —
                // the `combinedClickable` above it is, so a chip's tap and
                // its long-press are the same gesture recognizer rather than
                // two independent ones that could both claim the same touch.
                SuggestionChip(onClick = {}, enabled = enabled, label = { Text(chip) })
            }
        }
        item {
            SuggestionChip(
                onClick = { onAdd(draft) },
                enabled = enabled && draft.isNotBlank(),
                label = { Text("+") },
            )
        }
    }
    editing?.let { chip ->
        EditQuickReplyDialog(
            original = chip,
            onSave = { edited -> onRemove(chip); onAdd(edited); editing = null },
            onRemove = { onRemove(chip); editing = null },
            onDismiss = { editing = null },
        )
    }
}

/**
 * A chip's long-press dialog: edit its text (removes the old chip and adds
 * the edited one — [dev.claudefleet.mobile.ui.QuickReplies] has no rename of
 * its own) or remove it outright.
 */
@Composable
private fun EditQuickReplyDialog(original: String, onSave: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(original) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick reply") },
        text = { TextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        },
    )
}

/**
 * Which **LazyColumn item** holds the newest turn, or null when there are none.
 *
 * Not `turns.lastIndex`: `scrollToItem` takes an item index, and the
 * truncation note occupies index 0 whenever `truncated` is set —
 * which is the normal case, since the hub sets it on any conversation longer
 * than its window. The target was one short, so the screen settled on the
 * second-to-last turn with the newest one below the fold: exactly the turn the
 * screen exists to show.
 *
 * A pure function because nothing in this repository can render a `LazyColumn`,
 * and an off-by-one that only a device can see is an off-by-one that ships.
 */
internal fun newestItemIndex(turns: Int, truncated: Boolean): Int? {
    if (turns <= 0) return null
    return turns - 1 + if (truncated) 1 else 0
}
