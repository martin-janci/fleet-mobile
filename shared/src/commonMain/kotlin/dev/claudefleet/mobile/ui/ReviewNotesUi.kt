package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.NoteAnchor
import dev.claudefleet.mobile.model.NoteSide
import dev.claudefleet.mobile.model.REVIEW_NOTE_BODY_MAX
import dev.claudefleet.mobile.model.ReviewNote
import dev.claudefleet.mobile.model.sendNotesLabel
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.SheetAction

/**
 * Review notes on the open diff (claude-fleet migration 223): the state and
 * what the diff does with a tapped line. Null on a hub without `review_notes`,
 * and the diff then has no note gutter.
 */
data class ReviewNotesUi(
    val state: ReviewNotesUiState,
    /** The session's agent, for "Send 2 notes to Claude". */
    val agent: String,
    /** This device may send and resolve: a readonly or answer-only one only drafts and reads. */
    val canSend: Boolean,
    val onOpenLine: (NoteAnchor, String) -> Unit,
    val onSave: (String) -> Unit,
    val onClose: () -> Unit,
    val onDelete: (NoteAnchor) -> Unit,
    val onSend: () -> Unit,
    val onResolve: (ReviewNote) -> Unit,
    val onDismissError: () -> Unit,
    val onDismissNotice: () -> Unit,
)

internal const val NOTES_SEND_TAG = "review-notes-send"
internal const val NOTE_SHEET_FIELD_TAG = "review-note-field"

/**
 * What the gutter shows beside a line: ✎ for a draft here, a dot with the
 * number of open sent notes, ✓ when every sent note on it is resolved.
 */
internal fun noteBadge(anchor: NoteAnchor?, s: ReviewNotesUiState): String? {
    if (anchor == null) return null
    if (anchor in s.drafts) return "✎"
    val sent = s.sentByAnchor[anchor].orEmpty()
    if (sent.isEmpty()) return null
    val open = sent.count { !it.resolved }
    return if (open == 0) "✓" else "●$open"
}

/** The bar under the diff: the draft count and Send, plus what the last send said. */
@Composable
internal fun NotesSendBar(notes: ReviewNotesUi) {
    val s = notes.state
    ErrorBanner(s.error, onDismiss = notes.onDismissError)
    s.notice?.let { Notice(it, notes.onDismissNotice) }
    if (s.drafts.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (notes.canSend) "Drafts stay on this phone until you send." else "This device can draft but not send.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = notes.onSend,
                enabled = notes.canSend && !s.sending,
                modifier = Modifier.testTag(NOTES_SEND_TAG),
            ) { Text(if (s.sending) "Sending…" else sendNotesLabel(s.drafts.size, notes.agent)) }
        }
    }
}

/** The open line's sheet: what was sent on it, then this phone's draft. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewNoteSheet(notes: ReviewNotesUi) {
    val s = notes.state
    val anchor = s.open ?: return
    val draft = s.drafts[anchor]
    var text by remember(anchor) { mutableStateOf(draft?.body.orEmpty()) }
    val quote = draft?.quote ?: s.openQuote
    BottomSheet(
        title = "Note on line ${anchor.line}${if (anchor.side == NoteSide.OLD) " (removed)" else ""}",
        meta = anchor.path,
        onDismiss = notes.onClose,
        primary = SheetAction(if (text.isBlank() && draft != null) "Delete draft" else "Save note", enabled = text.isNotBlank() || draft != null) {
            notes.onSave(text)
        },
        scrollable = true,
    ) {
        if (quote.isNotBlank()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(
                    quote,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        for (n in s.sentByAnchor[anchor].orEmpty()) SentNote(n, notes, busy = n.id in s.resolving)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(REVIEW_NOTE_BODY_MAX) },
            label = { Text(if (draft == null) "Your note" else "Your draft") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).testTag(NOTE_SHEET_FIELD_TAG),
        )
        if (draft != null) {
            TextButton(onClick = { notes.onDelete(anchor) }) { Text("Delete draft") }
        }
    }
}

@Composable
private fun SentNote(n: ReviewNote, notes: ReviewNotesUi, busy: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            listOfNotNull(n.author.ifBlank { null }, if (n.resolved) "resolved" else null).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(n.body, style = MaterialTheme.typography.bodyMedium)
        if (notes.canSend) {
            TextButton(onClick = { notes.onResolve(n) }, enabled = !busy) { Text(if (n.resolved) "Reopen" else "Resolve") }
        }
    }
}
