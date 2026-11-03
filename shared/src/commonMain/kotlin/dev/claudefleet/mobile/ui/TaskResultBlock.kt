package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.TaskResult
import dev.claudefleet.mobile.model.relativeAgo
import dev.claudefleet.mobile.model.resultGateLine
import dev.claudefleet.mobile.model.resultPrLabel
import dev.claudefleet.mobile.ui.components.ReportCard

/** "Implement · 2 h ago": what finished, and when. */
internal fun resultHeader(r: TaskResult, nowSeconds: Long): String =
    listOfNotNull(
        r.role.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() },
        relativeAgo(r.at.takeIf { it > 0 }, nowSeconds),
    ).joinToString(" · ")

/**
 * The item's **Result**, the desktop task page's: the newest finished task's
 * report, what fleet's completion gate found, and the PRs it opened. Read
 * only, but for **Make a task** on a follow-up line.
 */
@Composable
internal fun TaskResultBlock(state: TaskUiState, handlers: TaskHandlers, nowSeconds: Long, modifier: Modifier = Modifier) {
    val r = state.result ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val uri = LocalUriHandler.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        resultHeader(r, nowSeconds).takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = muted)
        }
        // The follow-ups are listed below, each with Make a task.
        ReportCard(r.report.copy(followups = emptyList()), r.raw, "Result", r.marker)
        Text(
            resultGateLine(r),
            style = MaterialTheme.typography.bodySmall,
            color = if (r.gate == "blocked") MaterialTheme.colorScheme.error else muted,
        )
        for (line in r.failedChecks + r.openChildren.map { "Open: $it" }) {
            Text("• $line", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        if (r.followups.isNotEmpty()) {
            Text("Follow-ups", style = MaterialTheme.typography.labelMedium, color = muted)
            for (f in r.followups) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("• ${f.text}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    when {
                        f.itemId != null -> Text("Task made", style = MaterialTheme.typography.labelSmall, color = muted)
                        state.canFollowup -> TextButton(onClick = { handlers.onMakeFollowup(f.index) }, enabled = !state.busy) { Text("Make a task") }
                    }
                }
            }
        }
        for (pr in r.prs) {
            Text(
                resultPrLabel(pr),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.heightIn(min = 32.dp).clickable { runCatching { uri.openUri(pr.url) } }.padding(vertical = 6.dp),
            )
        }
    }
}
