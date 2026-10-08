package dev.claudefleet.mobile.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import dev.claudefleet.mobile.ui.components.MarkdownText
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.theme.diffColors
import dev.claudefleet.mobile.ui.theme.FleetIcons
import kotlinx.coroutines.launch
import kotlin.math.round

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
    /**
     * Put words in the session's composer — never send them: "Ask Claude Code
     * to commit", or a long-pressed diff line. Null on the Classic worktree
     * screen, which has no composer beside it.
     */
    val onAsk: ((String) -> Unit)? = null,
    /** Step out of the open diff, commit or file (the Files tab has no header of its own). */
    val onClose: (() -> Unit)? = null,
)

/**
 * A session's worktree on the phone, read-only — the desktop's Files panel
 * without its writes: what the agent changed and each change's diff, the
 * commit history and each commit's files, and the files themselves, any of
 * which can be sent to Downloads. Back steps out of what is open first.
 *
 * The Classic bar's own screen; on the New bar the same body is the session's
 * Files tab ([RepoBody]).
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
        RepoBody(state, handlers)
    }
}

/**
 * The worktree without a header: Changes, History and Files, and the diff,
 * commit or file opened from them. The worktree screen's body, and a session's
 * Files tab on the New bar (redesign 14.4, MobileSessionFiles), where it keeps
 * the session's header above it instead of a header of its own.
 */
