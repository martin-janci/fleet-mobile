package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.TodayBucket
import dev.claudefleet.mobile.model.TodayGroup
import dev.claudefleet.mobile.model.TodayShipped
import dev.claudefleet.mobile.model.groupLabel
import dev.claudefleet.mobile.model.sessionPhrase
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.WorkStatusDot

/** Everything the Today sheet reports. */
data class TodayHandlers(
    val onClose: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpenSession: (Long) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * The Today sheet: the desktop's four sections and **Copy standup**. A
 * session line opens that session. Titles are the tracker's text, drawn as
 * plain text only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodaySheet(state: TodayUiState, handlers: TodayHandlers) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Today", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = handlers.onRefresh, enabled = !state.loading) { Text("Refresh") }
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(state.standup))
                        copied = true
                    },
                    enabled = state.loaded,
                ) { Text(if (copied) "Copied" else "Copy standup") }
            }
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            val v = state.view
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                if (state.loaded && v.isEmpty) {
                    item(key = "empty") {
                        Text(
                            "Nothing yet today.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                }
                groups("waiting", "Waiting on me", v.waiting, TodayBucket.Waiting, handlers)
                groups("progress", "In progress", v.inProgress, TodayBucket.InProgress, handlers)
                if (v.shipped.isNotEmpty()) {
                    item(key = "title-shipped") { TodaySectionTitle("Shipped") }
                    items(v.shipped, key = { "shipped-${it.how}-${it.key ?: it.title}-${it.at}" }) { ShippedRow(it) }
                }
                groups("stale", "Stale", v.stale, TodayBucket.Stale, handlers)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.groups(
    id: String,
    title: String,
    groups: List<TodayGroup>,
    bucket: TodayBucket,
    handlers: TodayHandlers,
) {
    if (groups.isEmpty()) return
    item(key = "title-$id") { TodaySectionTitle(title) }
    groups.forEachIndexed { i, g ->
        item(key = "$id-${g.key ?: "none"}-$i") { TodayGroupBlock(g, bucket, handlers) }
    }
}

@Composable
private fun TodaySectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** One piece of work and its sessions — each a tap to open. */
@Composable
private fun TodayGroupBlock(g: TodayGroup, bucket: TodayBucket, handlers: TodayHandlers) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            g.statusCategory?.let { WorkStatusDot(it) }
            Text(
                groupLabel(g.key, g.title),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            g.statusName?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (s in g.sessions) {
                Text(
                    "${sessionPhrase(s, bucket)} · ${s.hostAlias}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { handlers.onOpenSession(s.id) }
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ShippedRow(x: TodayShipped) {
    val label = if (!x.key.isNullOrEmpty()) groupLabel(x.key, x.title) else x.title.ifEmpty { "Untitled work" }
    Text(
        "$label — ${if (x.how == "done") "done" else "PR"}",
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
    )
}
