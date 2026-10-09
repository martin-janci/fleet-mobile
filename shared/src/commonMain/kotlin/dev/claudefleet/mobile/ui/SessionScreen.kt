package dev.claudefleet.mobile.ui

import androidx.compose.material3.VerticalDivider
import dev.claudefleet.mobile.ui.components.withFind
import dev.claudefleet.mobile.ui.components.LocalFindQuery
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.text.selection.SelectionContainer
import dev.claudefleet.mobile.model.relativeAgo
import dev.claudefleet.mobile.ui.components.DangerTextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import dev.claudefleet.mobile.ui.components.StatusDot
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.model.ConversationSummary
import dev.claudefleet.mobile.model.RepairReport
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.QuickReply
import dev.claudefleet.mobile.model.tailMarker
import dev.claudefleet.mobile.ui.components.BlockedCardView
import dev.claudefleet.mobile.ui.components.CompactChip
import dev.claudefleet.mobile.ui.components.ConnectionBanner
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.MarkdownText
import dev.claudefleet.mobile.ui.components.LocalComposerFill
import dev.claudefleet.mobile.ui.components.ChatHost
import dev.claudefleet.mobile.ui.components.LocalChatHost
import dev.claudefleet.mobile.model.PendingForm
import dev.claudefleet.mobile.model.progressBoard
import dev.claudefleet.mobile.data.ChatFormActions
import dev.claudefleet.mobile.ui.components.RichText
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.components.StatusStrip
import dev.claudefleet.mobile.ui.components.WorkChip
import dev.claudefleet.mobile.ui.components.contextIsTight
import dev.claudefleet.mobile.ui.components.statusStripText
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.data.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.IconButtonDefaults

/** The conversation list, for the device test that checks it follows new output. */
const val CONVERSATION_LIST: String = "conversation-list"

/** The quick-reply chip row, for the device test that taps and long-presses it. */
const val QUICK_REPLY_ROW: String = "quick-reply-row"

