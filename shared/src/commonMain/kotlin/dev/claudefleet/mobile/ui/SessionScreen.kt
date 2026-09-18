package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.ui.components.ConnectionBanner
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.StatusChip
import dev.claudefleet.mobile.data.ConnectionStatus

/**
 * One session: what has been said, newest at the bottom, and a box to answer.
 *
 * Stateless — it draws a [SessionUiState] and reports typing and taps. Only a
 * device can show whether this reads well; the behaviour behind it is in
 * [SessionViewModel] and is tested there.
 */
@Composable
fun SessionScreen(
    state: SessionUiState,
    status: ConnectionStatus,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SessionBar(state = state, onBack = onBack, onRefresh = onRefresh)
        ConnectionBanner(status)
        ErrorBanner(state.error, onDismiss = onDismissError)

        val turns = state.conversation.turns
        val listState = rememberLazyListState()
        val newest = newestItemIndex(turns.size, state.conversation.truncated)
        // Newest at the bottom, so new output should bring the view with it —
        // but only for someone who was already at the bottom. Review S1: this
        // used to fire unconditionally and yank the view down while a person was
        // scrolled up reading, and it keyed on `turns.size`, so the live bottom
        // turn growing — the usual case, since the agent appends items to it
        // while it works — did not scroll at all.
        //
        // Review S-2: the key has to include `newest`. `remember(listState)`
        // alone allocated the lambda once and closed over the `newest` of the
        // FIRST composition — which is null, because `SessionRoute` composes
        // this with `SessionUiState`'s initial empty `Conversation` and only
        // then runs `vm.load()`. `newest == null` is the second disjunct, so
        // `atBottom` was permanently true and the effect below fired
        // unconditionally: exactly the behaviour it was written to replace.
        // `listState` comes from `rememberLazyListState()` and never changes, so
        // the key could never have invalidated on its own. The off-by-one half
        // of S1 worked; this half was dead code that read like a fix.
        val atBottom by remember(listState, newest) {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                last == null || newest == null || last.index >= newest - 1
            }
        }
        LaunchedEffect(turns.size, turns.lastOrNull()?.endedAt, newest) {
            if (newest != null && atBottom) listState.scrollToItem(newest)
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.conversation.truncated) {
                item(key = "truncated") { TruncationNote() }
            }
            turnItems(turns)
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

@Composable
private fun SessionBar(state: SessionUiState, onBack: () -> Unit, onRefresh: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Column(modifier = Modifier.weight(1f)) {
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
            StatusChip(
                claudeStatus = state.session?.claudeStatus,
                stuckKind = state.session?.stuckKind,
            )
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onRefresh, enabled = !state.loading) {
                Text(if (state.loading) "…" else "Refresh")
            }
        }
    }
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
        is ConvItem.Text -> Text(
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
        // A kind this build does not know: say so rather than drop it.
        is ConvItem.Unsupported -> Text(
            text = item.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
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
            if (state.readOnly) {
                // Not hidden: the design says a refusal is explained, and an
                // absent box looks like a bug rather than a permission.
                Text(
                    text = "This device is paired read-only. Prompts are the operator's to send.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    enabled = !state.sending && !state.readOnly,
                    label = { Text("Prompt") },
                    maxLines = 4,
                )
                Button(onClick = onSend, enabled = state.canSend) {
                    Text(if (state.sending) "Sending…" else "Send")
                }
            }
        }
    }
}

/**
 * Which **LazyColumn item** holds the newest turn, or null when there are none.
 *
 * Not `turns.lastIndex`, and that was review S1: `scrollToItem` takes an item
 * index, and the truncation note occupies index 0 whenever `truncated` is set —
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
