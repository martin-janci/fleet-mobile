package dev.claudefleet.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.EditDetail
import dev.claudefleet.mobile.model.ToolDetail
import dev.claudefleet.mobile.ui.theme.DiffColors
import dev.claudefleet.mobile.ui.theme.diffColors
import dev.claudefleet.mobile.ui.theme.FleetIcons

/*
 * A conversation's tool calls: one readable row per call (icon, verb, target),
 * runs of three or more folded into "N tool calls", and each call expandable
 * to its detail — a diff for an edit, the output of a command, the lines a
 * Read returned, the matches of a search, a todo list — read from the hub the
 * first time the row opens. The rules behind all of it (names, grouping, the
 * diff, the parsers) are pure functions in `ToolCalls.kt`.
 */

/**
 * What the tool rows need from their screen: whether this hub can answer
 * `session_tool_detail` at all, what has been read so far, and how to ask for
 * more. A [compositionLocalOf] rather than a parameter threaded through
 * `Turn`, so a turn drawn anywhere — a screenshot, a preview — gets the
 * unexpandable [None] without knowing about any of this.
 *
 * [expandAll] opens every row and group on first draw; only a preview or a
 * screenshot wants that.
 */
@Immutable
class ToolDetailsHost(
    val available: Boolean,
    val states: Map<String, ToolDetailLoad>,
    val request: (toolUseId: String, done: Boolean) -> Unit,
    val expandAll: Boolean = false,
) {
    companion object {
        val None = ToolDetailsHost(available = false, states = emptyMap(), request = { _, _ -> })
    }
}

val LocalToolDetails = compositionLocalOf { ToolDetailsHost.None }

/** The largest the expanded body grows before it scrolls inside itself. */
private val DETAIL_MAX_HEIGHT = 400.dp
private const val DIFF_ROW_CAP = 200
private const val BASH_TAIL = 30
private const val READ_CAP = 40
private const val SEARCH_CAP = 20
private const val FALLBACK_CAP = 40
private val ICON = 18.dp

/** A turn's items, with tool runs drawn as tool rows. [item] draws everything else. */
@Composable
internal fun TurnItems(items: List<ConvItem>, live: Boolean, item: @Composable (ConvItem) -> Unit) {
    val runs = remember(items, live) { groupToolRuns(items, live || anyPending(items)) }
    var previousWasTool = false
    for ((index, run) in runs.withIndex()) {
        val isTool = run is ItemRun.Tools || (run is ItemRun.One && run.item is ConvItem.Tool)
        // Tool rows carry their own height; a gap between two of them would
        // only make a run look like separate paragraphs.
        if (index > 0 && !(isTool && previousWasTool)) Spacer(Modifier.padding(top = 4.dp))
        when (run) {
            is ItemRun.Tools -> ToolGroupRow(run)
            is ItemRun.One -> {
                val one = run.item
                if (one is ConvItem.Tool) ToolCallRow(one) else item(one)
            }
        }
        previousWasTool = isTool
    }
}