/** The widest a quick-reply caption draws before it ellipsizes. */
private val QUICK_REPLY_CAPTION_MAX = 200.dp

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
 * buttons is in [newestFirst] and [adjacentTurn].
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
    /** The chip row's own content — the fleet's list, see `ui/QuickReplies.kt`. */
    quickReplies: List<QuickReply>,
    /**
     * Whether this hub keeps the chip row at all (`quick_replies`). False
     * against one older than that tool: the chips still draw and still send —
     * they are cached on the device — but nothing offers to edit a list this
     * hub has nowhere to put. Defaults true so the screen's own tests and
     * previews need not know about it.
     */
    quickRepliesEditable: Boolean = true,
    onSendQuick: (String) -> Unit,
    onAddQuickReply: (QuickReply) -> Unit,
    onEditQuickReply: (QuickReply, QuickReply) -> Unit,
    onRemoveQuickReply: (QuickReply) -> Unit,
    /** Move a chip one place left (-1) or right (1); the default does nothing. */
    onMoveQuickReply: (QuickReply, Int) -> Unit = { _, _ -> },
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
    /** Every task of the session (the Work view's *Tasks*); the default draws nothing. */
    tasks: SessionTasksUiState = SessionTasksUiState(),
    tasksHandlers: SessionTasksHandlers = SessionTasksHandlers(),
    /**
     * What the tool rows can open (`session_tool_detail`); the default opens
     * nothing — an older hub, a preview, or a test that does not care.
     */
    toolDetails: ToolDetailsHost = ToolDetailsHost.None,
    /**
     * What a reply's settings card may call (step 10.8); the default calls
     * nothing, and the card says where to review the change instead.
     */
    chat: ChatHost = ChatHost.None,
    /** A waiting chat form's calls (`ask`); null where the hub does not list it for this device. */
    chatForms: ChatFormActions? = null,
    /**
     * Whether the once-only "double-tap for the whole screen" hint is still
     * owed ([Hints.DOUBLE_TAP]); [onFoldHintShown] is told the moment it goes
     * up. The default shows nothing — tests and previews.
     */
    showFoldHint: Boolean = false,
    onFoldHintShown: () -> Unit = {},
    /**
     * The reply actions that write: Rewind here (an anchor), Retry (an
     * anchor and the prompt to send again), Fork here (an anchor or null for
     * all of it, and a new worktree's name or null). Offered only while
     * [SessionUiState.canRewind]; the defaults do nothing.
     */
    onRewind: (String) -> Unit = {},
    onRetry: (String, String) -> Unit = { _, _ -> },
    onFork: (String?, String?) -> Unit = { _, _ -> },
    /** Open the session's Details sheet; the default does nothing. */
    onOpenDetails: () -> Unit = {},
    /** Earlier conversations and older turns; the defaults do nothing. */
    onLoadOlder: () -> Unit = {},
    onViewConversation: (ConversationSummary) -> Unit = {},
    onBackToCurrent: () -> Unit = {},
    /** The session's worktree screen; null where the hub serves none of it. */
    onOpenRepo: (() -> Unit)? = null,
    /** Recreate the session / let a ghost go; the defaults do nothing. */
    onRecreate: () -> Unit = {},
    onDismissGhost: () -> Unit = {},
    /** Start a review session with a prompt; repair the workspace; close a repair's report. */
    onReview: (String) -> Unit = {},
    onRepair: () -> Unit = {},
    onDismissRepair: () -> Unit = {},
    /** Press Enter in the pane (the ⏎ chip); the default does nothing. */
    onPressEnter: () -> Unit = {},
    /** Stop the working agent (Escape); the default does nothing. */
    onStop: () -> Unit = {},
    /** Move to another host; null where the hub or this pairing cannot. */
    onMove: (() -> Unit)? = null,
    /**
     * The session's tabs on the New bar (redesign 14.4); null on the Classic
     * bar, where this screen is the conversation alone, as before.
     */
    tabs: SessionTabsHost? = null,
    /** Answer the question up now with the draft ([SessionViewModel.answerInWords]); New bar only. */
    onAnswerInWords: () -> Unit = {},
    /**
     * Recovery on the New bar (redesign 14.5): send a kept "Not sent" prompt
     * again, put it back in the box, and send a failed session's last prompt
     * again. The defaults do nothing.
     */
    onRetryNotSent: () -> Unit = {},
    onEditNotSent: () -> Unit = {},
    onRetryLastTurn: (String) -> Unit = {},
    /** ⋮ Archive (redesign 14.14); null where this hub, pairing or bar has none. */
    onArchive: (() -> Unit)? = null,
    /** One more thing that went wrong, drawn with the screen's other banners (Archive's refusal). */
    notice: Friendly? = null,
    onDismissNotice: () -> Unit = {},
    /**
     * A key pressed in the agent's pane from its full-screen key bar
     * (redesign 14.21): Escape, Tab, Enter or C-c, while nothing is asked
     * ([SessionViewModel.pressKey]). The default does nothing.
     */
    onPaneKey: (String) -> Unit = {},
    /**
     * What takes the whole screen on the New bar (redesign 14.21): nothing,
     * the conversation without its header, or the agent's own screen. Kept by
     * the route, whose Back handler leaves it; the defaults never go there.
     */
    full: SessionFull = SessionFull.None,
    onFull: (SessionFull) -> Unit = {},
    /**
     * The conversation as the Control tab (redesign 9.8): Control's header
     * in place of the session's bar, and its confirms above the composer.
     * Null for every other session.
     */
    control: ControlChrome? = null,
) {
    // On the New bar the result sits in the conversation instead (RepairResultCard).
    if (tabs == null) state.repair?.let { RepairReportDialog(it, onDismissRepair) }
    val turns = state.conversation.turns
    val truncated = state.conversation.truncated
    // Newest first, under a `reverseLayout` list: item 0 is the newest turn
    // and is drawn against the bottom edge. A list that has never scrolled
    // (index 0, offset 0) is therefore already showing it on the very first
    // frame that has turns at all — there is no "scroll to the last item
    // once it has loaded" step, which is what used to draw the oldest turns
    // first and then visibly jump. See [newestFirst].
    val rows = remember(turns) { newestFirst(turns) }
    // `progress` cards of one id find each other across the conversation (RichCards.kt).
    val progress = remember(turns) {
        progressBoard(turns.flatMap { t -> t.items.mapNotNull { (it as? ConvItem.Text)?.text } })
    }
    // The form the row last carried, for a line saying how it ended once it drops it.
    var closedForm by remember(sessionId) { mutableStateOf<PendingForm?>(null) }
    val liveForm = state.session?.pendingForm
    LaunchedEffect(liveForm) { if (liveForm != null) closedForm = liveForm }
    val newestKey = rows.firstOrNull()?.key
    val lastItem = rows.size - 1 + if (truncated) 1 else 0
    // One state for the life of this screen — not keyed on the turns, so a
    // read never resets the reader's position.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val nearPx = with(LocalDensity.current) { NEAR_NEWEST.roundToPx() }
    // At the newest turn: item 0 at the bottom edge, give or take
    // [NEAR_NEWEST]. Derived from the list rather than latched, so scrolling
    // back down opts back in by itself. Before the list is measured at all it
    // reads index 0, offset 0 — at the newest — which is also the truth.
    val atBottom by remember(listState, nearPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= nearPx
        }
    }
    // True while this screen's own scroll to the newest turn is running. A
    // turn that arrives is first laid out below the fold (the list holds the
    // reader's item by key), so for the length of the animation that follows
    // it `atBottom` reads false; this keeps the jump pill and the view
    // model's `newReply` from flashing on for a reader who never left.
    var following by remember { mutableStateOf(false) }
    val followJob = remember { FollowJob() }

    // The chrome's focus — see `SessionChrome.kt` for the policy. `readingUp`
    // mirrors `direction` as Compose state; `immersive` is the double tap's
    // toggle; `promptFocused` is the field's own focus, which counts as
    // composing only while the keyboard is up: the field keeps focus after
    // the back gesture puts the keyboard away, and the header should come
    // back with it.
    val readingPx = with(LocalDensity.current) { READING_THRESHOLD.toPx() }
    val direction = remember(readingPx) { ReadingDirection(readingPx) }
    var readingUp by remember { mutableStateOf(false) }
    // A phone on its side opens folded; see [startsImmersive].
    val windowHeightDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }
    val short = startsImmersive(windowHeightDp)
    var immersive by remember(short) { mutableStateOf(short) }
    // The New bar's full screen (redesign 14.21): the conversation without its
    // header (⤢), or the agent's own screen edge to edge with a key bar.
    val fullScreen = tabs != null && full == SessionFull.Conversation
    val agentFull = tabs != null && full == SessionFull.Agent
    var promptFocused by remember { mutableStateOf(false) }
    var focusPrompt by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val readingWatch = remember(direction) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // What the list actually moved, under a finger: a fling, or a
                // drag at an end the list cannot move past, says nothing new.
                if (source == NestedScrollSource.UserInput) readingUp = direction.onDrag(consumed.y)
                return Offset.Zero
            }
        }
    }
    fun stopReading() {
        direction.reset()
        readingUp = false
    }
    val chromeInputs = ChromeInputs(
        atBottom = atBottom || following,
        readingUp = readingUp,
        immersive = immersive,
        composing = promptFocused && keyboardVisible(),
        hasDraft = state.draft.isNotEmpty(),
        needsAnswer = state.card != null,
    )
    // Find in the conversation: the query, and which match is shown (an
    // index into `matches`, newest first like the list).
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findAt by remember { mutableStateOf(0) }
    // The New bar's scopes (redesign 14.14); the Classic bar always finds in Everything.
    var findScope by remember { mutableStateOf(FindScope.Everything) }
    val matches = remember(rows, findQuery, findScope) { findTurns(rows, findQuery, findScope) }
    fun showMatch(at: Int) {
        if (matches.isEmpty()) return
        findAt = at.mod(matches.size)
        stopReading()
        scope.launch { listState.showTurn(matches[findAt]) }
    }
    val header = headerChrome(chromeInputs)
    val footer = footerChrome(chromeInputs)

    fun scrollToNewest() {
        stopReading()
        val previous = followJob.job
        following = true
        val job = scope.launch { listState.animateScrollToItem(0) }
        // Swapped in before the old one is cancelled: a job cancelled before
        // it ever ran completes on the spot, and its handler must not see
        // itself as the current one and clear `following` under the new one.
        followJob.job = job
        previous?.cancel()
        job.invokeOnCompletion { if (followJob.job === job) following = false }
    }

    // The view model's own copy of `atBottom` — used for `newReply` and the
    // unseen count — follows the screen's, not the other way round: the
    // screen is the one thing that can actually see the list.
    val shownAtBottom = atBottom || following
    LaunchedEffect(shownAtBottom) { onAtBottom(shownAtBottom) }
    // Back at the newest turn by any route ends a read-back: the next drag
    // up starts a new one from zero.
    LaunchedEffect(atBottom) { if (atBottom) stopReading() }

    // The first time reading back folds the chrome, say once that a double
    // tap does it on purpose. Marked shown as it goes up, not when it goes
    // away: it is a hint, not something to be acknowledged. Keyed on
    // `readingUp` alone, so the owed flag flipping does not cancel it.
    var foldHintUp by remember { mutableStateOf(false) }
    val foldHintOwed by rememberUpdatedState(showFoldHint && !immersive)
    LaunchedEffect(readingUp) {
        if (readingUp && foldHintOwed) {
            onFoldHintShown()
            foldHintUp = true
            try {
                delay(FOLD_HINT_MS)
            } finally {
                foldHintUp = false
            }
        }
    }

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
                    atBottom = atBottom || following,
                    firstVisibleKey = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key,
                ),
            )
        }
    }

    // Switching conversation — to an earlier one, or back to the current —
    // opens it at its newest turn, not at an index carried over from the other.
    val viewingId = state.viewing?.claudeSessionId
    val shownConversation = remember(sessionId) { mutableStateOf(viewingId) }
    LaunchedEffect(viewingId) {
        if (shownConversation.value != viewingId) {
            shownConversation.value = viewingId
            listState.scrollToItem(0)
        }
    }

    // What the tail looked like the last time this screen was composed, so a
    // change to it can be told apart from any other recomposition.
    val tail = remember(sessionId) { TailWatch() }
    val marker = state.conversation.tailMarker()
    // A `SideEffect`, not a `LaunchedEffect`: it runs once the composition
    // that carries the new turns is applied and BEFORE the frame that lays
    // them out, so `listState` still describes what the reader was looking
    // at — which is the whole question. A `LaunchedEffect` coroutine could
    // run on either side of that layout, and after it the list has already
    // moved its index to keep the reader's item in place.
    SideEffect {
        val previousKey = tail.newestKey
        val previousMarker = tail.marker
        tail.newestKey = newestKey
        tail.marker = marker
        if (newestKey == null) return@SideEffect
        if (previousKey == null) {
            // The first turns this screen shows for this session. A
            // remembered anchor — one the reader was NOT at the bottom of when
            // it was taken, see [ScrollMemory.remember] — wins over the newest
            // turn: that is "open where you left off". Requested rather than
            // scrolled to, so it lands in the same layout as the turns
            // themselves instead of a frame later. With none, index 0: a
            // fresh state already is, and one carried over from another
            // session at this call site must not keep that session's place.
            // Not for an earlier conversation: the anchor is the current one's,
            // and its index landed a reader mid-way through the other.
            val recalled = if (state.viewing != null) null else ScrollMemory.recall(sessionId)?.takeIf { !it.atBottom }
            if (recalled != null) {
                val index = rows.indexOfFirst { it.key == recalled.firstVisibleKey }.takeIf { it >= 0 }
                    ?: if (recalled.firstVisibleKey == TRUNCATED_KEY && truncated) rows.size else null
                listState.requestScrollToItem(
                    index ?: recalled.firstVisibleIndex.coerceIn(0, lastItem),
                    recalled.firstVisibleOffset,
                )
            } else if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                listState.requestScrollToItem(0)
            }
            return@SideEffect
        }
        if (previousKey == newestKey && previousMarker == marker) return@SideEffect
        val index = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        // The live turn grew under a reader pinned to its end: `reverseLayout`
        // keeps that end against the bottom edge by itself, nothing to scroll.
        if (previousKey == newestKey && index == 0 && offset == 0) return@SideEffect
        // The tail moved: a turn arrived, or the live one grew. Follow it only
        // for a reader who was already at the newest turn (or on the way
        // there) and is not in the middle of scrolling away from it; anyone
        // scrolled up to read stays exactly where they are — the list holds
        // their item by key — and the pill counts what arrived.
        val wasAtBottom = following ||
            (!listState.isScrollInProgress && index == 0 && offset <= nearPx)
        if (wasAtBottom) scrollToNewest()
    }

    // Measured for the blocked card's cap (see [CARD_MAX_FRACTION]).
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val cardMax = maxHeight * CARD_MAX_FRACTION
        // Landscape on the New bar (redesign 14.21): the conversation on the
        // left, one of the session's other tabs on the right.
        val wide = tabs != null && twoPaneWide(maxWidth.value, maxHeight.value)
        // The tab that was open in portrait, put back when the phone is turned upright again.
        var uprightTab by remember { mutableStateOf<SessionTab?>(null) }
        var wasWide by remember { mutableStateOf(false) }
        if (tabs != null) {
            // Keyed on the tab too: the conversation is the left pane, so a
            // switch to it (a notification, "ask in the conversation") leaves
            // the right pane on a tab of its own rather than on nothing.
            LaunchedEffect(wide, tabs.selected) {
                if (wide) {
                    if (!wasWide) {
                        uprightTab = tabs.selected
                        wasWide = true
                    }
                    sideTabFor(tabs.selected, tabs.tabs).let { if (it != tabs.selected) tabs.onSelect(it) }
                } else if (wasWide) {
                    wasWide = false
                    uprightTab?.let { if (it != tabs.selected && it in tabs.tabs) tabs.onSelect(it) }
                    uprightTab = null
                }
            }
        }
        val side = tabs?.let { sideTabFor(it.selected, it.tabs) }
        // An open diff in landscape takes the whole width, side by side.
        val sideWhole = wide && tabs?.sideWhole == true && side == SessionTab.Files
        HideSystemBars(hidden = tabs != null && (wide || fullScreen || agentFull))
        Row(modifier = Modifier.fillMaxSize()) {
        // Kept composed while a diff has the width, so the conversation comes back where it was.
        Column(modifier = (if (sideWhole) Modifier.width(0.dp) else Modifier.weight(1f)).fillMaxHeight()) {
            if (!fullScreen && control != null) {
                control.header()
            } else if (!fullScreen) AnimatedContent(
                targetState = header,
                transitionSpec = { chromeTransition() },
                label = "session header",
            ) { shown ->
                when (shown) {
                    Chrome.Full -> SessionBar(
                        state = state,
                        onBack = onBack,
                        onRefresh = onRefresh,
                        listState = listState,
                        turnCount = turns.size,
                        scope = scope,
                        onRestart = onRestart,
                        onSafeKill = onSafeKill,
                        onKill = onKill,
                        onSetTags = onSetTags,
                        onRename = onRename,
                        onSendCommand = onSendCommand,
                        work = work,
                        workHandlers = workHandlers,
                        tasks = tasks,
                        onOpenTasks = tasksHandlers.onOpen,
                        onOpenDetails = onOpenDetails,
                        onViewConversation = onViewConversation,
                        onOpenRepo = onOpenRepo,
                        onRecreate = onRecreate,
                        onDismissGhost = onDismissGhost,
                        onReview = onReview,
                        onRepair = onRepair,
                        onFind = { findOpen = !findOpen; if (!findOpen) { findQuery = ""; findScope = FindScope.Everything } },
                        onMove = onMove,
                        onFullScreen = { onFull(SessionFull.Conversation) }.takeIf { tabs != null },
                        orbit = tabs?.let {
                            OrbitMenu(
                                onArchive = onArchive,
                                onTicket = workHandlers.onOpen.takeIf { work.chip != null || work.canSetWork || work.canNameWork },
                                ticketKey = work.chip?.key,
                                onTasks = tasksHandlers.onOpen.takeIf { tasks.available },
                            )
                        },
                    )
                    // A tap unfolds it: out of immersive, out of the read-back,
                    // and — when typing is what folded it — the keyboard down.
                    Chrome.Compact -> CompactSessionBar(
                        state = state,
                        onBack = onBack,
                        onExpand = {
                            immersive = false
                            stopReading()
                            focusManager.clearFocus()
                        },
                    )
                }
            }
            if (findOpen) {
                // A new scope starts from its newest match, once `matches` holds that scope's.
                LaunchedEffect(findScope) { findAt = 0; showMatch(0) }
                FindBar(
                    query = findQuery,
                    at = if (matches.isEmpty()) 0 else findAt + 1,
                    count = matches.size,
                    onQuery = { findQuery = it; findAt = 0; if (it.isNotBlank()) showMatch(0) },
                    // Older is further up the list: a higher index.
                    onOlder = { showMatch(findAt + 1) },
                    onNewer = { showMatch(findAt - 1) },
                    onClose = { findOpen = false; findQuery = ""; findScope = FindScope.Everything },
                    scope = findScope.takeIf { tabs != null },
                    onScope = { findScope = it },
                )
            }
            ConnectionBanner(status, state.hubReachable)
            // A send's failure is drawn by the composer, where the thumb is.
            if (!state.errorFromSend) ErrorBanner(state.error, onDismiss = onDismissError)
            ErrorBanner(notice, onDismiss = onDismissNotice)
            // Behind an open sheet a banner cannot be read: the sheet shows it instead.
            if (!work.sheetOpen) ErrorBanner(work.error, onDismiss = workHandlers.onDismissError)
            if (work.sheetOpen) WorkTicketSheet(work, workHandlers)
        state.viewing?.let { EarlierConversationBanner(it, state.nowSeconds, state.loadingOlder, onBackToCurrent) }
            if (tasks.sheetOpen) SessionTasksSheet(tasks, tasksHandlers)
            // The New bar's tabs: under the header, and still there when a
            // read-back folds it, so another tab is never more than a tap away.
            if (!wide && !fullScreen) tabs?.let { SessionTabRow(it) }
            val pane = tabs?.selected?.takeIf { it != SessionTab.Conversation && !wide }
            if (tabs != null && pane != null) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (pane) {
                        SessionTab.Agent -> AgentPane(
                            state = state,
                            agent = tabs.agent,
                            onCapture = onShowTerminal,
                            onAnswer = onAnswer,
                            onFullScreen = { onFull(SessionFull.Agent) },
                        )
                        SessionTab.Terminals -> tabs.terminals()
                        SessionTab.Files -> tabs.files()
                        SessionTab.Details -> tabs.details()
                        SessionTab.Conversation -> Unit
                    }
                }
            } else {

            // `weight(1f)`: the list takes what the bar and the footer leave, so
            // it shrinks when the keyboard raises the footer (`App` applies the
            // IME inset, once, with the rest of `safeDrawing`), and with
            // `reverseLayout` the newest turn stays against the footer as it does.
            //
            // `readingWatch` hears every drag the list moves by; the double tap
            // toggles the whole screen for the conversation. A turn's own
            // clickable rows consume their taps first, so a double tap there
            // stays theirs and does not also toggle.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(readingWatch)
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { immersive = !immersive }) },
            ) {
                if (state.loaded && turns.isEmpty()) {
                    EmptyConversation(state, onRetry = onRefresh)
                } else {
                    // Tagged so a device test can address this list rather than
                    // guessing which of the screen's scrollable nodes it meant.
                    // `atBottom` above is derived from measurement, so it only
                    // means anything where there is measurement, and the test that
                    // checks it has to run on a device — see `ConversationScrollTest`.
                    CompositionLocalProvider(
                        LocalToolDetails provides toolDetails,
                        LocalChatHost provides chat.copyWith(progress = progress, nowSeconds = state.nowSeconds),
                        LocalFindQuery provides if (findOpen) findQuery else "",
                        // A reply's cards (RichCards.kt) act by filling the
                        // composer, after what is typed, never by sending.
                        LocalComposerFill provides if (!state.readOnly && state.session != null) {
                            { text: String -> onDraftChange(appendToDraft(state.draft, text)) }
                        } else {
                            null
                        },
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().testTag(CONVERSATION_LIST),
                            // Item 0 — the newest turn — at the bottom.
                            reverseLayout = true,
                            // Room under the newest turn, so it does not sit flush on the composer.
                            contentPadding = PaddingValues(bottom = 12.dp),
                        ) {
                            turnItems(
                                rows,
                                working = state.session?.claudeStatus == "working",
                                replies = ReplyHost(
                                    turns = turns,
                                    truncated = truncated,
                                    supported = state.canRewind,
                                    canQuote = !state.readOnly && state.session != null,
                                    forkName = forkWorktreeName(state.session?.displayName ?: "session"),
                                    onQuote = { quoted -> onDraftChange(quoted + state.draft) },
                                    onRewind = onRewind,
                                    onRetry = { anchor, prompt -> scrollToNewest(); onRetry(anchor, prompt) },
                                    onFork = onFork,
                                ),
                            )
                            // After the oldest turn, so drawn above it.
                            if (truncated) {
                                item(key = TRUNCATED_KEY, contentType = TRUNCATED_KEY) {
                                    TruncationNote(canLoadOlder = state.canLoadOlder, loading = state.loadingOlder, onLoadOlder = onLoadOlder)
                                }
                            }
                        }
                    }
                }
                // The once-only double-tap hint, over the top of the
                // conversation (see `foldHintUp`).
                // Qualified: inside the screen's Column the ColumnScope overload is the
                // one resolved, and it cannot be called from this Box.
                androidx.compose.animation.AnimatedVisibility(
                    visible = foldHintUp,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    ) {
                        Text(
                            text = "Double-tap for the whole screen",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                // The fast way back down, for whoever scrolled up to read
                // something and either wants the bottom again or got fresh
                // turns while they were up there — see `SessionUiState.newReply`
                // and `SessionUiState.unseen`.
                if (!shownAtBottom) {
                    // Reading back folds the header, and its ▲/▼ with it; the
                    // same steps ride beside the pill, where the thumb is.
                    val olderTurn by remember(listState, turns.size) {
                        derivedStateOf { adjacentTurn(listState.firstVisibleItemIndex, turns.size, -1) }
                    }
                    val newerTurn by remember(listState, turns.size) {
                        derivedStateOf { adjacentTurn(listState.firstVisibleItemIndex, turns.size, 1) }
                    }
                    Row(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TurnStep("▲", "Previous turn", olderTurn != null) {
                            olderTurn?.let { target -> scope.launch { listState.showTurn(target) } }
                        }
                        JumpToLatest(
                            newReply = state.newReply,
                            unseen = state.unseen,
                            onClick = {
                                scrollToNewest()
                                // Clears `newReply` and the count now rather than
                                // when the animation lands; `following` keeps the
                                // screen's own report true for the length of it.
                                onAtBottom(true)
                            },
                        )
                        TurnStep("▼", "Next turn", newerTurn != null) {
                            newerTurn?.let { target -> scope.launch { listState.showTurn(target) } }
                        }
                    }
                }
            }

            // A chat form the agent waits on (`ask`), where the answer goes;
            // once the row drops it, how it ended, until dismissed.
            val formShown = state.session?.pendingForm ?: closedForm
            if (formShown != null && state.viewing == null && (state.session?.pendingForm != null || chatForms != null)) {
                ChatFormCard(
                    pending = formShown,
                    sessionName = state.session?.displayName ?: "The session",
                    actions = chatForms,
                    canAnswer = !state.readOnly && state.connected,
                    open = state.session?.pendingForm != null,
                    onDismiss = { closedForm = null },
                    modifier = Modifier.heightIn(max = cardMax),
                    orbit = tabs != null || control != null,
                    onAskAgain = {
                        onSendQuick("The form \"${formShown.title}\" expired before I answered it. Please ask it again.")
                        closedForm = null
                    }.takeIf { !state.readOnly && state.connected },
                )
            }
            control?.aboveComposer?.invoke()

            // Recovery on the New bar (redesign 14.5): at the foot of the
            // conversation, where the next step is taken — never behind ⋮.
            val failed = if (tabs != null && state.card == null && state.viewing == null) {
                failedSession(state.session, state.conversation.turns)
            } else {
                null
            }
            if (tabs != null) {
                state.repair?.let { report ->
                    RepairResultCard(
                        report = report,
                        agent = tabs.agent,
                        onShowChanges = { tabs.onSelect(SessionTab.Files) }.takeIf { SessionTab.Files in tabs.tabs },
                        onAskToCommit = {
                            onDraftChange(appendToDraft(state.draft, ASK_TO_COMMIT))
                            immersive = false
                            stopReading()
                            focusPrompt = true
                        }.takeIf { SessionTab.Files in tabs.tabs && !state.readOnly && state.session != null },
                        onDone = onDismissRepair,
                        modifier = Modifier.heightIn(max = cardMax),
                    )
                }
                failed?.let { f ->
                    FailedSessionCard(
                        failed = f,
                        canWrite = state.canSendQuick,
                        onRetry = f.retryPrompt?.let { prompt -> { scrollToNewest(); onRetryLastTurn(prompt) } }.takeIf { !state.readOnly },
                        onRepair = onRepair.takeIf { state.canRepair },
                        onDetails = { tabs.onSelect(SessionTab.Details) },
                        modifier = Modifier.heightIn(max = cardMax),
                    )
                }
                state.notSent?.let { n ->
                    NotSentCard(
                        notSent = n,
                        canRetry = state.canSendQuick && state.card == null,
                        onRetry = { scrollToNewest(); onRetryNotSent() },
                        onEdit = {
                            onEditNotSent()
                            immersive = false
                            stopReading()
                            focusPrompt = true
                        },
                        modifier = Modifier.heightIn(max = cardMax),
                    )
                }
            }

            // Between the conversation and the composer: a person who opened this
            // screen because the agent is waiting should not have to scroll to
            // answer it, and the card sits where the answer goes.
            state.card?.let { card ->
                if (tabs != null) {
                    QuestionCard(
                        card = card,
                        state = state,
                        agent = tabs.agent,
                        asking = if (card.offerRestart) null else pendingTool(state.conversation.turns),
                        onAnswer = onAnswer,
                        onAnswerInWords = {
                            immersive = false
                            stopReading()
                            focusPrompt = true
                        },
                        onShowAgent = { tabs.onSelect(SessionTab.Agent) },
                        onRestart = if (state.canRestart) onRestart else null,
                        modifier = Modifier.heightIn(max = cardMax),
                    )
                    return@let
                }
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
                    asking = if (card.offerRestart) null else pendingTool(state.conversation.turns),
                    // Capped, and scrolls inside: an explanation, a row of
                    // answers and the terminal under them used to be able to take
                    // the conversation's whole height — the footer stays full
                    // while the card is up, so nothing else gave way.
                    modifier = Modifier.heightIn(max = cardMax),
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
                    AnimatedContent(
                        targetState = footer,
                        transitionSpec = { chromeTransition() },
                        label = "session footer",
                    ) { shown ->
                        when (shown) {
                            Chrome.Full -> Column {
                                // The desktop's slash menu: while the draft is one
                                // `/word`, the commands it could be; a tap fills it in.
                                val slash = if (state.readOnly) emptyList() else matchSlashCommands(state.draft)
                                if (slash.isNotEmpty()) SlashSuggestions(slash, onPick = { onDraftChange(completeSlashCommand(it)) })
                                // Hidden outright, not merely dimmed, in the same two cases
                                // the card itself takes over the space for: while it is up
                                // (the answer goes there instead) and on a readonly device (no
                                // chip may offer a write it cannot make) — spec 1.1.
                                if (failed != null && tabs != null && !state.readOnly) {
                                    // A failed session's chips fit the failure (MobileRecovery).
                                    FailedQuickReplies(
                                        enabled = state.canSendQuick,
                                        onRetry = failed.retryPrompt?.let { prompt -> { scrollToNewest(); onRetryLastTurn(prompt) } },
                                        onShowError = { tabs.onSelect(SessionTab.Agent) },
                                    )
                                } else if (state.card == null && !state.readOnly) {
                                    QuickRepliesRow(
                                        chips = quickReplies,
                                        draft = state.draft,
                                        enabled = state.canSendQuick,
                                        editable = quickRepliesEditable,
                                        // A chip that sends is the reader answering: show
                                        // them the newest turn, where the answer will land.
                                        onSendQuick = { scrollToNewest(); onSendQuick(it) },
                                        onFill = onDraftChange,
                                        onAdd = onAddQuickReply,
                                        onEdit = onEditQuickReply,
                                        onRemove = onRemoveQuickReply,
                                        onMove = onMoveQuickReply,
                                    )
                                }
                                PromptBox(
                                    state = state,
                                    onDraftChange = onDraftChange,
                                    // Whoever just sent wants to see it land, wherever they
                                    // had scrolled to — and once at the newest turn, the
                                    // turn their prompt starts is followed like any other.
                                    onSend = { scrollToNewest(); onSend() },
                                    onOpenHistory = onOpenHistory,
                                    onPressEnter = { scrollToNewest(); onPressEnter() },
                                    onStop = onStop,
                                    sendError = state.error.takeIf { state.errorFromSend },
                                    onDismissSendError = onDismissError,
                                    onFocusChange = { promptFocused = it },
                                    focusNow = focusPrompt,
                                    onFocused = { focusPrompt = false },
                                    // Open for the whole time a question with a No is up — not
                                    // only while an answer could go out — so the keyboard is not
                                    // dropped mid-answer. Send itself waits on `canSendWords`.
                                    wordsMode = tabs != null && !state.readOnly && state.card?.let(::declineOption) != null,
                                    agent = tabs?.agent,
                                    onAnswerInWords = { scrollToNewest(); onAnswerInWords() },
                                    // The New bar names what Send does while Claude works: Queue.
                                    queueLabel = tabs != null && state.session?.claudeStatus == "working",
                                )
                            }
                            // The pill a folded footer leaves. A tap is someone
                            // about to write: unfold, and put the cursor in the
                            // field — but not move the list, since what they are
                            // answering may be the turn they scrolled up to.
                            Chrome.Compact -> PromptPill(
                                state = state,
                                onClick = {
                                    immersive = false
                                    stopReading()
                                    if (!state.readOnly) focusPrompt = true
                                },
                            )
                        }
                    }
                }
            }
            }
        }
        if (wide && tabs != null && side != null) {
            if (!sideWhole) VerticalDivider(color = Fleet.colors.border)
            SidePane(
                tabs = tabs,
                side = side,
                state = state,
                onShowTerminal = onShowTerminal,
                onAnswer = onAnswer,
                onAgentFull = { onFull(SessionFull.Agent) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        }
        // Portrait full screen's way back, where the header was.
        if (fullScreen && !agentFull) {
            IconButton(
                onClick = { onFull(SessionFull.None) },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            ) { Icon(FleetIcons.Collapse, contentDescription = "Leave full screen") }
        }
        if (agentFull && tabs != null) {
            AgentFullscreen(
                state = state,
                agent = tabs.agent,
                onPress = { press ->
                    when (press) {
                        is AgentPress.Card -> onAnswer(press.answer)
                        is AgentPress.Key -> onPaneKey(press.key)
                    }
                },
                onSendLine = onSendCommand,
                onCapture = onShowTerminal,
                onExit = { onFull(SessionFull.None) },
            )
        }
    }
}

