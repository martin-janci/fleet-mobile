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
import dev.claudefleet.mobile.model.TrackerCommentRow
import dev.claudefleet.mobile.model.commentAuthor
import dev.claudefleet.mobile.model.commentPostLine
import dev.claudefleet.mobile.model.relativeAgo

/** One line of the Comments timeline: fleet's own, or the tracker's. */
internal sealed interface CommentEntry {
    val at: Long

    data class Fleet(val comment: TaskComment) : CommentEntry {
        override val at get() = comment.createdAt
    }

    data class Tracker(val comment: TrackerCommentRow) : CommentEntry {
        override val at get() = comment.createdAt
    }
}

/** Fleet's and the tracker's comments in one timeline, oldest first; at the same second fleet's first (the desktop's `commentTimeline`). */
internal fun commentTimeline(fleet: List<TaskComment>, tracker: List<TrackerCommentRow>): List<CommentEntry> =
    (fleet.map { CommentEntry.Fleet(it) } + tracker.map { CommentEntry.Tracker(it) })
        .sortedWith(compareBy<CommentEntry> { it.at }.thenBy { if (it is CommentEntry.Fleet) 0 else 1 })

/** "Ana · on Acme Jira · 2 h ago": a tracker comment's header line. */
internal fun trackerCommentHeader(c: TrackerCommentRow, tracker: String, nowSeconds: Long): String =
    listOfNotNull(c.author.ifBlank { "Someone" }, "on $tracker", relativeAgo(c.createdAt.takeIf { it > 0 }, nowSeconds)).joinToString(" · ")

/** "You · 5 min ago", the header line of one comment. */
internal fun commentHeader(c: TaskComment, nowSeconds: Long): String =
    listOfNotNull(commentAuthor(c), relativeAgo(c.createdAt.takeIf { it > 0 }, nowSeconds)).joinToString(" · ")

/**
 * The task's comments, fleet's and its tracker's in one timeline oldest
 * first, with a box to add one (and, where the hub offers it, to post it to
 * the ticket's tracker too) and Delete on the reader's own: the desktop task
 * page's Comments tab.
 */
@Composable
internal fun TaskCommentsBlock(state: TaskUiState, handlers: TaskHandlers, nowSeconds: Long, modifier: Modifier = Modifier) {
    val timeline = remember(state.comments, state.trackerComments) { commentTimeline(state.comments, state.trackerComments) }
    if (timeline.isEmpty() && !state.canComment && !state.trackerCommentsFailed) return
    var toTracker by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TaskComment?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (timeline.isEmpty()) "Comments" else "Comments ${timeline.size}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        if (state.trackerCommentsFailed) {
            Text("Could not read the comments on ${state.commentsTracker}.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        for (e in timeline) when (e) {
            is CommentEntry.Tracker -> Column {
                Text(trackerCommentHeader(e.comment, state.commentsTracker ?: "the tracker", nowSeconds), style = MaterialTheme.typography.labelMedium, color = muted)
                Text(e.comment.body, style = MaterialTheme.typography.bodyMedium)
            }
            is CommentEntry.Fleet -> Column {
                val c = e.comment
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
