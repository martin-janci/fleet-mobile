package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.kit.InlineLoading

/** The New layout's one ticket sheet is up while either half of it was opened. */
internal fun ticketSheetOpen(work: SessionWorkUiState, tasks: SessionTasksUiState): Boolean =
    work.sheetOpen || tasks.sheetOpen

/**
 * The *Tasks · N* chip beside the ticket chip. On the New layout the two
 * open one sheet, so the ticket chip stands for both and the tasks chip is
 * drawn only for a session with no ticket chip.
 */
internal fun tasksChipShown(newLayout: Boolean, ticketChip: Boolean, tasksAvailable: Boolean): Boolean =
    tasksAvailable && !(newLayout && ticketChip)

/**
 * One ticket with its tasks, as one sheet (redesign 14.15, MobileTidyTickets
 * "Ticket sheet"): the ticket chip and the Tasks chip of the New layout both
 * open it. The ticket on top — its status, why, acceptance criteria, Open
 * and Ask for a handover ([WorkTicketBody]) — and under it what this session
 * works on: the primary (★) and the other links with Make primary and
 * Unlink, suggestions with Confirm, and **+ Link another ticket…**. Classic
 * keeps its two sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneTicketSheet(
    work: SessionWorkUiState,
    workHandlers: SessionWorkHandlers,
    tasks: SessionTasksUiState,
    tasksHandlers: SessionTasksHandlers,
) {
    val current = work.work
    var renaming by remember { mutableStateOf(false) }
    if (renaming && current != null) {
        WorkTitleDialog(
            heading = "Rename work",
            initialTitle = current.title,
            askKey = false,
            confirmLabel = "Rename",
            onConfirm = { title, _ -> renaming = false; workHandlers.onRenameWork(title) },
            onDismiss = { renaming = false },
        )
    }
    ModalBottomSheet(onDismissRequest = { workHandlers.onClose(); tasksHandlers.onClose() }) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (work.chip == null) {
                Text("Ticket and tasks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() })
            }
            InlineLoading(waiting = tasks.loading || tasks.busy)
            val crossOrg = tasks.crossOrg
            if (crossOrg != null) {
                CrossOrgPanel(crossOrg, tasks, tasksHandlers)
            } else if (tasks.addOpen) {
                AddTask(tasks, tasksHandlers)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    if (work.chip != null) {
                        item(key = "ticket") { WorkTicketBody(work, workHandlers, onRename = { renaming = true }) }
                    }
                    if (tasks.available) {
                        item(key = "tasks-head") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (work.chip != null) HorizontalDivider()
                                if (!tasks.connected) {
                                    Text(
                                        "Offline — nothing can be changed until the hub is back; nothing is queued.",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                                    )
                                }
                                ErrorBanner(tasks.error, onDismiss = tasksHandlers.onDismissError)
                                if (tasks.error != null && tasks.conflict) ReloadRow(tasksHandlers.onReload)
                            }
                        }
                        section("This session works on", tasks.active, tasks, tasksHandlers, removeLabel = "Unlink")
                        section("Suggested", tasks.suggested, tasks, tasksHandlers, removeLabel = "Unlink")
                        if (tasks.loaded && tasks.active.isEmpty() && tasks.suggested.isEmpty()) {
                            item(key = "none") {
                                Text(
                                    "This session is not linked to any task.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                )
                            }
                        }
                        if (tasks.canAdd) {
                            item(key = "add") {
                                OutlinedButton(
                                    onClick = tasksHandlers.onOpenAdd,
                                    enabled = !tasks.busy,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                ) { Text("+ Link another ticket…") }
                            }
                        }
                        section("Past", tasks.past, tasks, tasksHandlers, removeLabel = "Unlink")
                    }
                }
            }
        }
    }
}