/**
 * The right-hand pane of a session in landscape (redesign 14.21): the
 * session's tabs but the conversation, which is the left pane, and the one
 * chosen. The bottom bar is not drawn on a session in any orientation.
 */
@Composable
private fun SidePane(
    tabs: SessionTabsHost,
    side: SessionTab,
    state: SessionUiState,
    onShowTerminal: () -> Unit,
    onAnswer: (Answer) -> Unit,
    onAgentFull: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.testTag(SIDE_PANE_TAG)) {
        SessionTabRow(tabs, shown = sideTabs(tabs.tabs), selected = side)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (side) {
                SessionTab.Agent -> AgentPane(
                    state = state,
                    agent = tabs.agent,
                    onCapture = onShowTerminal,
                    onAnswer = onAnswer,
                    onFullScreen = onAgentFull,
                )
                SessionTab.Terminals -> tabs.terminals()
                SessionTab.Files -> tabs.files()
                SessionTab.Details -> tabs.details()
                SessionTab.Conversation -> Unit
            }
        }
    }
}

const val SIDE_PANE_TAG = "session.side"

/**
 * The turns, newest first ([newestFirst]), each keyed by its identity rather
 * than its position, so that turns arriving at the newest end leave every
 * other item's key alone and the list can hold a scrolled-up reader still.
 */
