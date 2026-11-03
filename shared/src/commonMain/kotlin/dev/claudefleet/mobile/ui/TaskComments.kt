package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.TaskComment
import dev.claudefleet.mobile.model.commentAuthor
import dev.claudefleet.mobile.model.commentPostLine
import dev.claudefleet.mobile.model.relativeAgo

/** "You · 5 min ago", the header line of one comment. */
internal fun commentHeader(c: TaskComment, nowSeconds: Long): String =
    listOfNotNull(commentAuthor(c), relativeAgo(c.createdAt.takeIf { it > 0 }, nowSeconds)).joinToString(" · ")

/**
 * The task's comments in fleet, oldest first, with a box to add one (and,
 * where the hub offers it, to post it to the ticket's tracker too) and
 * Delete on the reader's own: the desktop task page's Comments tab, without
 * the tracker's own comments (those stay on the desktop).
 */
@Composable
internal fun TaskCommentsBlock(state: TaskUiState, handlers: TaskHandlers, nowSeconds: Long, modifier: Modifier = Modifier) {
    if (state.comments.isEmpty() && !state.canComment) return
    var toTracker by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TaskComment?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (state.comments.isEmpty()) "Comments" else "Comments ${state.comments.size}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        for (c in state.comments) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(commentHeader(c, nowSeconds), style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.weight(1f))
                    if (c.mine && state.canDeleteComment) {
                        TextButton(onClick = { deleting = c }, enabled = !state.busy) { Text("Delete") }
                    }
                }
                Text(c.body, style = MaterialTheme.typography.bodyMedium)
                commentPostLine(c.trackerPost, state.commentsTracker ?: "the tracker")?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (c.trackerPost?.state == "failed") MaterialTheme.colorScheme.error else muted,
                    )
                }
            }
        }
        if (state.canComment) {
            OutlinedTextField(
                value = state.commentDraft,
                onValueChange = handlers.onCommentDraft,
                placeholder = { Text("Add a comment") },
                enabled = !state.busy,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.canPostToTracker) {
                    Checkbox(checked = toTracker, onCheckedChange = { toTracker = it }, enabled = !state.busy)
                    Text("Also post to ${state.commentsTracker}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                } else {
                    Text("Kept in fleet", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = { handlers.onComment(toTracker) },
                    enabled = !state.busy && state.commentDraft.isNotBlank(),
                ) { Text("Comment") }
            }
        }
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete your comment?") },
            text = {
                Text(
                    if (c.trackerPost?.state == "pending") "It is removed from fleet and not sent to ${state.commentsTracker ?: "the tracker"}."
                    else if (c.trackerPost?.state == "done") "It is removed from fleet; the copy on ${state.commentsTracker ?: "the tracker"} stays."
                    else "It is removed from fleet.",
                )
            },
            confirmButton = { TextButton(onClick = { deleting = null; handlers.onDeleteComment(c.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
