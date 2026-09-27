package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The multi-start confirm sheet (work graph M13.4d): the count, the
 * organisation and every project, before anything is sent. A small screen
 * makes a stray tick easy; this is where it is caught.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiStartConfirmSheet(confirm: MultiStartConfirm, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(multiStartTitle(confirm), style = MaterialTheme.typography.titleMedium)
            Text(
                orgLine(confirm.orgLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (project in confirm.projects) {
                Text("• $project", style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "One session in each, on the same branch. A project in another organisation is refused, not started.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm) { Text("Start ${confirm.count}") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/** What a multi-start did, one line per project, with **Open** where a session is there to open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiStartResultSheet(result: MultiStartResult, onOpen: (Long) -> Unit, onDone: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDone) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(resultTitle(result), style = MaterialTheme.typography.titleMedium)
            for (line in result.projects) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(line.project, style = MaterialTheme.typography.titleSmall)
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (line.outcome.refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    line.sessionId?.let { id -> TextButton(onClick = { onOpen(id) }) { Text("Open") } }
                }
            }
            Button(onClick = onDone) { Text("Done") }
        }
    }
}

/** "Start PAY-9 in 3 projects on pine". */
fun multiStartTitle(confirm: MultiStartConfirm): String =
    "Start ${confirm.key} in ${confirm.count} projects on ${confirm.host}"

/** The org line of the confirm sheet — said even when the phone does not know it. */
fun orgLine(orgLabel: String?): String =
    orgLabel?.let { "Organisation: $it" } ?: "Organisation: not known on this phone"

/** "PAY-9: started 2 of 3". */
fun resultTitle(result: MultiStartResult): String =
    "${result.key}: started ${result.startedCount} of ${result.projects.size}"

private val StartOutcome.refused: Boolean
    get() = this == StartOutcome.CROSS_ORG || this == StartOutcome.REFUSED