private fun LazyListScope.turnItems(rows: List<TurnRow>, working: Boolean, replies: ReplyHost? = null) {
    itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, _ -> "turn" }) { index, row ->
        val live = working && index == 0
        // Centred at a reading width: on a tablet a line the screen's whole
        // width is too long to follow back to its start.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(modifier = Modifier.widthIn(max = READING_WIDTH)) {
                Turn(
                    row.turn,
                    live = live,
                    // Not on the live turn: it is still being written, and the
                    // hub refuses a rewind of a session that is not quiet.
                    footer = if (replies != null && !live) {
                        { ReplyMenu(row.turn, chronological = rows.size - 1 - index, host = replies) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** The truncation note's key, and its content type. */
internal const val TRUNCATED_KEY: String = "truncated"

/**
 * How far above the newest turn's end a reader can be and still count as at
 * the bottom — still followed when the tail moves, and shown no jump pill.
 */
private val NEAR_NEWEST = 48.dp

/** The widest a turn draws; a phone is narrower than this, a tablet is not. */
private val READING_WIDTH = 720.dp

/** How long the double-tap hint stays up. */
private const val FOLD_HINT_MS = 3_500L

/**
 * How far one drag has to run, one way, before the chrome folds (towards
 * older turns) or unfolds (towards the newest) — [ReadingDirection]'s dead
 * band, so a finger resting on the list does not flicker the header.
 */
private val READING_THRESHOLD = 24.dp

/** Folding and unfolding the header and footer: a quick cross-fade while the height animates. */
private fun AnimatedContentTransitionScope<Chrome>.chromeTransition(): ContentTransform =
    (fadeIn(tween(150, delayMillis = 60)) togetherWith fadeOut(tween(90)))
        .using(SizeTransform(clip = true) { _, _ -> tween(220) })

/**
 * The header folded to one line: back, the session's status dot, its name
 * and the status strip's text under it. The rest of the line is one target that unfolds the full header —
 * everything the full one carries (status strip, turn arrows, ticket and
 * tasks chips, the ⋮ menu) is a tap away rather than gone.
 */
@Composable
private fun CompactSessionBar(state: SessionUiState, onBack: () -> Unit, onExpand: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(COMPACT_BAR_HEIGHT)
                    .padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(FleetIcons.ArrowBack, contentDescription = "Back")
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(onClickLabel = "Show the session header", onClick = onExpand),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(state.session?.claudeStatus, state.session?.stuckKind)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.session?.displayName ?: "Session",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // The strip's own words, small: a dot alone said
                        // working but not for how long or how full.
                        val status = statusStripText(state.session, state.conversation.context, state.nowSeconds)
                        if (status.isNotBlank()) {
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** The folded header's height: the 48 dp touch target and no more. */
private val COMPACT_BAR_HEIGHT = 48.dp

/**
 * The footer folded to one line: what the field would say, as a pill. A
 * draft never folds (see [footerChrome]), so there is nothing here to lose.
 */
@Composable
private fun PromptPill(state: SessionUiState, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (state.readOnly) "Read-only" else "Message ${state.session?.displayName ?: "session"}…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The scroll that follows the newest turn, so a second one can replace it. */
private class FollowJob {
    var job: Job? = null
}

/** What the tail looked like at the screen's last composition; see `SessionScreen`'s `SideEffect`. */
private class TailWatch {
    var newestKey: String? = null
    var marker: Pair<Int, String?>? = null
}

/**
 * Bring a turn into view from its start — the prompt — rather than its end.
 *
 * In a `reverseLayout` list `animateScrollToItem` lines the item's **bottom**
 * up with the bottom of the viewport. A turn shorter than the viewport is
 * then wholly visible; a taller one would show its last lines, so the rest of
 * the way is scrolled to put its top at the top. The item stays the first
 * visible one throughout, which is what keeps [adjacentTurn] stepping from it.
 */
private suspend fun LazyListState.showTurn(index: Int) {
    animateScrollToItem(index)
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewport = layoutInfo.viewportSize.height - layoutInfo.beforeContentPadding - layoutInfo.afterContentPadding
    val overflow = item.size - viewport
    if (overflow > 0) animateScrollBy(overflow.toFloat())
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
    scope: CoroutineScope,
    onRestart: () -> Unit,
    onSafeKill: () -> Unit,
    onKill: () -> Unit,
    onSetTags: (List<String>) -> Unit,
    onRename: (String) -> Unit,
    onSendCommand: (String) -> Unit,
    work: SessionWorkUiState,
    workHandlers: SessionWorkHandlers,
    tasks: SessionTasksUiState,
    onOpenTasks: () -> Unit,
    onOpenDetails: () -> Unit,
    onViewConversation: (ConversationSummary) -> Unit,
    onOpenRepo: (() -> Unit)?,
    onRecreate: () -> Unit,
    onDismissGhost: () -> Unit,
    onReview: (String) -> Unit,
    onRepair: () -> Unit,
    onFind: () -> Unit,
    onMove: (() -> Unit)?,
    orbit: OrbitMenu? = null,
    /** ⤢: the conversation full screen; New bar only. */
    onFullScreen: (() -> Unit)? = null,
) {
    val busy = state.loading || state.refreshing
    var pickingConversation by remember { mutableStateOf(false) }
    if (pickingConversation) {
        ConversationsDialog(
            conversations = state.conversations,
            viewing = state.viewing,
            nowSeconds = state.nowSeconds,
            onPick = { pickingConversation = false; onViewConversation(it) },
            onDismiss = { pickingConversation = false },
        )
    }
    val angle = refreshAngle(busy)
    // Recomputed from `listState.firstVisibleItemIndex` — a snapshot-backed
    // read, and in this `reverseLayout` list the item at the bottom of the
    // viewport — whenever it moves; see [adjacentTurn] for the index
    // arithmetic of a newest-first list.
    val prevTurn by remember(listState, turnCount) {
        derivedStateOf { adjacentTurn(listState.firstVisibleItemIndex, turnCount, -1) }
    }
    val nextTurn by remember(listState, turnCount) {
        derivedStateOf { adjacentTurn(listState.firstVisibleItemIndex, turnCount, 1) }
    }
    ScreenHeader(
        title = state.session?.displayName ?: "Session",
        // The host is in the header because a prompt goes to a machine, not
        // just to a name.
        subtitle = state.session?.hostAlias ?: "no longer in the fleet",
        titleStyle = MaterialTheme.typography.titleMedium,
        // The session's Details: a readonly pairing has no ⋮ menu, so the
        // title is the one way in that every pairing has.
        onTitleClick = onOpenDetails,
        navigation = {
            IconButton(onClick = onBack) {
                Icon(FleetIcons.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = onFind) { Icon(FleetIcons.Search, contentDescription = "Find in conversation") }
            if (onFullScreen != null) IconButton(onClick = onFullScreen) { Icon(FleetIcons.Expand, contentDescription = "Full screen") }
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
            if (state.canManage || work.canSetWork || work.canNameWork) {
                SessionOverflowMenu(
                    state = state,
                    onRestart = onRestart,
                    onSafeKill = onSafeKill,
                    onKill = onKill,
                    onSetTags = onSetTags,
                    onRename = onRename,
                    onSetWork = workHandlers.onSetWork.takeIf { work.canSetWork },
                    onNameWork = workHandlers.onNameWork.takeIf { work.canNameWork },
                    onDetails = onOpenDetails,
                    onRepo = onOpenRepo,
                    onRecreate = onRecreate,
                    onDismissGhost = onDismissGhost,
                    onReview = onReview,
                    onRepair = onRepair,
                    onSendCommand = onSendCommand,
                    onMove = onMove,
                    orbit = orbit,
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
                    onClick = { prevTurn?.let { target -> scope.launch { listState.showTurn(target) } } },
                    enabled = prevTurn != null,
                ) {
                    Icon(
                        FleetIcons.ArrowBack,
                        contentDescription = "Previous turn",
                        modifier = Modifier.rotate(90f),
                    )
                }
                IconButton(
                    onClick = { nextTurn?.let { target -> scope.launch { listState.showTurn(target) } } },
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
            val manyConversations = state.conversations.size > 1
            if (tight || retiring != null || ticket != null || tasks.available || manyConversations) {
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
                    // A guess beside confirmed work gets its own dashed chip,
                    // so the suggestion the sheet asks about is on screen too.
                    work.suggestion?.takeIf { it.besideConfirmed }?.let { guess ->
                        WorkChip(
                            work = guess.work,
                            suggested = true,
                            onClick = workHandlers.onOpen,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                    // Every task of the session, not only the primary the
                    // ticket chip shows: the Work view's *Tasks* section.
                    if (tasks.available) {
                        SuggestionChip(
                            onClick = onOpenTasks,
                            label = { Text(if (tasks.count > 0) "Tasks · ${tasks.count}" else "Tasks", maxLines = 1) },
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                    // A `/clear`, a resume, a compaction or a rewind started
                    // another conversation in this session: the earlier ones
                    // are a tap away, read-only.
                    if (manyConversations) {
                        SuggestionChip(
                            onClick = { pickingConversation = true },
                            label = { Text("Conversations · ${state.conversations.size}", maxLines = 1) },
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
                        // Information, not a control: drawn as a label, so it does not invite a tap that does nothing.
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(
                                "retire: $retiring",
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
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
    /** *Name this work…*; null unless the session has no work and this token and hub may name it. */
    onNameWork: ((String, String?) -> Unit)? = null,
    onDetails: () -> Unit = {},
    onRepo: (() -> Unit)? = null,
    onRecreate: () -> Unit = {},
    onDismissGhost: () -> Unit = {},
    onReview: (String) -> Unit = {},
    onRepair: () -> Unit = {},
    onSendCommand: (String) -> Unit = {},
    onMove: (() -> Unit)? = null,
    /** The New bar's written-out menu (redesign 14.14); null keeps the Classic one. */
    orbit: OrbitMenu? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }
    var showReview by remember { mutableStateOf(false) }
    var showRepairConfirm by remember { mutableStateOf(false) }
    var showRecreateConfirm by remember { mutableStateOf(false) }
    var showDismissGhostConfirm by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showSetWork by remember { mutableStateOf(false) }
    var showNameWork by remember { mutableStateOf(false) }
    val manage = state.canManage
    var showTags by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    var showSafeKillConfirm by remember { mutableStateOf(false) }
    var showKillConfirm by remember { mutableStateOf(false) }
    val actionable = !state.busy && state.connected

    IconButton(onClick = { expanded = true }) {
        Icon(FleetIcons.MoreVert, contentDescription = "Session actions")
    }
    // Grouped, a divider between groups: what it is, its work, steering the
    // agent, naming it, keeping it running, and — last, apart, in red — ending it.
    val hasWork = onSetWork != null || onNameWork != null
    val hasSteer = state.canSendQuick || state.canReview
    val hasUpkeep = (onMove != null && manage) || state.canRepair || (manage && state.canRestart) ||
        state.canRecreate || state.canDismissGhost
    val clipboard = LocalClipboardManager.current
    if (orbit != null) {
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val session = state.session
            val items = orbitMenuItems(
                OrbitMenuFacts(
                    manage = manage,
                    hostAlias = session?.hostAlias,
                    tmuxName = session?.tmuxName,
                    ticketKey = orbit.ticketKey,
                    ticket = orbit.onTicket != null || onSetWork != null,
                    tasks = orbit.onTasks != null,
                    move = onMove != null && manage,
                    repair = state.canRepair,
                    recreate = state.canRecreate,
                    ghost = state.ghost,
                    dismissGhost = state.canDismissGhost,
                    restart = manage && state.canRestart,
                    steer = state.canSendQuick,
                    review = state.canReview,
                    archive = orbit.onArchive != null,
                    kill = manage && state.canKill,
                    worktree = onRepo != null,
                ),
            )
            for ((i, item) in items.withIndex()) {
                if (i > 0 && items[i - 1].group != item.group) HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.label, color = if (item.danger) Fleet.colors.statusFailed else Fleet.colors.fg)
                            item.detail?.let { Text(it, style = Fleet.type.textXs, color = Fleet.colors.fgMuted) }
                        }
                    },
                    enabled = actionable || !item.writes,
                    modifier = Modifier.testTag(ORBIT_MENU_TAG + item.id.name),
                    onClick = {
                        expanded = false
                        when (item.id) {
                            OrbitItem.Rename -> showRename = true
                            OrbitItem.Ticket -> (orbit.onTicket ?: orbit.onTasks)?.invoke() ?: run { showSetWork = true }
                            OrbitItem.Move -> onMove?.invoke()
                            OrbitItem.Repair -> showRepairConfirm = true
                            OrbitItem.Recreate -> showRecreateConfirm = true
                            OrbitItem.Restart -> showRestartConfirm = true
                            OrbitItem.DismissGhost -> showDismissGhostConfirm = true
                            OrbitItem.CopyAttach -> session?.let { clipboard.setText(AnnotatedString(tmuxAttachCommand(it.tmuxName))) }
                            OrbitItem.Details -> onDetails()
                            OrbitItem.Worktree -> onRepo?.invoke()
                            OrbitItem.Model -> picking = "model"
                            OrbitItem.Effort -> picking = "effort"
                            OrbitItem.Review -> showReview = true
                            OrbitItem.Tags -> showTags = true
                            OrbitItem.Archive -> orbit.onArchive?.invoke()
                            OrbitItem.Retire -> showSafeKillConfirm = true
                            OrbitItem.Kill -> showKillConfirm = true
                        }
                        Unit
                    },
                )
            }
        }
    } else DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text("Details") }, onClick = { expanded = false; onDetails() })
        if (onRepo != null) DropdownMenuItem(text = { Text("Worktree") }, onClick = { expanded = false; onRepo() })
        if (hasWork) {
            HorizontalDivider()
            if (onSetWork != null) {
                DropdownMenuItem(
                    text = { Text("Set work…") },
                    enabled = state.connected,
                    onClick = { expanded = false; showSetWork = true },
                )
            }
            if (onNameWork != null) {
                DropdownMenuItem(
                    text = { Text("Name this work…") },
                    enabled = state.connected,
                    onClick = { expanded = false; showNameWork = true },
                )
            }
        }
        if (hasSteer) {
            HorizontalDivider()
            // The desktop's model and effort pickers: each sends `/model <alias>`
            // or `/effort <level>` like a typed command.
            if (state.canSendQuick) {
                DropdownMenuItem(text = { Text("Model…") }, onClick = { expanded = false; picking = "model" })
                DropdownMenuItem(text = { Text("Effort…") }, onClick = { expanded = false; picking = "effort" })
            }
            if (state.canReview) {
                DropdownMenuItem(text = { Text("Review…") }, enabled = actionable, onClick = { expanded = false; showReview = true })
            }
        }
        if (manage) {
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Rename…") }, enabled = actionable, onClick = { expanded = false; showRename = true })
            DropdownMenuItem(text = { Text("Tags…") }, enabled = actionable, onClick = { expanded = false; showTags = true })
        }
        if (hasUpkeep) {
            HorizontalDivider()
            // A ghost first: bringing it back, or letting it go, is what it is for.
            if (state.canRecreate) {
                DropdownMenuItem(
                    text = { Text(if (state.ghost) "Recreate" else "Recreate (resume in a new pane)") },
                    enabled = actionable,
                    onClick = { expanded = false; showRecreateConfirm = true },
                )
            }
            if (state.canDismissGhost) {
                DropdownMenuItem(
                    text = { Text("Dismiss ghost") },
                    enabled = actionable,
                    onClick = { expanded = false; showDismissGhostConfirm = true },
                )
            }
            if (onMove != null && manage) {
                DropdownMenuItem(text = { Text("Move to host…") }, enabled = actionable, onClick = { expanded = false; onMove() })
            }
            if (state.canRepair) {
                DropdownMenuItem(text = { Text("Repair workspace") }, enabled = actionable, onClick = { expanded = false; showRepairConfirm = true })
            }
            if (manage && state.canRestart) {
                DropdownMenuItem(text = { Text("Restart") }, enabled = actionable, onClick = { expanded = false; showRestartConfirm = true })
            }
        }
        if (manage) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Retire safely") },
                enabled = actionable,
                onClick = { expanded = false; showSafeKillConfirm = true },
            )
            if (state.canKill) {
                DropdownMenuItem(
                    text = { Text("Kill now", color = MaterialTheme.colorScheme.error) },
                    enabled = actionable,
                    onClick = { expanded = false; showKillConfirm = true },
                )
            }
        }
    }

    picking?.let { command ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (command == "model") "Switch the model" else "Set the effort") },
            text = {
                Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    for (option in if (command == "model") MODEL_OPTIONS else EFFORT_OPTIONS) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable {
                                picking = null
                                pickerCommand(command, option.value)?.let(onSendCommand)
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        )
    }
    if (showReview) {
        var prompt by remember { mutableStateOf(DEFAULT_REVIEW_PROMPT) }
        AlertDialog(
            onDismissRequest = { showReview = false },
            title = { Text("Review this worktree") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A new session in this session's worktree, seeded with the prompt below. It reviews the worktree as it is now.")
                    TextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        minLines = 4,
                        maxLines = 10,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showReview = false; onReview(prompt) }, enabled = prompt.isNotBlank()) { Text("Start review") } },
            dismissButton = { TextButton(onClick = { showReview = false }) { Text("Cancel") } },
        )
    }
    if (showRepairConfirm) {
        AlertDialog(
            onDismissRequest = { showRepairConfirm = false },
            title = { Text("Repair the workspace?") },
            text = { Text("Makes the session's directory a healthy git worktree on its branch, with its pane running there. Nothing happens to a healthy one.") },
            confirmButton = { TextButton(onClick = { showRepairConfirm = false; onRepair() }) { Text("Repair") } },
            dismissButton = { TextButton(onClick = { showRepairConfirm = false }) { Text("Cancel") } },
        )
    }
    if (showRecreateConfirm) {
        AlertDialog(
            onDismissRequest = { showRecreateConfirm = false },
            title = { Text("Recreate this session?") },
            text = { Text("Its tmux session is killed and rebuilt in the same worktree, resuming the same conversation. The process does not survive; the conversation does.") },
            confirmButton = { TextButton(onClick = { showRecreateConfirm = false; onRecreate() }) { Text("Recreate") } },
            dismissButton = { TextButton(onClick = { showRecreateConfirm = false }) { Text("Cancel") } },
        )
    }
    if (showDismissGhostConfirm) {
        AlertDialog(
            onDismissRequest = { showDismissGhostConfirm = false },
            title = { Text("Dismiss this ghost?") },
            text = { Text("Its row is deleted for good. Its conversation stays on the host, and can still be found from the host's sheet.") },
            confirmButton = { TextButton(onClick = { showDismissGhostConfirm = false; onDismissGhost() }) { Text("Dismiss") } },
            dismissButton = { TextButton(onClick = { showDismissGhostConfirm = false }) { Text("Cancel") } },
        )
    }
    if (showSetWork && onSetWork != null) {
        SetWorkDialog(
            onConfirm = { showSetWork = false; onSetWork(it) },
            onDismiss = { showSetWork = false },
        )
    }
    if (showNameWork && onNameWork != null) {
        WorkTitleDialog(
            heading = "Name this work",
            initialTitle = "",
            askKey = true,
            confirmLabel = "Name",
            onConfirm = { title, key -> showNameWork = false; onNameWork(title, key) },
            onDismiss = { showNameWork = false },
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
        showSafeKillConfirm = showSafeKillConfirm,
        hideSafeKillConfirm = { showSafeKillConfirm = false },
        showKillConfirm = showKillConfirm,
        hideKillConfirm = { showKillConfirm = false },
        onRename = onRename,
        onSetTags = onSetTags,
        onRestart = onRestart,
        onSafeKill = onSafeKill,
        onKill = onKill,
    )
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
    showSafeKillConfirm: Boolean,
    hideSafeKillConfirm: () -> Unit,
    showKillConfirm: Boolean,
    hideKillConfirm: () -> Unit,
    onRename: (String) -> Unit,
    onSetTags: (List<String>) -> Unit,
    onRestart: () -> Unit,
    onSafeKill: () -> Unit,
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
    // Retiring ends the session once its work is saved: asked first, like
    // Restart. No delayed enable — that is Kill now's, which cannot wait.
    if (showSafeKillConfirm) {
        AlertDialog(
            onDismissRequest = hideSafeKillConfirm,
            title = { Text("Retire this session?") },
            text = {
                Text(
                    "Claude is asked to commit and push its work. Once the tree is clean, " +
                        "the session and its worktree are removed.",
                )
            },
            confirmButton = {
                TextButton(onClick = { hideSafeKillConfirm(); onSafeKill() }) { Text("Retire") }
            },
            dismissButton = { TextButton(onClick = hideSafeKillConfirm) { Text("Cancel") } },
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
            DangerTextButton(onClick = onConfirm, enabled = enabled) {
                Text("Kill now")
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

/**
 * One turn: the prompt bubble, then everything the agent said and did.
 *
 * Turns are set apart by space above each prompt rather than a full-bleed rule:
 * a rule between every turn made a long conversation read as a ledger, and
 * the bubble already marks where a new exchange starts. [live] is the last
 * turn of a session that is working — its newest folded tool run opens by
 * itself, since that is where the work is happening.
 */
@Composable
internal fun Turn(turn: ConvTurn, live: Boolean = false, footer: (@Composable () -> Unit)? = null) {
    val prompt = turn.prompt
    val hasPrompt = !prompt.isNullOrBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = if (hasPrompt) 16.dp else 4.dp, bottom = 4.dp),
    ) {
        if (hasPrompt) {
            PromptBubble(prompt.orEmpty())
            Spacer(Modifier.height(8.dp))
        }
        TurnItems(turn.items, live) { Item(it) }
        footer?.invoke()
    }
}

/** What a turn's [ReplyMenu] needs from the screen: the conversation it sits in, and where its actions go. */
private class ReplyHost(
    val turns: List<ConvTurn>,
    val truncated: Boolean,
    val supported: Boolean,
    val canQuote: Boolean,
    val forkName: String,
    val onQuote: (String) -> Unit,
    val onRewind: (String) -> Unit,
    val onRetry: (String, String) -> Unit,
    val onFork: (String?, String?) -> Unit,
)

/**
 * The actions under a reply, behind a small ⋯ at its end so they cost the
 * conversation one short line rather than a row of buttons per turn: Copy
 * and Quote the reply's words, and — where [replyActionsFor] allows it —
 * Retry, Rewind here and Fork here. The three that rewrite the session ask
 * first; Retry offered but unavailable says why instead of vanishing.
 */
@Composable
private fun ReplyMenu(turn: ConvTurn, chronological: Int, host: ReplyHost) {
    val view = remember(turn, chronological, host.turns, host.truncated, host.supported) {
        replyActionsFor(host.turns, chronological, host.truncated, host.supported)
    }
    val text = remember(turn) { replyText(turn) }
    if (text.isEmpty() && !view.canFork) return
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<ReplyAsk?>(null) }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(FleetIcons.MoreVert, contentDescription = "Reply actions", modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (text.isNotEmpty()) {
                DropdownMenuItem(text = { Text("Copy") }, onClick = {
                    clipboard.setText(AnnotatedString(text))
                    open = false
                })
                if (host.canQuote) {
                    DropdownMenuItem(text = { Text("Quote") }, onClick = {
                        host.onQuote(quoteText(text))
                        open = false
                    })
                }
            }
            if (view.canRewind) {
                DropdownMenuItem(
                    text = { Text("Retry") },
                    enabled = view.canRetry,
                    onClick = { asking = ReplyAsk.Retry; open = false },
                )
                view.retryUnavailable?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                DropdownMenuItem(text = { Text("Rewind here") }, onClick = { asking = ReplyAsk.Rewind; open = false })
            }
            if (view.canFork) {
                DropdownMenuItem(text = { Text("Fork here") }, onClick = { asking = ReplyAsk.Fork; open = false })
            }
        }
    }
    when (asking) {
        ReplyAsk.Rewind -> ConfirmRewind(
            title = "Rewind here?",
            body = "This prompt and everything after it leave the session's conversation, and the session restarts on what is left. The original transcript is kept.",
            confirm = "Rewind",
            onConfirm = { view.rewindAnchor?.let(host.onRewind); asking = null },
            onDismiss = { asking = null },
        )
        ReplyAsk.Retry -> ConfirmRewind(
            title = "Retry this prompt?",
            body = "The session rewinds to before this prompt, then the same prompt is sent again. The original transcript is kept.",
            confirm = "Retry",
            onConfirm = {
                val anchor = view.rewindAnchor
                val prompt = turn.prompt
                if (anchor != null && prompt != null) host.onRetry(anchor, prompt)
                asking = null
            },
            onDismiss = { asking = null },
        )
        ReplyAsk.Fork -> ForkDialog(
            suggested = host.forkName,
            onConfirm = { worktree -> host.onFork(view.forkAnchor, worktree); asking = null },
            onDismiss = { asking = null },
        )
        null -> Unit
    }
}

private enum class ReplyAsk { Rewind, Retry, Fork }

@Composable
private fun ConfirmRewind(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Fork here: a new session on the conversation so far. In a new worktree by
 * default — the desktop's default too — so the fork's edits do not land in
 * the tree this session is working in; unticked, it shares that worktree.
 */
@Composable
private fun ForkDialog(suggested: String, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    var ownTree by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf(suggested) }
    val slug = branchSlug(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fork here") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A new session starts on this conversation up to here. This session is left as it is.")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(value = ownTree, role = Role.Checkbox) { ownTree = it },
                ) {
                    Checkbox(checked = ownTree, onCheckedChange = null)
                    Text("In a new worktree")
                }
                if (ownTree) {
                    // What git will be given, which is not always what was typed.
                    TextField(
                        supportingText = { if (slug != name) Text("As: ${slug.ifEmpty { "—" }}") },
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Worktree and branch") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(if (ownTree) slug else null) },
                enabled = !ownTree || slug.isNotEmpty(),
            ) { Text("Fork") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * What the person sent, folded to [PROMPT_FOLD_LINES] when it is longer: a
 * pasted log or spec drew whole, and the answer under it — what the screen
 * is opened for — was hundreds of lines further down. A tap on the bubble
 * unfolds it and folds it again. Saveable, so a prompt someone opened stays
 * open when its turn scrolls off and back.
 */
@Composable
private fun PromptBubble(prompt: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var folds by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.clickable(
            enabled = folds,
            onClickLabel = if (expanded) PROMPT_LESS else PROMPT_MORE,
        ) { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // A match inside a folded prompt opens it, or Find would land on
            // a bubble whose matching line is cut off.
            val find = LocalFindQuery.current
            val matched = find.isNotBlank() && prompt.contains(find.trim(), ignoreCase = true)
            Text(
                text = AnnotatedString(prompt).withFind(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded || matched) Int.MAX_VALUE else PROMPT_FOLD_LINES,
                overflow = TextOverflow.Ellipsis,
                // Only a folded layout can say whether folding cut anything;
                // an unfolded one keeps the answer it had.
                onTextLayout = { if (!expanded) folds = it.hasVisualOverflow },
            )
            if (folds) {
                Text(
                    text = if (expanded) PROMPT_LESS else PROMPT_MORE,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** How many lines of a prompt show before it folds. */
internal const val PROMPT_FOLD_LINES: Int = 6
private const val PROMPT_MORE = "Show more"
private const val PROMPT_LESS = "Show less"

/**
 * The most of the screen the blocked card may take; past it, the card
 * scrolls inside itself.
 */
private const val CARD_MAX_FRACTION = 0.45f

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
        // A step above the prompt bubble and the tool rows: the agent's words
        // are what the screen is read for.
        // A task report or a fleet-ui block in it is drawn as a card
        // (RichCards.kt); everything else is that same Markdown.
        is ConvItem.Text -> RichText(
            text = item.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        // Normally drawn by `TurnItems`, which folds runs of them; here for
        // exhaustiveness, and drawn the same way.
        is ConvItem.Tool -> ToolCallRow(item)
        // A subagent gets a block rather than a line: it is a whole piece of
        // work, and its result is the part somebody scrolls back for. A
        // `Workflow` call arrives as this same item and draws this same block
        // -- see `typeLabel`.
        is ConvItem.Subagent -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = buildString {
                        append(item.typeLabel())
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
                    FoldedReport(it)
                }
            }
        }
        // A background job reporting in. Before the hub parsed these they
        // arrived as raw XML in a text item; the point of drawing them is that
        // they stay on the screen now they arrive tagged.
        is ConvItem.Notification -> {
            val report = item.result?.takeIf { it.isNotBlank() && it != item.summary }
            // A job that only says it moved (a Monitor line, "started") stays
            // a quiet marker; one that hands back a report gets a block of
            // its own, like the subagent it usually is.
            if (report == null) Note(marker = "◆", text = item.label) else NotificationBlock(item, report)
        }
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
                // Six lines, then a way to the rest: `!git status` or `/cost`
                // used to stop at "…" with nothing to tap. Selectable, so a
                // path or an error line can be copied out of it.
                var open by remember(it) { mutableStateOf(false) }
                var cut by remember(it) { mutableStateOf(false) }
                SelectionContainer {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (monospace) FontFamily.Monospace else null,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (open) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout -> if (!open) cut = layout.hasVisualOverflow },
                    )
                }
                if (cut || open) {
                    TextButton(onClick = { open = !open }) { Text(if (open) "Show less" else "Show all") }
                }
            }
        }
    }
}

/**
 * A background job's report — most often a subagent saying it finished, and
 * what it did. It used to be a [Note]: one grey line with the report under it
 * as plain text, so a report's `**Enter:**` and `- ` lists showed as literal
 * characters and ran straight into the reply above it. Now it is a block of
 * its own, set apart from the turn's text, its report drawn as Markdown.
 */
@Composable
private fun NotificationBlock(item: ConvItem.Notification, report: String) {
    val colors = MaterialTheme.colorScheme
    val failed = item.status == "failed" || item.status == "killed"
    val accent = when {
        failed -> colors.error
        item.status == "completed" -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Surface(
        color = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text("◆", style = MaterialTheme.typography.labelLarge, color = accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (failed) colors.error else colors.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            notificationStatusWord(item.status)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier.padding(start = 20.dp, top = 2.dp),
                )
            }
            HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
            FoldedReport(report)
        }
    }
}

/** The status a notification's block names under its title; null when the title says it all. */
internal fun notificationStatusWord(status: String?): String? = when (status) {
    "completed" -> "Completed"
    "failed" -> "Failed"
    "stopped" -> "Stopped"
    "killed" -> "Killed"
    else -> null
}

/**
 * A report as Markdown, as the agent wrote it, and folded: a subagent's
 * report could be hundreds of lines of literal `**` and `|` burying the reply
 * it was for.
 */
@Composable
private fun FoldedReport(text: String) {
    var open by remember(text) { mutableStateOf(false) }
    val long = isLongResult(text)
    Box(modifier = if (long && !open) Modifier.heightIn(max = RESULT_FOLDED_HEIGHT).clipToBounds() else Modifier) {
        MarkdownText(text, style = MaterialTheme.typography.bodySmall)
    }
    if (long) {
        TextButton(onClick = { open = !open }) { Text(if (open) "Show less" else "Show the whole report") }
    }
}

/** A subagent's report taller than this many lines (or characters) is folded. */
internal fun isLongResult(text: String): Boolean = text.lines().size > 10 || text.length > 800

private val RESULT_FOLDED_HEIGHT = 160.dp

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
private fun EmptyConversation(state: SessionUiState, onRetry: () -> Unit = {}) {
    // A read that failed is not an empty conversation: saying "No turns yet"
    // about a busy session the phone could not reach was simply untrue.
    val failed = state.error != null && !state.errorFromSend && state.session?.kind != "shell"
    val text = when {
        state.session == null -> "This session was killed."
        state.session.kind == "shell" -> "Shell session — no conversation to show."
        failed -> "Couldn't load the conversation."
        state.silent -> "Nothing has been said yet — send a prompt to start."
        else -> "No turns yet."
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 8.dp),
            )
            if (failed && state.session != null) TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

/** One step between turns beside the "↓ Latest" pill: a round 48 dp target. */
@Composable
private fun TurnStep(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (enabled) 1f else 0.6f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = if (enabled) 1f else 0.4f),
        shadowElevation = 4.dp,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { Text(glyph, style = MaterialTheme.typography.labelLarge) }
    }
}

/**
 * The fast way back down: a filled pill that floats over the conversation,
 * with a badge counting the turns that arrived while the reader was away.
 *
 * Was an outlined `AssistChip`, whose container is transparent — floated over
 * a transcript, the monospace tool lines showed straight through its label
 * and "↓ New reply" was unreadable exactly when there was one.
 */
@Composable
private fun JumpToLatest(newReply: Boolean, unseen: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    BadgedBox(
        badge = {
            if (unseen > 0) Badge { Text(if (unseen > 99) "99+" else unseen.toString()) }
        },
        modifier = modifier,
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (newReply) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (newReply) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            shadowElevation = 4.dp,
        ) {
            Text(
                text = if (newReply) "↓ New reply" else "↓ Latest",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Above the oldest turn on screen when older ones exist: a way to ask for
 * them while there is a wider window to ask for, and the plain fact once
 * there is not.
 */
@Composable
private fun TruncationNote(canLoadOlder: Boolean, loading: Boolean, onLoadOlder: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (canLoadOlder) "Older turns are not shown." else "Older turns are not shown here.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (canLoadOlder) {
            TextButton(onClick = onLoadOlder, enabled = !loading) { Text(if (loading) "Loading…" else "Load older") }
        }
    }
}

/** Over the conversation while an earlier one is on screen: which, and the way back. */
@Composable
private fun EarlierConversationBanner(viewing: ConversationSummary, nowSeconds: Long, loading: Boolean, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Earlier conversation · ${conversationCaption(viewing, nowSeconds)}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) { Text("Back to current") }
            }
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** "clear · 12 turns · 3 h" — how it started, how long it ran, how long ago. */
internal fun conversationCaption(c: ConversationSummary, nowSeconds: Long): String =
    listOfNotNull(
        c.startSource.takeIf { it.isNotBlank() && it != "unknown" },
        "${c.turns} turn" + if (c.turns == 1L) "" else "s",
        relativeAgo(c.startedAt, nowSeconds),
    ).joinToString(" · ")

@Composable
private fun ConversationsDialog(
    conversations: List<ConversationSummary>,
    viewing: ConversationSummary?,
    nowSeconds: Long,
    onPick: (ConversationSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conversations") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for ((index, c) in conversations.withIndex()) {
                    val shown = if (viewing == null) c.current else c.claudeSessionId == viewing.claudeSessionId
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 10.dp),
                    ) {
                        Text(
                            (if (c.current) "Current · " else "") + (c.firstPrompt?.takeIf { it.isNotBlank() } ?: "(no prompt)"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (shown) FontWeight.Bold else null,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            conversationCaption(c, nowSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (index != conversations.lastIndex) HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PromptBox(
    state: SessionUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onOpenHistory: () -> List<String>,
    /** Enter in the pane, for a REPL waiting on a bare Enter — the ⏎ in an empty field. */
    onPressEnter: () -> Unit = {},
    /** Stop the working agent (Escape), offered in Send's place while there is nothing to send. */
    onStop: () -> Unit = {},
    /** A send's failure, drawn here by the box rather than under the header. */
    sendError: Friendly? = null,
    onDismissSendError: () -> Unit = {},
    /** The field gained or lost focus — what tells the screen someone is typing. */
    onFocusChange: (Boolean) -> Unit = {},
    /** Put the cursor in the field now (the folded footer's pill was tapped); [onFocused] once done. */
    focusNow: Boolean = false,
    onFocused: () -> Unit = {},
    /**
     * The question card is up and can be answered in words (New bar, 14.4):
     * the field stays open, and Send says no to the question and sends the
     * words ([onAnswerInWords]) instead of being dark.
     */
    wordsMode: Boolean = false,
    onAnswerInWords: () -> Unit = {},
    /** The agent tab's name on the New bar, where the terminal is that tab; null on the Classic bar. */
    agent: String? = null,
    /** New bar while Claude works: Send is a "Queue" button, since the message waits its turn. */
    queueLabel: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    var showHistory by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusNow) {
        if (focusNow) {
            focusRequester.requestFocus()
            onFocused()
        }
    }
    // A field that leaves composition with focus does not always report
    // losing it; the screen must not go on thinking someone is typing.
    DisposableEffect(Unit) { onDispose { onFocusChange(false) } }
    // No surface of its own: it sits in the footer `SessionScreen` draws.
    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
        val why = when {
            state.readOnly -> "This device is paired read-only."
            wordsMode -> "Send says No to the question and gives your words instead."
            state.card != null -> if (agent != null) "Answer with the buttons above, or Show in $agent." else "Answer with the buttons above, or Show terminal."
            !state.connected -> "The hub is offline; the prompt will not be delivered."
            state.session == null -> "This session is gone."
            else -> null
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = state.draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { onFocusChange(it.isFocused) },
                // Not disabled while a prompt is out: that dropped the
                // keyboard on every send. The next one waits on Send instead.
                enabled = !state.readOnly && (state.card == null || wordsMode),
                // One line: a long session name wrapped the placeholder
                // onto a second row and made an empty field look filled.
                placeholder = {
                    Text(
                        if (wordsMode) "Or type an answer…" else "Message ${state.session?.displayName ?: "session"}…",
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
                // The desktop's ⏎ chip, where it costs no room: inside an
                // empty field, gone the moment there is a draft to send.
                // Never while the card is up: on a permission dialog Enter
                // picks the highlighted option — it approves. The card's own
                // Enter is the one that checks the dialog first.
                trailingIcon = if (state.draft.isEmpty() && state.canSendQuick && state.card == null) {
                    {
                        IconButton(onClick = onPressEnter) {
                            Text("⏎", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { contentDescription = "Press Enter" })
                        }
                    }
                } else {
                    null
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
            // Send's place while the agent works and there is nothing to send:
            // Stop — the one way to halt a turn going wrong short of a kill.
            if (state.canStop) {
                FilledIconButton(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onStop() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
                ) {
                    Text("■", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { contentDescription = "Stop the agent" })
                }
            } else if (queueLabel && !wordsMode) {
                Button(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSend() },
                    enabled = state.canSend,
                    modifier = Modifier.padding(bottom = 4.dp).heightIn(min = 48.dp),
                ) {
                    if (state.sending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(sendLabel(working = true))
                }
            } else {
                FilledIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (wordsMode) onAnswerInWords() else onSend()
                    },
                    enabled = if (wordsMode) state.canSendWords else state.canSend,
                    modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
                ) {
                    if (state.sending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(FleetIcons.Send, contentDescription = "Send")
                }
            }
        }
        // The prompt on its way: out of the box already, said until it lands.
        state.pending?.let { text ->
            Text(
                "Sending: $text",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (why != null) Text(why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        sendError?.let { ErrorBanner(it, onDismiss = onDismissSendError) }
    }
    if (showHistory) {
        HistoryDialog(
            entries = onOpenHistory(),
            // Puts the picked entry in the draft; it is not sent — the
            // person still taps Send (or edits it first), same as tapping a
            // suggestion anywhere else in this screen never fires by itself.
            // Added after what is already typed, never over it: a pick used
            // to wipe a half-written reply without a word.
            onPick = { entry -> onDraftChange(withHistoryEntry(state.draft, entry)); showHistory = false },
            onDismiss = { showHistory = false },
        )
    }
}

/** [text] after what is already typed, a blank line between, as the
 *  desktop's `insertIntoComposer` puts it. */
internal fun appendToDraft(draft: String, text: String): String {
    val prev = draft.trimEnd()
    return if (prev.isEmpty()) text else "$prev\n\n$text"
}

/** What was actually sent, most recent first — picking one loads it into the draft, unsent. */
/** [entry] into the box: alone when it is empty, on a line after what is there otherwise. */
internal fun withHistoryEntry(draft: String, entry: String): String =
    if (draft.isBlank()) entry else draft.trimEnd() + "\n" + entry

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
 * The chip row above the composer: a [LazyRow] of quick replies, then two
 * trailing chips — `+`, which saves whatever is
 * in the draft, and **Edit chips**, which opens [ManageQuickRepliesDialog].
 *
 * The visible **Edit chips** entry is the point. The only way to change this
 * row used to be a long-press on a chip, which is invisible: nothing on the
 * screen said the buttons could be edited at all, so as far as anyone using
 * the app was concerned, they could not be. The long-press still works as a
 * shortcut to the same editor.
 *
 * A tap does what the chip says ([QuickReply.sendsOnTap]): an auto-send chip
 * goes to [onSendQuick]; any other puts its prompt in the composer through
 * [onFill], to be edited and sent from there. Only the sending chips follow
 * `enabled` — filling the box writes nothing to the session, so a busy
 * session is no reason to refuse it.
 *
 * Visibility of the row (hidden while blocked or readonly) is the caller's
 * decision, same as every other card-vs-composer choice on this screen — see
 * `SessionScreen`'s own body.
 */
@Composable
private fun QuickRepliesRow(
    chips: List<QuickReply>,
    draft: String,
    enabled: Boolean,
    editable: Boolean,
    onSendQuick: (String) -> Unit,
    onFill: (String) -> Unit,
    onAdd: (QuickReply) -> Unit,
    onEdit: (QuickReply, QuickReply) -> Unit,
    onRemove: (QuickReply) -> Unit,
    onMove: (QuickReply, Int) -> Unit,
) {
    var editing by remember { mutableStateOf<QuickReply?>(null) }
    var adding by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp),
        modifier = Modifier.testTag(QUICK_REPLY_ROW),
    ) {
        items(chips) { chip ->
            val sends = chip.sendsOnTap
            QuickReplyChip(
                caption = chip.caption,
                sends = sends,
                enabled = enabled || !sends,
                onClick = { if (sends) onSendQuick(chip.text) else onFill(chip.text) },
                onLongClick = if (editable) ({ editing = chip }) else null,
            )
        }
        if (editable) {
            item {
                // Not gated on `enabled`: saving the draft as a chip is a write
                // to the fleet's chip list, not a prompt to this session, so a
                // busy or disconnected session is no reason to refuse it.
                SuggestionChip(
                    onClick = { onAdd(QuickReply.of(draft)) },
                    enabled = draft.isNotBlank(),
                    label = { Text("+") },
                )
            }
            item {
                SuggestionChip(onClick = { managing = true }, label = { Text("Edit chips") })
            }
        }
    }
    editing?.let { chip ->
        EditQuickReplyDialog(
            original = chip,
            onSave = { edited -> onEdit(chip, edited); editing = null },
            onRemove = { onRemove(chip); editing = null },
            onDismiss = { editing = null },
        )
    }
    if (adding) {
        EditQuickReplyDialog(
            original = null,
            onSave = { chip -> onAdd(chip); adding = false },
            onRemove = null,
            onDismiss = { adding = false },
        )
    }
    if (managing) {
        ManageQuickRepliesDialog(
            chips = chips,
            onEdit = { editing = it; managing = false },
            onRemove = onRemove,
            onMove = onMove,
            onAdd = { adding = true; managing = false },
            onDismiss = { managing = false },
        )
    }
}

/**
 * One chip, with a tap and a long-press that both actually arrive.
 *
 * The gesture sits in a Box drawn **on top of** the chip rather than around
 * it. A `SuggestionChip` is itself clickable, and Compose hit-tests the
 * innermost node first: with the chip inside a `combinedClickable` parent,
 * the chip's own (empty) `onClick` swallowed the tap and the row's chips did
 * nothing at all. A later sibling is hit first, so the overlay gets the
 * gesture and the chip below it is left as the drawing.
 *
 * The overlay shares the chip's [MutableInteractionSource] and draws no
 * indication of its own, so the press still ripples on the chip and not on an
 * invisible rectangle over it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickReplyChip(
    caption: String,
    /** An auto-send chip: marked with a trailing ↵ so a tap is not a surprise. */
    sends: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    /** Null on a hub with no chip list to edit — see `quickRepliesEditable`. */
    onLongClick: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    Box {
        SuggestionChip(
            onClick = {},
            enabled = enabled,
            label = {
                // The mark is its own Text so the caption stays the chip's
                // exact text — for a screen reader and for a test finding it.
                // A long caption is capped with widthIn, never weight: the
                // chip sits in a LazyRow, whose width is unbounded, and a
                // weighted child of an unbounded Row is measured at zero —
                // every chip drew as an empty box.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = QUICK_REPLY_CAPTION_MAX),
                    )
                    if (sends) Text(" ↵", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            interactionSource = interaction,
        )
        Box(
            modifier = Modifier.matchParentSize().combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClickLabel = onLongClick?.let { "Edit quick reply" },
                onLongClick = onLongClick,
                onClick = { if (enabled) onClick() },
            ),
        )
    }
}

/**
 * The chip row's editor: every chip with a way to change, move or delete it,
 * and a way to write a new one. Tapping a chip's text opens its editor; the
 * arrows move it one place, which is the order the row draws on every device.
 *
 * A dialog rather than a settings screen because this is where the chips are
 * — the row is on the session screen and nowhere else, and a list of buttons
 * is easiest to edit while looking at them. The list is the fleet's (the hub
 * stores it), so an edit here shows up on the desktop and on any other phone.
 */
@Composable
private fun ManageQuickRepliesDialog(
    chips: List<QuickReply>,
    onEdit: (QuickReply) -> Unit,
    onRemove: (QuickReply) -> Unit,
    onMove: (QuickReply, Int) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick replies") },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Shared with the desktop and your other devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (chips.isEmpty()) {
                    Text("No chips yet.", style = MaterialTheme.typography.bodyMedium)
                }
                chips.forEachIndexed { i, chip ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f).clickable { onEdit(chip) }.padding(vertical = 8.dp)) {
                            Text(chip.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Only when it says something the caption does not:
                            // a chip whose label IS its prompt would otherwise
                            // draw the same line twice.
                            if (chip.label.isNotBlank() && chip.label != chip.text) {
                                Text(
                                    chip.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                if (chip.sendsOnTap) "Sends on tap" else "Fills the box",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onMove(chip, -1) }, enabled = i > 0) {
                            Text("↑")
                        }
                        IconButton(onClick = { onMove(chip, 1) }, enabled = i < chips.lastIndex) {
                            Text("↓")
                        }
                        DangerTextButton(onClick = { onRemove(chip) }) {
                            Text("Remove")
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text("New chip") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Write one chip: the label the button shows, and the prompt it sends.
 *
 * [original] null is a new chip — the dialog then offers no Remove, because
 * there is nothing yet to remove. The prompt field is multi-line: the built-in
 * Review chip is a paragraph, and a single-line field made such a chip
 * impossible to read, let alone edit, on a phone.
 */
@Composable
private fun EditQuickReplyDialog(
    original: QuickReply?,
    onSave: (QuickReply) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(original?.label.orEmpty()) }
    var text by remember { mutableStateOf(original?.text.orEmpty()) }
    // A new chip fills the box by default, as on the desktop.
    var autoSend by remember { mutableStateOf(original?.sendsOnTap ?: false) }
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove && onRemove != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove this quick reply?") },
            text = { Text("It goes from every device's chip row.") },
            confirmButton = {
                DangerTextButton(onClick = { confirmRemove = false; onRemove() }) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "New quick reply" else "Quick reply") },
        text = {
            Column {
                TextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("Label (optional)") },
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Prompt") },
                    minLines = 2,
                    maxLines = 6,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(value = autoSend, role = Role.Checkbox) { autoSend = it },
                ) {
                    Checkbox(checked = autoSend, onCheckedChange = null)
                    Text("Send on tap (otherwise only fills the box)")
                }
                // Not where Cancel sits: a tap meant to back out must never
                // delete a chip from every device. Asked first, too.
                if (onRemove != null) {
                    DangerTextButton(onClick = { confirmRemove = true }) {
                        Text("Remove this quick reply…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(QuickReply(label = label.trim(), text = text.trim(), autoSend = autoSend))
                },
                enabled = text.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What a repair found and did: healthy or not, its actions, its warnings, what it left for a person. */
@Composable
private fun RepairReportDialog(report: RepairReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (report.healthy) "The workspace is healthy" else "The workspace still needs attention") },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (report.actions.isEmpty()) Text("Nothing needed doing.", style = MaterialTheme.typography.bodySmall)
                for (a in report.actions) Text("• $a", style = MaterialTheme.typography.bodySmall)
                for (w in report.warnings) Text("⚠ $w", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                for (d in report.deferred) Text("Left for you: $d", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** The slash commands the draft could be, each with what it does; a tap fills it in. */
@Composable
private fun SlashSuggestions(commands: List<SlashCommand>, onPick: (SlashCommand) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
        for (c in commands) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("/${c.name}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.widthIn(min = 120.dp))
                Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Find in the conversation: the query, which match of how many, and the way to the older and newer ones. */
@Composable
private fun FindBar(
    query: String,
    at: Int,
    count: Int,
    onQuery: (String) -> Unit,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onClose: () -> Unit,
    /** The chosen scope on the New bar, whose chips sit under the field; null on the Classic bar. */
    scope: FindScope? = null,
    onScope: (FindScope) -> Unit = {},
) {
    // Opened to type into: the cursor is in the field, the keyboard up.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    placeholder = { Text("Find in conversation") },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )
                if (query.isNotBlank() || scope == FindScope.Errors) {
                    Text(if (count == 0) "none" else "$at of $count", style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = onOlder, enabled = count > 1) {
                    Icon(FleetIcons.ArrowBack, contentDescription = "Older match", modifier = Modifier.rotate(90f))
                }
                IconButton(onClick = onNewer, enabled = count > 1) {
                    Icon(FleetIcons.ArrowBack, contentDescription = "Newer match", modifier = Modifier.rotate(-90f))
                }
                IconButton(onClick = onClose) { Icon(FleetIcons.Close, contentDescription = "Close find") }
            }
            if (scope != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (s in FindScope.entries) {
                        FilterChip(
                            selected = s == scope,
                            onClick = { onScope(s) },
                            label = { Text(s.label) },
                            modifier = Modifier.testTag(FIND_SCOPE_TAG + s.name),
                        )
                    }
                }
            }
        }
    }
}

const val FIND_SCOPE_TAG = "find.scope."

/** The chips a failed session shows in place of the quick replies: Retry (the last turn) and Show the error (the agent tab). */
@Composable
private fun FailedQuickReplies(enabled: Boolean, onRetry: (() -> Unit)?, onShowError: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val (retry, show) = FAILED_QUICK_REPLIES
        if (onRetry != null) SuggestionChip(onClick = onRetry, enabled = enabled, label = { Text(retry) })
        SuggestionChip(onClick = onShowError, label = { Text(show) })
    }
}
