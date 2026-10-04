package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.ChangedFile
import dev.claudefleet.mobile.model.Commit
import dev.claudefleet.mobile.model.CommitDetail
import dev.claudefleet.mobile.model.FileContent
import dev.claudefleet.mobile.model.FileDiff
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.theme.FleetIcons

/** What the worktree screen reports. */
data class RepoHandlers(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSelect: (RepoTab) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onOpenDiff: (String) -> Unit = {},
    val onOpenCommit: (String) -> Unit = {},
    val onOpenCommitDiff: (String, String) -> Unit = { _, _ -> },
    val onOpenFile: (String) -> Unit = {},
    val onMoreLog: () -> Unit = {},
    val onSendToDownloads: (String) -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/**
 * A session's worktree on the phone, read-only — the desktop's Files panel
 * without its writes: what the agent changed and each change's diff, the
 * commit history and each commit's files, and the files themselves, any of
 * which can be sent to Downloads. Back steps out of what is open first.
 */
@Composable
fun RepoScreen(state: RepoUiState, handlers: RepoHandlers, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = state.session?.displayName?.let { "Worktree · $it" } ?: "Worktree",
            subtitle = listOfNotNull(state.session?.hostAlias, state.session?.branch).joinToString(" · "),
            titleStyle = MaterialTheme.typography.titleMedium,
            navigation = {
                IconButton(onClick = handlers.onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = handlers.onRefresh, enabled = !state.loading) {
                    Icon(FleetIcons.Refresh, contentDescription = "Refresh")
                }
            },
        )
        if (state.tabs.size > 1 && state.top == null) {
            PrimaryTabRow(selectedTabIndex = state.tabs.indexOf(state.tab).coerceAtLeast(0)) {
                for (tab in state.tabs) {
                    Tab(selected = tab == state.tab, onClick = { handlers.onSelect(tab) }, text = { Text(tab.label) })
                }
            }
        }
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        state.notice?.let { Notice(it, handlers.onDismissNotice) }
        when (val top = state.top) {
            is RepoView.Diff -> DiffPane(top.path, top.diff, state, handlers, openFile = { handlers.onOpenFile(top.path) })
            is RepoView.CommitDiff -> DiffPane(top.path, top.diff, state, handlers, openFile = null)
            is RepoView.CommitView -> CommitPane(top.hash, top.detail, handlers)
            is RepoView.File -> FilePane(top.path, top.content, state, handlers)
            null -> when {
                state.tabs.isEmpty() -> Quiet("This hub does not serve a session's worktree.")
                state.tab == RepoTab.Changes -> ChangesList(state.changes, handlers)
                state.tab == RepoTab.History -> HistoryList(state, handlers)
                else -> FilesList(state, handlers)
            }
        }
    }
}

@Composable
private fun Notice(text: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun Quiet(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    )
}