@Composable
fun RepoBody(state: RepoUiState, handlers: RepoHandlers, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        if (state.tabs.size > 1 && state.top == null) {
            PrimaryTabRow(selectedTabIndex = state.tabs.indexOf(state.tab).coerceAtLeast(0)) {
                for (tab in state.tabs) {
                    val count = if (tab == RepoTab.Changes) state.changes?.size?.takeIf { it > 0 } else null
                    Tab(
                        selected = tab == state.tab,
                        onClick = { handlers.onSelect(tab) },
                        text = { Text(count?.let { "${tab.label} $it" } ?: tab.label) },
                    )
                }
            }
        }
        // A tab inside the session has no header of its own: its way out of
        // an open diff, commit or file, and its refresh, ride here.
        if (handlers.onClose != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.top != null) TextButton(onClick = handlers.onClose) { Text("‹ Back") }
                Text(
                    listOfNotNull(state.session?.branch, state.changes?.takeIf { it.isNotEmpty() }?.let { "${it.count { c -> !c.staged }} not committed" })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                IconButton(onClick = handlers.onRefresh, enabled = !state.loading) {
                    Icon(FleetIcons.Refresh, contentDescription = "Refresh")
                }
            }
        }
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        state.notice?.let { Notice(it, handlers.onDismissNotice) }
        when (val top = state.top) {
            is RepoView.Diff -> DiffPane(top.path, top.diff, handlers, openFile = { handlers.onOpenFile(top.path) })
            is RepoView.CommitDiff -> DiffPane(top.path, top.diff, handlers, openFile = null)
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

/** What the Files tab's commit button puts in the composer. */
internal const val ASK_TO_COMMIT: String = "Commit the changes in this worktree with a clear message."

@Composable
private fun ChangesList(changes: List<ChangedFile>?, handlers: RepoHandlers) {
    if (changes == null) return
    if (changes.isEmpty()) {
        Quiet("No uncommitted changes.")
        return
    }
    // Not committed, then staged, each its own group: what is staged is a
    // different promise from what is merely changed.
    val groups = listOf("Not committed" to changes.filter { !it.staged }, "Staged" to changes.filter { it.staged })
        .filter { it.second.isNotEmpty() }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        for ((title, files) in groups) {
            item(key = "group:$title") { GroupTitle("$title ${files.size}") }
            items(files, key = { "${it.status}:${it.path}:${it.staged}" }) { change ->
                FileRow(
                    status = change.status,
                    path = change.path,
                    sub = change.origPath?.let { "from $it" },
                    onClick = { handlers.onOpenDiff(change.path) },
                )
            }
        }
        handlers.onAsk?.let { ask ->
            item(key = "ask") {
                OutlinedButton(
                    onClick = { ask(ASK_TO_COMMIT) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp),
                ) { Text("Ask $DEFAULT_AGENT_NAME to commit") }
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * One file: its name first, in full, and its folder under it trimmed from the
 * left — the end of a path is the part that tells two files apart, and the
 * old rows lost it ("HostsViewModelTes…"). At least a thumb tall.
 */
@Composable
private fun FileRow(status: String?, path: String, sub: String? = null, onClick: () -> Unit) {
    val (name, folder) = nameAndFolder(path)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (status != null) StatusLetter(status)
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val second = listOfNotNull(folder.takeIf { it.isNotEmpty() }, sub).joinToString(" · ")
            if (second.isNotEmpty()) {
                Text(
                    second,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
        }
    }
}

/** A path as its name and the folder it is in: `a/b/c.kt` → (`c.kt`, `a/b`); a folder keeps its slash. */
internal fun nameAndFolder(path: String): Pair<String, String> {
    val trimmed = path.trimEnd('/')
    val cut = trimmed.lastIndexOf('/')
    val slash = if (path.endsWith('/')) "/" else ""
    return if (cut < 0) (trimmed + slash) to "" else (trimmed.substring(cut + 1) + slash) to trimmed.substring(0, cut)
}

/** git's status letter, coloured: added green, deleted red, everything else the accent. */
@Composable
private fun StatusLetter(status: String) {
    val letter = status.trim().take(1).ifEmpty { "?" }
    val diff = diffColors()
    val color = when (letter) {
        "A", "?" -> diff.addedFg
        "D" -> diff.removedFg
        else -> MaterialTheme.colorScheme.primary
    }
    // The letter is git's; the colour is a hint. A screen reader gets the word.
    Text(
        letter,
        style = MaterialTheme.typography.labelLarge,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier.widthIn(min = 16.dp).semantics { contentDescription = gitStatusWord(letter) },
    )
}

/** git's status letter as a word. */
internal fun gitStatusWord(letter: String): String = when (letter) {
    "A" -> "added"
    "D" -> "deleted"
    "M" -> "modified"
    "R" -> "renamed"
    "C" -> "copied"
    "U" -> "conflicted"
    "?" -> "untracked"
    else -> "changed"
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
    Column(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
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

/** One row of a folder in the Files tab: a folder to step into, or a file to open. */
internal data class TreeEntry(val name: String, val path: String, val folder: Boolean)

/**
 * The folders and files directly in [dir] (`""` is the top, otherwise ending in
 * `/`), folders first, each group by name. Built from the hub's flat list, which
 * holds folders as `shared/` and files by their whole path; a file whose folder
 * was never listed on its own still makes that folder appear.
 */
internal fun folderListing(entries: List<String>, dir: String): List<TreeEntry> {
    val seen = LinkedHashMap<String, TreeEntry>()
    for (entry in entries) {
        if (!entry.startsWith(dir) || entry == dir) continue
        val rest = entry.substring(dir.length)
        val slash = rest.indexOf('/')
        val entryHere = if (slash < 0) {
            TreeEntry(rest, entry, folder = false)
        } else {
            val name = rest.substring(0, slash)
            TreeEntry(name, "$dir$name/", folder = true)
        }
        if (entryHere.path !in seen) seen[entryHere.path] = entryHere
    }
    return seen.values.sortedWith(compareBy<TreeEntry>({ !it.folder }, { it.name.lowercase() }))
}

@Composable
private fun FilesList(state: RepoUiState, handlers: RepoHandlers) {
    val tree = state.tree ?: return
    // Which folder is open: the screen's own place, not the view model's —
    // a read of the tree never moves it.
    var dir by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = handlers.onQuery,
            singleLine = true,
            placeholder = { Text("Find a file") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (tree.truncated) Quiet("Only part of the tree is listed — find narrows what was sent.")
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (state.query.isNotBlank()) {
                // Find looks everywhere, so its hits are whole paths.
                items(state.shownEntries, key = { it }) { path ->
                    FileRow(status = null, path = path, onClick = { handlers.onOpenFile(path) })
                }
            } else {
                if (dir.isNotEmpty()) {
                    item(key = "up") {
                        val parent = nameAndFolder(dir).second.let { if (it.isEmpty()) "" else "$it/" }
                        Text(
                            "‹ ${dir.trimEnd('/')}",
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { dir = parent }.padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                }
                items(folderListing(tree.entries, dir), key = { it.path }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { if (entry.folder) dir = entry.path else handlers.onOpenFile(entry.path) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(if (entry.folder) "▸" else " ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (entry.folder) "${entry.name}/" else entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (entry.folder) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PathBar(path: String, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    val (name, folder) = nameAndFolder(path)
    Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val second = listOfNotNull(folder.takeIf { it.isNotEmpty() }, subtitle).joinToString(" · ")
            if (second.isNotEmpty()) {
                Text(second, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            }
        }
        actions()
    }
}

@Composable
private fun DiffPane(path: String, diff: FileDiff?, handlers: RepoHandlers, openFile: (() -> Unit)?) {
    val lines = remember(diff) { diff?.diff?.takeIf { it.isNotBlank() && !diff.binary }?.let(::diffLines).orEmpty() }
    val hunks = remember(lines) { hunkStarts(lines) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The hunk on screen: the last one that starts at or above the top line.
    val at by remember(hunks) { derivedStateOf { hunks.indexOfLast { it <= listState.firstVisibleItemIndex }.coerceAtLeast(0) } }
    PathBar(path, subtitle = if (hunks.size > 1) "hunk ${at + 1} of ${hunks.size}" else null) {
        if (openFile != null) TextButton(onClick = openFile) { Text("Open file") }
    }
    if (diff == null) return
    when {
        diff.binary -> Quiet("A binary file — no diff to show.")
        diff.diff.isBlank() -> Quiet("No difference.")
        else -> Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.weight(1f)) {
                CodeLines(
                    lines,
                    truncated = diff.truncated,
                    listState = listState,
                    // Long-press a line to ask about it: the words go to the composer, not to the agent.
                    onLongPress = handlers.onAsk?.let { ask ->
                        { line: CodeLine -> ask(askAboutLine(path, line)) }
                    },
                )
            }
            if (hunks.size > 1) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { scope.launch { listState.animateScrollToItem(hunks[at - 1]) } }, enabled = at > 0) { Text("‹ Previous hunk") }
                    TextButton(onClick = { scope.launch { listState.animateScrollToItem(hunks[at + 1]) } }, enabled = at < hunks.size - 1) { Text("Next hunk ›") }
                }
            }
        }
    }
}

/** What a long-pressed diff line puts in the composer. */
internal fun askAboutLine(path: String, line: CodeLine): String {
    val number = line.new ?: line.old
    return "About $path" + (number?.let { " line $it" } ?: "") + ":\n```\n${line.text}\n```\n"
}

@Composable
private fun CommitPane(hash: String, detail: CommitDetail?, handlers: RepoHandlers) {
    if (detail == null) {
        PathBar(hash.take(12))
        return
    }
    val clipboard = LocalClipboardManager.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(detail.subject, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOf(detail.hash.take(12), detail.author, shortDate(detail.date)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { clipboard.setText(AnnotatedString(detail.hash)) }) { Text("Copy hash") }
                }
                if (detail.body.isNotBlank()) Text(detail.body.trim(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            HorizontalDivider()
            if (detail.files.isNotEmpty()) GroupTitle("${detail.files.size} ${if (detail.files.size == 1) "file" else "files"}")
        }
        items(detail.files, key = { "${it.status}:${it.path}" }) { file ->
            FileRow(status = file.status, path = file.path, onClick = { handlers.onOpenCommitDiff(detail.hash, file.path) })
        }
    }
}

/** Whether a file is Markdown, drawn rendered with a Source switch. */
internal fun isMarkdown(path: String): Boolean =
    path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdx")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePane(path: String, content: FileContent?, state: RepoUiState, handlers: RepoHandlers) {
    var source by remember(path) { mutableStateOf(false) }
    PathBar(path, subtitle = content?.size?.let { sizeLabel(it) }) {
        if (state.canSendFile) {
            TextButton(onClick = { handlers.onSendToDownloads(path) }, enabled = !state.sending) { Text("Send to Downloads") }
        }
    }
    if (content == null) return
    val markdown = isMarkdown(path) && !content.binary && !content.isDir
    if (markdown) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            SegmentedButton(selected = !source, onClick = { source = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Rendered") }
            SegmentedButton(selected = source, onClick = { source = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Source") }
        }
    }
    when {
        content.isDir -> Quiet("A folder.")
        content.binary -> Quiet("A binary file" + (content.size?.let { " ($it bytes)" } ?: "") + " — send it to Downloads to open it on the phone.")
        markdown && !source -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { MarkdownText(content.content, modifier = Modifier.padding(16.dp)) }
            if (content.truncated) item { Quiet("Cut short by the hub — the rest is not shown.") }
        }
        else -> CodeLines(
            content.content.split('\n').mapIndexed { i, text -> CodeLine(text, LineKind.Plain, new = i + 1) },
            truncated = content.truncated,
        )
    }
}

/** A byte count in the words a person reads: `812 B`, `2.1 KB`, `3.4 MB`. */
internal fun sizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${round(bytes * 10.0 / 1024) / 10} KB"
    else -> "${round(bytes * 10.0 / (1024 * 1024)) / 10} MB"
}

internal enum class LineKind { Plain, Added, Removed, Hunk, Meta }

/** One line of a diff or file, with its line number on each side where it has one. */
internal data class CodeLine(val text: String, val kind: LineKind, val old: Int? = null, val new: Int? = null)

private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

/**
 * A unified diff's lines, each with what it is — an addition, a removal, a
 * hunk header or file metadata — and its line numbers, counted from each
 * hunk's header: a removal has only the old one, an addition only the new.
 */
internal fun diffLines(diff: String): List<CodeLine> {
    var old = 0
    var new = 0
    return diff.trimEnd('\n').split('\n').map { line ->
        when {
            line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ") ->
                CodeLine(line, LineKind.Meta)
            line.startsWith("@@") -> {
                HUNK_HEADER.find(line)?.let {
                    old = it.groupValues[1].toInt()
                    new = it.groupValues[2].toInt()
                }
                CodeLine(line, LineKind.Hunk)
            }
            line.startsWith("+") -> CodeLine(line, LineKind.Added, new = new++)
            line.startsWith("-") -> CodeLine(line, LineKind.Removed, old = old++)
            line.startsWith("\\") -> CodeLine(line, LineKind.Meta)
            else -> CodeLine(line, LineKind.Plain, old = old++, new = new++)
        }
    }
}

/** Where each hunk starts in [lines]: the indices of its `@@` headers. */
internal fun hunkStarts(lines: List<CodeLine>): List<Int> = lines.indices.filter { lines[it].kind == LineKind.Hunk }

/**
 * Code as it is: a line-number gutter and no soft wrap — a long line scrolls
 * sideways, so indentation stays true.
 */
@Composable
private fun CodeLines(
    lines: List<CodeLine>,
    truncated: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    onLongPress: ((CodeLine) -> Unit)? = null,
) {
    val numbers = lines.any { it.old != null || it.new != null }
    val gutter = (lines.maxOfOrNull { maxOf(it.old ?: 0, it.new ?: 0) } ?: 0).toString().length.coerceAtLeast(2)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = maxWidth
        Column(modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().widthIn(min = width)) {
                itemsIndexed(lines) { _, line ->
                    val diff = diffColors()
                    val (bg, fg) = when (line.kind) {
                        LineKind.Added -> diff.addedBg to MaterialTheme.colorScheme.onSurface
                        LineKind.Removed -> diff.removedBg to MaterialTheme.colorScheme.onSurface
                        LineKind.Hunk -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.primary
                        LineKind.Meta -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                        LineKind.Plain -> Color.Transparent to MaterialTheme.colorScheme.onSurface
                    }
                    Surface(
                        color = bg,
                        contentColor = fg,
                        modifier = Modifier.widthIn(min = width).then(
                            if (onLongPress != null && line.kind != LineKind.Meta && line.kind != LineKind.Hunk) {
                                Modifier.combinedClickable(onClick = {}, onLongClick = { onLongPress(line) }, onLongClickLabel = "Ask about this line")
                            } else {
                                Modifier
                            },
                        ),
                    ) {
                        Row {
                            if (numbers) {
                                Text(
                                    (line.new ?: line.old)?.toString().orEmpty().padStart(gutter),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    softWrap = false,
                                    modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
                                )
                            }
                            Text(
                                line.text.ifEmpty { " " },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                if (truncated) item { Quiet("Cut short by the hub — the rest is not shown.") }
            }
        }
    }
}
