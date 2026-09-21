package dev.claudefleet.mobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.tailMarker
import dev.claudefleet.mobile.ui.components.ConnectionBanner
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.MarkdownText
import dev.claudefleet.mobile.ui.components.StatusChip
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.data.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    modifier: Modifier = Modifier,
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
        )
        ConnectionBanner(status, state.hubReachable)
        ErrorBanner(state.error, onDismiss = onDismissError)

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
                AssistChip(
                    onClick = {
                        newest?.let { target -> scope.launch { listState.animateScrollToItem(target) } }
                        // Optimistic: this fires before `animateScrollToItem`
                        // has actually finished, on the assumption that the
                        // animation it just started will land there. The
                        // `atBottom` derived above will confirm it once the
                        // list settles; nothing here waits for that.
                        onAtBottom(true)
                    },
                    label = { Text(if (state.newReply) "↓ New reply" else "↓ Latest") },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
            }
        }

        PromptBox(state = state, onDraftChange = onDraftChange, onSend = onSend)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionBar(
    state: SessionUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    listState: LazyListState,
    turnCount: Int,
    truncated: Boolean,
    scope: CoroutineScope,
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
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(FleetIcons.ArrowBack, contentDescription = "Back")
            }
        },
        title = {
            Column {
                Text(
                    text = state.session?.displayName ?: "Session",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // The host is in the bar because a prompt goes to a machine,
                    // not just to a name.
                    text = state.session?.hostAlias ?: "no longer in the fleet",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            StatusChip(
                claudeStatus = state.session?.claudeStatus,
                stuckKind = state.session?.stuckKind,
            )
            Spacer(Modifier.width(4.dp))
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
        },
    )
}

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
            modifier = Modifier.padding(32.dp),
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
private fun PromptBox(state: SessionUiState, onDraftChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
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
                    placeholder = { Text("Message ${state.session?.displayName ?: "session"}…") },
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (state.canSend) onSend() }),
                    maxLines = 6,
                )
                FilledIconButton(onClick = onSend, enabled = state.canSend, modifier = Modifier.size(48.dp)) {
                    if (state.sending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(FleetIcons.Send, contentDescription = "Send")
                }
            }
            if (why != null) Text(why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
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