/** One call: icon, verb, target; tap to open its detail when the hub can give it. */
@Composable
internal fun ToolCallRow(tool: ConvItem.Tool, modifier: Modifier = Modifier) {
    val host = LocalToolDetails.current
    val id = tool.id
    val expandable = host.available && id != null
    val line = remember(tool) { tool.line() }
    var expanded by rememberSaveable(id ?: tool.summary) { mutableStateOf(host.expandAll) }
    val colors = MaterialTheme.colorScheme

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (expandable) 48.dp else 40.dp)
                .then(
                    if (expandable) {
                        Modifier.clickable(role = Role.Button, onClickLabel = if (expanded) "Hide details" else "Show details") {
                            expanded = !expanded
                        }
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(ICON), contentAlignment = Alignment.Center) {
                when {
                    !tool.done -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.primary,
                    )
                    tool.error -> Icon(FleetIcons.Failed, contentDescription = "failed", tint = colors.error, modifier = Modifier.size(ICON))
                    else -> Icon(iconFor(line.kind), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(ICON))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = line.verb,
                style = MaterialTheme.typography.labelLarge,
                color = if (tool.error) colors.error else colors.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            if (line.target != null) {
                Text(
                    text = line.target,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = if (line.pathLike) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (tool.error) {
                Spacer(Modifier.width(8.dp))
                Text("failed", style = MaterialTheme.typography.labelMedium, color = colors.error)
            }
            if (expandable) {
                Spacer(Modifier.width(8.dp))
                Chevron(expanded)
            }
        }
        if (expanded && expandable && id != null) {
            LaunchedEffect(id, tool.done) { host.request(id, tool.done) }
            ToolDetailCard(tool, line.kind, host.states[id], onRetry = { host.request(id, tool.done) })
        }
    }
}

/** "7 tool calls" over "Read, Grep, Edit +3", opening to the calls themselves. */
@Composable
internal fun ToolGroupRow(run: ItemRun.Tools, modifier: Modifier = Modifier) {
    val host = LocalToolDetails.current
    val key = run.tools.first().let { it.id ?: it.summary }
    // Keyed on `startExpanded` too: a group that picks up a failure, or
    // becomes the live end of the turn, opens even if it was drawn closed.
    var expanded by rememberSaveable(key, run.startExpanded) { mutableStateOf(run.startExpanded || host.expandAll) }
    val colors = MaterialTheme.colorScheme
    val running = run.tools.any { !it.done }
    val failed = run.failed

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand") { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(ICON), contentAlignment = Alignment.Center) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.primary)
                } else {
                    Icon(FleetIcons.Layers, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(ICON))
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(toolGroupTitle(run.tools), style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                Text(
                    toolGroupNames(run.tools),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (failed > 0) {
                Spacer(Modifier.width(8.dp))
                Pill("$failed failed", colors.errorContainer, colors.onErrorContainer)
            }
            Spacer(Modifier.width(8.dp))
            Chevron(expanded)
        }
        if (expanded) {
            val rail = colors.outlineVariant
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val x = (ICON / 2).toPx()
                        drawLine(rail, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    }
                    .padding(start = ICON / 2 + 12.dp),
            ) {
                for (tool in run.tools) ToolCallRow(tool)
            }
        }
    }
}

@Composable
private fun Chevron(expanded: Boolean) {
    Icon(
        FleetIcons.ChevronDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(ICON).rotate(if (expanded) 180f else 0f),
    )
}

