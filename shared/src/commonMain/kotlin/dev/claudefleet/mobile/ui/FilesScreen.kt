package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.ui.components.ConnectionBanner
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.theme.FleetIcons

/** What the Files tab can ask its view model to do. */
data class FilesHandlers(
    val onRefresh: () -> Unit,
    val onTap: (Long) -> Unit,
    val onCancelTransfer: () -> Unit,
    val onHandOff: (Handoff) -> Unit,
    val onCloseOpened: () -> Unit,
    /** Null for a token that may not remove (readonly, or a hub without the tool). */
    val onRemove: ((Long) -> Unit)?,
    val onConfirmRemove: () -> Unit,
    val onCancelRemove: () -> Unit,
    val onDismissError: () -> Unit,
    val onDismissNotice: () -> Unit,
)

/**
 * The hub's copies of files sessions sent (claude-fleet file downloads):
 * newest first, each with its size, where it came from and how old it is. A
 * copy still coming off its host shows a spinner, a failed one its reason; a
 * tap on a ready one brings it onto this phone and offers Save / Share / Open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    state: FilesUiState,
    status: ConnectionStatus,
    handlers: FilesHandlers,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = "Files", subtitle = state.usage)
        ConnectionBanner(status)
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        state.notice?.let { notice ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(notice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = handlers.onDismissNotice) { Text("OK") }
            }
        }
        state.transfer?.let { TransferRow(it, handlers.onCancelTransfer) }

        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = handlers.onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.isEmpty || !state.loaded) {
                    item(key = "empty") {
                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            if (!state.loaded) {
                                CircularProgressIndicator()
                            } else {
                                Text(
                                    text = "No files yet. When Claude sends you one — or you send one from the desktop's " +
                                        "file viewer — it appears here.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(32.dp),
                                )
                            }
                        }
                    }
                }
                items(state.files, key = { it.id }) { file ->
                    FileLineItem(
                        file = file,
                        busy = state.transfer != null,
                        onTap = { handlers.onTap(file.id) },
                        onRemove = handlers.onRemove?.let { remove -> { remove(file.id) } },
                    )
                }
            }
        }
    }

    state.opened?.let { opened ->
        ModalBottomSheet(onDismissRequest = handlers.onCloseOpened) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(opened.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${opened.size} · on this phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                if (state.handoffs.isEmpty()) {
                    Text("This device has nowhere to hand the file on to.", style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.handoffs.forEachIndexed { index, action ->
                        if (index == 0) {
                            Button(onClick = { handlers.onHandOff(action) }) { Text(action.label) }
                        } else {
                            OutlinedButton(onClick = { handlers.onHandOff(action) }) { Text(action.label) }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    state.confirmRemove?.let { file ->
        AlertDialog(
            onDismissRequest = handlers.onCancelRemove,
            title = { Text("Remove ${file.name}?") },
            text = { Text("The hub forgets its copy, for every device. The file on its host is not touched.") },
            confirmButton = { TextButton(onClick = handlers.onConfirmRemove) { Text("Remove") } },
            dismissButton = { TextButton(onClick = handlers.onCancelRemove) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TransferRow(transfer: Transfer, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Downloading ${transfer.name}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        val fraction = transfer.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FileLineItem(file: FileLine, busy: Boolean, onTap: () -> Unit, onRemove: (() -> Unit)?) {
    val tappable = file.state == FileState.Ready && !busy
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = tappable, onClickLabel = "Download ${file.name}", onClick = onTap)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                listOf(file.size, file.where, file.age).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            file.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            when (file.state) {
                FileState.Failed -> Text(
                    "failed" + (file.error?.let { ": $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                FileState.Other -> Text(
                    "not ready",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Unit
            }
        }
        Spacer(Modifier.width(8.dp))
        if (file.state == FileState.Fetching) {
            // No percentage: the hub reports a copy's state, not its progress.
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(FleetIcons.Close, contentDescription = "Remove ${file.name}") }
        }
    }
    HorizontalDivider()
}
