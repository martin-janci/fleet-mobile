package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.SessionTaskLink
import dev.claudefleet.mobile.ui.components.ErrorBanner

data class SessionTasksHandlers(
    val onClose: () -> Unit = {},
    val onOpenTask: (String) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * A session's *Tasks*: every link, grouped — primary, also on, suggested,
 * past, not this — each with why. Read-only; a task opens its task screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionTasksSheet(state: SessionTasksUiState, handlers: SessionTasksHandlers) {
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Tasks", style = MaterialTheme.typography.titleMedium)
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            when {
                state.count == null && state.loading -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
                state.isEmpty -> Text("No task is linked to this session.", style = MaterialTheme.typography.bodySmall)
            }
            for (group in state.groups) {
                Text(group.kind.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                for (link in group.links) SessionTaskRow(link, onClick = { handlers.onOpenTask(link.task.taskId) })
            }
        }
    }
}

@Composable
private fun SessionTaskRow(link: SessionTaskLink, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClickLabel = "Open ${link.task.label}", onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(
            link.task.label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (link.task.unavailable) TextDecoration.LineThrough else null,
        )
        val meta = listOfNotNull(link.task.statusName, link.link.why.takeIf { it.isNotBlank() }).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (link.link.crossOrg) {
            Text("Linked across organisations", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
