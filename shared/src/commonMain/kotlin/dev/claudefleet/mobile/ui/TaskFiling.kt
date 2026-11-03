package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.claudefleet.mobile.model.EpicFacet

/** "Payments revamp (4)": an epic as the *Under epic…* list names it. */
internal fun epicChoiceLabel(e: EpicFacet): String =
    listOfNotNull(e.key?.takeIf { it.isNotBlank() }, e.title.takeIf { it.isNotBlank() }).joinToString(" ").ifBlank { e.taskId } +
        (if (e.count > 0) " (${e.count})" else "")

/**
 * **Under epic…** (the desktop's *Under epic…*): the epics the Work tree
 * knows, and *Take out to the top* for a task already filed. One tap files
 * it; the hub refuses a filing that would nest too deep, naming why.
 */
@Composable
internal fun FileUnderEpicDialog(state: TaskUiState, handlers: TaskHandlers) {
    AlertDialog(
        onDismissRequest = handlers.onCloseFile,
        title = { Text("File under an epic") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                // A refusal (too deep, say) is said here, over the screen's own banner.
                state.error?.let { Text("${it.title}: ${it.body}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (state.epicChoices.isEmpty()) {
                    Text(
                        "No epic of fleet's own is listed. Open Work to load them, or mark a task an epic on the desktop.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                for (e in state.epicChoices) {
                    TextButton(onClick = { handlers.onFileUnder(e.taskId) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(epicChoiceLabel(e), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (state.parentTaskId != null) {
                    TextButton(onClick = { handlers.onFileUnder("") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Take out to the top", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = handlers.onCloseFile) { Text("Cancel") } },
    )
}