@Composable
private fun Pill(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(8.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun iconFor(kind: ToolKind): ImageVector = when (kind) {
    ToolKind.Edit -> FleetIcons.Edit
    ToolKind.Bash -> FleetIcons.Terminal
    ToolKind.Read -> FleetIcons.Document
    ToolKind.Search -> FleetIcons.Search
    ToolKind.Todo -> FleetIcons.Checklist
    ToolKind.Other -> FleetIcons.Wrench
}

// ─── The expanded card ──────────────────────────────────────────────────────

@Composable
private fun ToolDetailCard(tool: ConvItem.Tool, kind: ToolKind, load: ToolDetailLoad?, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
    ) {
        Column(
            Modifier
                .heightIn(max = DETAIL_MAX_HEIGHT)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            when (load) {
                null, ToolDetailLoad.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                ToolDetailLoad.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Couldn't load details",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                is ToolDetailLoad.Loaded -> ToolDetailBody(tool, kind, load.detail)
            }
        }
    }
}

@Composable
private fun ToolDetailBody(tool: ConvItem.Tool, kind: ToolKind, detail: ToolDetail) {
    val edit = detail.edit
    when {
        edit != null -> EditDetailView(edit, isNewFile = detail.name == "Write" || tool.toolName() == "Write")
        kind == ToolKind.Bash -> BashDetail(detail.command ?: tool.toolTarget().orEmpty(), detail.result, detail.isError)
        kind == ToolKind.Read -> ReadDetail(
            path = inputString(detail.input, "file_path") ?: tool.toolTarget().orEmpty(),
            result = detail.result,
            isError = detail.isError,
        )
        kind == ToolKind.Search -> SearchDetail(
            pattern = inputString(detail.input, "pattern") ?: tool.toolTarget().orEmpty(),
            result = detail.result,
            isError = detail.isError,
        )
        kind == ToolKind.Todo -> {
            val todos = remember(detail.input) { parseTodos(detail.input) }
            if (todos != null) TodoDetail(todos) else FallbackDetail(detail)
        }
        else -> FallbackDetail(detail)
    }
}

// ─── Edit / MultiEdit / Write ───────────────────────────────────────────────

@Composable
private fun EditDetailView(edit: EditDetail, isNewFile: Boolean) {
    val lines = remember(edit) { lineDiff(edit.old, edit.new) }
    val rows = remember(lines) { collapseContext(lines) }
    val stat = remember(lines) { diffStat(lines) }
    val palette = diffColors()
    val (name, dir) = remember(edit.filePath) { splitPath(edit.filePath) }
    var showAll by remember(edit) { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text(
            dir,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        if (isNewFile) {
            Spacer(Modifier.width(6.dp))
            Text("new file", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text("+${stat.added}", style = MaterialTheme.typography.labelMedium, color = palette.addedFg, fontWeight = FontWeight.SemiBold)
        if (stat.removed > 0 || !isNewFile) {
            Spacer(Modifier.width(6.dp))
            Text("−${stat.removed}", style = MaterialTheme.typography.labelMedium, color = palette.removedFg, fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.padding(top = 8.dp))
    if (rows.isEmpty()) {
        Muted("No changes")
        return
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(6.dp))) {
        val shown = if (showAll) rows else rows.take(DIFF_ROW_CAP)
        for (row in shown) {
            when (row) {
                is DiffRow.Line -> DiffLineRow(row.line, palette)
                is DiffRow.Gap -> Text(
                    "⋯ ${row.count} unchanged lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(start = 48.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
    }
    if (!showAll && rows.size > DIFF_ROW_CAP) {
        TextButton(onClick = { showAll = true }) { Text("Show all ${rows.size} lines") }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine, palette: DiffColors) {
    val colors = MaterialTheme.colorScheme
    val (bg, signColor, sign) = when (line.kind) {
        DiffKind.Add -> Triple(palette.addedBg, palette.addedFg, "+")
        DiffKind.Del -> Triple(palette.removedBg, palette.removedFg, "−")
        DiffKind.Context -> Triple(Color.Transparent, colors.onSurfaceVariant, "")
    }
    val number = if (line.kind == DiffKind.Del) line.oldNo else line.newNo
    Row(Modifier.fillMaxWidth().background(bg)) {
        Text(
            number?.toString().orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant.copy(alpha = 0.8f),
            maxLines = 1,
            modifier = Modifier.width(32.dp).padding(end = 6.dp, top = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
        Text(
            sign,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = signColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(16.dp),
        )
        Text(
            line.text.ifEmpty { " " },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurface,
            modifier = Modifier.weight(1f).padding(end = 4.dp),
        )
    }
}

// ─── Bash ───────────────────────────────────────────────────────────────────

@Composable
private fun BashDetail(command: String, result: String?, isError: Boolean) {
    val colors = MaterialTheme.colorScheme
    var showEarlier by remember(result) { mutableStateOf(false) }
    if (isError) {
        Row {
            Pill("failed", colors.errorContainer, colors.onErrorContainer)
        }
        Spacer(Modifier.padding(top = 8.dp))
    }
    Surface(
        color = colors.inverseSurface,
        contentColor = colors.inverseOnSurface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "$ $command",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            when {
                result == null -> Text("running…", fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                result.isBlank() -> Text("(no output)", fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                else -> {
                    val (hidden, tail) = remember(result) { tailLines(result, BASH_TAIL) }
                    if (hidden > 0 && !showEarlier) {
                        Text(
                            "Show $hidden earlier lines",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.inversePrimary,
                            modifier = Modifier
                                .clickable(role = Role.Button) { showEarlier = true }
                                .padding(vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.padding(top = 4.dp))
                    Text(
                        if (showEarlier) result.trimEnd('\n') else tail.joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

// ─── Read ───────────────────────────────────────────────────────────────────

@Composable
private fun ReadDetail(path: String, result: String?, isError: Boolean) {
    val colors = MaterialTheme.colorScheme
    val (name, dir) = remember(path) { splitPath(path) }
    var showAll by remember(result) { mutableStateOf(false) }
    PathHeader(name, dir)
    Spacer(Modifier.padding(top = 8.dp))
    val numbered = remember(result) { result?.let(::parseNumberedLines) }
    when {
        result == null -> Muted("Reading…")
        isError || numbered == null -> MonoBlock(result, isError)
        else -> {
            val shown = if (showAll) numbered else numbered.take(READ_CAP)
            Column(Modifier.fillMaxWidth()) {
                for (line in shown) {
                    Row {
                        Text(
                            line.number.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.width(32.dp).padding(end = 8.dp, top = 2.dp),
                        )
                        Text(
                            line.text.ifEmpty { " " },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (!showAll && numbered.size > READ_CAP) {
                TextButton(onClick = { showAll = true }) { Text("${numbered.size - READ_CAP} more lines") }
            }
        }
    }
}

// ─── Grep / Glob ────────────────────────────────────────────────────────────

@Composable
private fun SearchDetail(pattern: String, result: String?, isError: Boolean) {
    val colors = MaterialTheme.colorScheme
    var showAll by remember(result) { mutableStateOf(false) }
    if (pattern.isNotBlank()) {
        Row {
            Surface(color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer, shape = RoundedCornerShape(8.dp)) {
                Text(
                    pattern,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.padding(top = 8.dp))
    }
    when {
        result == null -> Muted("Searching…")
        isError -> MonoBlock(result, true)
        else -> {
            val lines = remember(result) { searchResultLines(result) }
            if (lines.isEmpty()) {
                Muted("No matches")
            } else {
                val shown = if (showAll) lines else lines.take(SEARCH_CAP)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (line in shown) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!showAll && lines.size > SEARCH_CAP) {
                    TextButton(onClick = { showAll = true }) { Text("+${lines.size - SEARCH_CAP} more") }
                }
            }
        }
    }
}

// ─── TodoWrite ──────────────────────────────────────────────────────────────

@Composable
private fun TodoDetail(todos: List<TodoEntry>) {
    val colors = MaterialTheme.colorScheme
    if (todos.isEmpty()) {
        Muted("No todos")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (todo in todos) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(ICON), contentAlignment = Alignment.Center) {
                    when (todo.status) {
                        TodoStatus.Completed -> Icon(FleetIcons.Check, contentDescription = "done", tint = colors.primary, modifier = Modifier.size(ICON))
                        TodoStatus.InProgress -> Box(Modifier.size(12.dp).background(colors.primary, CircleShape))
                        TodoStatus.Pending -> Box(Modifier.size(12.dp).border(1.5.dp, colors.onSurfaceVariant, CircleShape))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    todo.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (todo.status == TodoStatus.Completed) colors.onSurfaceVariant else colors.onSurface,
                    fontWeight = if (todo.status == TodoStatus.InProgress) FontWeight.SemiBold else FontWeight.Normal,
                    textDecoration = if (todo.status == TodoStatus.Completed) TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

// ─── Anything else ──────────────────────────────────────────────────────────

@Composable
private fun FallbackDetail(detail: ToolDetail) {
    if (detail.input.isNotBlank() && detail.input.trim() != "{}") {
        Label("Input")
        MonoBlock(detail.input, false)
        Spacer(Modifier.padding(top = 8.dp))
    }
    Label(if (detail.isError) "Error" else "Result")
    if (detail.result == null) Muted("Waiting for the result…") else MonoBlock(detail.result, detail.isError)
}

@Composable
private fun PathHeader(name: String, dir: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text(
            dir,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Monospace text, capped at [FALLBACK_CAP] lines until asked for the rest. */
@Composable
private fun MonoBlock(text: String, isError: Boolean) {
    var showAll by remember(text) { mutableStateOf(false) }
    val lines = remember(text) { text.trimEnd('\n').split('\n') }
    Text(
        if (showAll || lines.size <= FALLBACK_CAP) lines.joinToString("\n") else lines.take(FALLBACK_CAP).joinToString("\n"),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    if (!showAll && lines.size > FALLBACK_CAP) {
        TextButton(onClick = { showAll = true }) { Text("${lines.size - FALLBACK_CAP} more lines") }
    }
}