@Composable
private fun ChangesList(changes: List<ChangedFile>?, handlers: RepoHandlers) {
    if (changes == null) return
    if (changes.isEmpty()) {
        Quiet("No uncommitted changes.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(changes, key = { "${it.status}:${it.path}:${it.staged}" }) { change ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { handlers.onOpenDiff(change.path) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusLetter(change.status)
                Column(modifier = Modifier.weight(1f)) {
                    Text(change.path, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(change.origPath?.let { "from $it" }, if (change.staged) "staged" else null)
                    if (sub.isNotEmpty()) {
                        Text(sub.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

/** git's status letter, coloured: added green, deleted red, everything else the accent. */
@Composable
private fun StatusLetter(status: String) {
    val letter = status.trim().take(1).ifEmpty { "?" }
    val color = when (letter) {
        "A", "?" -> DIFF_ADDED
        "D" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Text(letter, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.width(16.dp))
}

@Composable
private fun HistoryList(state: RepoUiState, handlers: RepoHandlers) {
    val log = state.log ?: return
    if (log.isEmpty()) {
        Quiet("No commits.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(log, key = { it.hash }) { commit -> CommitRow(commit, onClick = { handlers.onOpenCommit(commit.hash) }) }
        if (!state.logEnd) {
            item {
                TextButton(onClick = handlers.onMoreLog, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Older commits") }
            }
        }
    }
}

@Composable
private fun CommitRow(commit: Commit, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(commit.subject, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val refs = commit.refs.joinToString(" ") { it.name }
        Text(
            listOf(commit.shortHash.ifEmpty { commit.hash.take(7) }, commit.author, shortDate(commit.date), refs)
                .filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider()
}

/** `2026-10-04T18:10:13+02:00` → `2026-10-04 18:10`; anything else as it came. */
internal fun shortDate(iso: String): String {
    val m = Regex("^(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2})").find(iso) ?: return iso
    return "${m.groupValues[1]} ${m.groupValues[2]}"
}

@Composable
private fun FilesList(state: RepoUiState, handlers: RepoHandlers) {
    val tree = state.tree ?: return
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = handlers.onQuery,
            singleLine = true,
            placeholder = { Text("Find a file") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (tree.truncated) Quiet("Only part of the tree is listed — find narrows what was sent.")
        val shown = state.shownEntries
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it }) { path ->
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { handlers.onOpenFile(path) }.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun PathBar(path: String, actions: @Composable () -> Unit = {}) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(path, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun DiffPane(path: String, diff: FileDiff?, state: RepoUiState, handlers: RepoHandlers, openFile: (() -> Unit)?) {
    PathBar(path) {
        if (openFile != null) TextButton(onClick = openFile) { Text("File") }
    }
    if (diff == null) return
    when {
        diff.binary -> Quiet("A binary file — no diff to show.")
        diff.diff.isBlank() -> Quiet("No difference.")
        else -> CodeLines(diffLines(diff.diff), truncated = diff.truncated)
    }
}

@Composable
private fun CommitPane(hash: String, detail: CommitDetail?, handlers: RepoHandlers) {
    if (detail == null) {
        PathBar(hash.take(12))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(detail.subject, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(detail.hash.take(12), detail.author, shortDate(detail.date)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (detail.body.isNotBlank()) Text(detail.body.trim(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            HorizontalDivider()
        }
        items(detail.files, key = { "${it.status}:${it.path}" }) { file ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { handlers.onOpenCommitDiff(detail.hash, file.path) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusLetter(file.status)
                Text(file.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FilePane(path: String, content: FileContent?, state: RepoUiState, handlers: RepoHandlers) {
    PathBar(path) {
        if (state.canSendFile) {
            TextButton(onClick = { handlers.onSendToDownloads(path) }, enabled = !state.sending) { Text("Send to Downloads") }
        }
    }
    if (content == null) return
    when {
        content.isDir -> Quiet("A folder.")
        content.binary -> Quiet("A binary file" + (content.size?.let { " ($it bytes)" } ?: "") + " — send it to Downloads to open it on the phone.")
        else -> CodeLines(content.content.split('\n').map { CodeLine(it, LineKind.Plain) }, truncated = content.truncated)
    }
}

internal enum class LineKind { Plain, Added, Removed, Hunk, Meta }

internal data class CodeLine(val text: String, val kind: LineKind)

/** A unified diff's lines, each with what it is: an addition, a removal, a hunk header or file metadata. */
internal fun diffLines(diff: String): List<CodeLine> = diff.trimEnd('\n').split('\n').map { line ->
    val kind = when {
        line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ") -> LineKind.Meta
        line.startsWith("@@") -> LineKind.Hunk
        line.startsWith("+") -> LineKind.Added
        line.startsWith("-") -> LineKind.Removed
        else -> LineKind.Plain
    }
    CodeLine(line, kind)
}

private val DIFF_ADDED = Color(0xFF2E7D32)

@Composable
private fun CodeLines(lines: List<CodeLine>, truncated: Boolean) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        itemsIndexed(lines) { _, line ->
            val (bg, fg) = when (line.kind) {
                LineKind.Added -> DIFF_ADDED.copy(alpha = 0.14f) to MaterialTheme.colorScheme.onSurface
                LineKind.Removed -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f) to MaterialTheme.colorScheme.onSurface
                LineKind.Hunk -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.primary
                LineKind.Meta -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                LineKind.Plain -> Color.Transparent to MaterialTheme.colorScheme.onSurface
            }
            Surface(color = bg, contentColor = fg, modifier = Modifier.fillMaxWidth()) {
                Text(
                    line.text.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        if (truncated) item { Quiet("Cut short by the hub — the rest is not shown.") }
    }
}
