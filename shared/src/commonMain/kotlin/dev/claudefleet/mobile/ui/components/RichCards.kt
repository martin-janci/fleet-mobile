package dev.claudefleet.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.claudefleet.mobile.data.FleetSettingsActions
import dev.claudefleet.mobile.model.Page
import dev.claudefleet.mobile.model.ProgressBoard
import dev.claudefleet.mobile.model.ResultItem
import dev.claudefleet.mobile.model.formatResultCell
import dev.claudefleet.mobile.ui.SettingCardModel
import dev.claudefleet.mobile.ui.whoWords
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.ReplyField
import dev.claudefleet.mobile.model.ReplyForm
import dev.claudefleet.mobile.model.RichSegment
import dev.claudefleet.mobile.model.TaskReport
import dev.claudefleet.mobile.model.UiBlock
import dev.claudefleet.mobile.model.fenced
import dev.claudefleet.mobile.model.fieldMissing
import dev.claudefleet.mobile.model.formAnswerPrompt
import dev.claudefleet.mobile.model.formDefaults
import dev.claudefleet.mobile.model.splitRich
import dev.claudefleet.mobile.model.visibleFields
import dev.claudefleet.mobile.ui.theme.LocalStatusColors
import dev.claudefleet.mobile.ui.theme.StatusTone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import dev.claudefleet.mobile.ui.kit.ProgressRing

/*
 * The cards a reply can hold (model/RichBlocks.kt; claude-fleet's
 * docs/chat-blocks.md is the format, src/lib/RichText.svelte the desktop's
 * drawing of it). A card is a view of the reply: nothing is stored and
 * nothing is sent from one. A card that acts only puts text in the composer,
 * through [LocalComposerFill], and the person sends it.
 */

/**
 * What the cards that act on the hub may call (step 10.8), each null where
 * this hub or this device does not offer it: the default offers nothing, so
 * a preview or a test that does not care draws them read-only.
 */
class ChatHost(
    /** `setting_proposals` and, with [mayDecideSettings], its decision. */
    val settings: FleetSettingsActions? = null,
    /** A full credential, and the hub lists `decide_setting_proposals`. */
    val mayDecideSettings: Boolean = false,
    /** `progress` blocks of the conversation on screen, by id. */
    val progress: ProgressBoard = ProgressBoard.EMPTY,
    /** Unix seconds, for a results card's `time` values. */
    val nowSeconds: Long = 0,
) {
    fun copyWith(progress: ProgressBoard, nowSeconds: Long) = ChatHost(settings, mayDecideSettings, progress, nowSeconds)

    companion object {
        val None = ChatHost()
    }
}

val LocalChatHost = compositionLocalOf { ChatHost.None }

/**
 * Puts text in this session's composer, after whatever is typed. `null`
 * where nothing may be typed (a read-only pairing, no session): a card then
 * draws its actions turned off.
 */
val LocalComposerFill = compositionLocalOf<((String) -> Unit)?> { null }

/** Hub assistant text: Markdown, with task reports and fleet-ui blocks as cards. */
@Composable
fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val segments = remember(text) { splitRich(text) }
    val only = segments.singleOrNull()
    if (only is RichSegment.Md) {
        MarkdownText(only.source, modifier = modifier, style = style)
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in segments) {
            when (s) {
                is RichSegment.Md -> MarkdownText(s.source, style = style)
                is RichSegment.Report -> ReportCard(s.report, s.raw, title = null, marker = s.marker)
                is RichSegment.Ui -> UiBlockCard(s.block, s.raw)
                is RichSegment.Invalid -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MarkdownText(fenced(s.lang, s.raw), style = style)
                    Text(
                        "Not shown as a card: ${s.problems.joinToString("; ")}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(RICH_INVALID),
                    )
                }
            }
        }
    }
}

internal const val RICH_INVALID = "rich-invalid"
internal const val RICH_REPORT = "rich-report"
internal const val RICH_CARD = "rich-card"

@Composable
private fun Card(accent: Color, tag: String, content: @Composable () -> Unit) {
    // The accent bar is drawn, not laid out: a bar the card's height would
    // need an intrinsic measure of everything inside it.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .drawBehind { drawRect(accent, size = Size(4.dp.toPx(), size.height)) }
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Label(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun Chip(text: String, tone: StatusTone?) {
    val c = tone?.let { LocalStatusColors.current(it) }
    Surface(
        color = c?.container ?: MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = c?.onContainer ?: MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

private fun outcomeTone(outcome: String) = when (outcome) {
    "done" -> StatusTone.COMPLETED
    "partial" -> StatusTone.BLOCKED
    else -> StatusTone.FAILED
}

private fun outcomeLabel(outcome: String) = when (outcome) {
    "done" -> "✓ Done"
    "partial" -> "◐ Partly done"
    "blocked" -> "■ Blocked"
    else -> "✗ Failed"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReportCard(report: TaskReport, raw: String, title: String?, marker: String?) {
    val fill = LocalComposerFill.current
    val tone = outcomeTone(report.outcome)
    var showRaw by rememberSaveable(raw) { mutableStateOf(false) }
    Card(LocalStatusColors.current(tone).dot, RICH_REPORT) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Heading(title ?: "Task report")
            Chip(outcomeLabel(report.outcome), tone)
            report.confidence?.let { Chip("confidence $it", null) }
        }
        if (report.summary.isNotEmpty()) MarkdownText(report.summary)
        val error = MaterialTheme.colorScheme.error
        ReportList("Blockers", report.blockers, error)
        ReportList("Warnings", report.warnings, LocalStatusColors.current(StatusTone.BLOCKED).dot)
        ReportList("Tests run", report.testsRun, MaterialTheme.colorScheme.onSurfaceVariant, mono = true)
        if (report.followups.isNotEmpty()) {
            Column {
                Label("Follow-ups")
                for (f in report.followups) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("• $f", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (fill != null) TextButton(onClick = { fill(f) }) { Text("Ask") }
                    }
                }
            }
        }
        TextButton(onClick = { showRaw = !showRaw }, contentPadding = PaddingValues(0.dp)) {
            Text(
                if (showRaw) "Hide source" else marker?.let { "Reported after $it" } ?: "Source",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (showRaw) MarkdownText(fenced("json", raw))
    }
}

@Composable
private fun ReportList(label: String, items: List<String>, color: Color, mono: Boolean = false) {
    if (items.isEmpty()) return
    Column {
        Label(label, color)
        for (i in items) {
            Text(
                "• $i",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (mono) FontFamily.Monospace else null,
            )
        }
    }
}

@Composable
internal fun UiBlockCard(block: UiBlock, raw: String) {
    val colors = MaterialTheme.colorScheme
    when (block) {
        is UiBlock.Report -> ReportCard(block.report, raw, block.title, marker = null)
        is UiBlock.Callout -> {
            val accent = when (block.tone) {
                "success" -> LocalStatusColors.current(StatusTone.COMPLETED).dot
                "warning" -> LocalStatusColors.current(StatusTone.BLOCKED).dot
                "danger" -> colors.error
                "tip" -> colors.tertiary
                else -> colors.primary
            }
            Card(accent, RICH_CARD) {
                block.title?.let { Heading(it) }
                MarkdownText(block.body)
            }
        }
        is UiBlock.Facts -> Card(colors.outlineVariant, RICH_CARD) {
            block.title?.let { Heading(it) }
            for ((label, value) in block.items) {
                Row {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(0.4f))
                    Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
                }
            }
        }
        is UiBlock.Steps -> StepsCard(block, raw)
        is UiBlock.Guide -> Card(colors.outlineVariant, RICH_CARD) {
            Heading(block.title)
            block.intro?.let { MarkdownText(it) }
            block.sections.forEachIndexed { k, s ->
                var open by rememberSaveable(raw, k) { mutableStateOf(k == 0) }
                Column {
                    Text(
                        (if (open) "▾ " else "▸ ") + s.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
                    )
                    if (open) MarkdownText(s.body, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
        is UiBlock.Choices -> {
            val fill = LocalComposerFill.current
            Card(colors.primary, RICH_CARD) {
                block.title?.let { Heading(it) }
                block.question?.let { MarkdownText(it) }
                for (o in block.options) {
                    OutlinedButton(onClick = { fill?.invoke(o.prompt) }, enabled = fill != null, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(o.label, fontWeight = FontWeight.SemiBold)
                            o.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                        }
                    }
                }
                Note(if (fill == null) "Choices fill the composer of a session you can write to." else "A choice fills the composer; send it yourself.")
            }
        }
        is UiBlock.Form -> FormCard(block.form, raw)
        is UiBlock.Progress -> ProgressCard(block, raw)
        is UiBlock.Results -> ResultsCard(block)
        is UiBlock.Error -> ErrorCard(block)
        is UiBlock.Setting -> SettingCard(block)
        is UiBlock.GuidePage -> GuidePageCard(block.page)
    }
}

internal const val RICH_PROGRESS = "rich-progress"
internal const val RICH_PROGRESS_MOVED = "rich-progress-moved"
internal const val RICH_RESULTS = "rich-results"
internal const val RICH_ERROR = "rich-error"
internal const val RICH_SETTING = "rich-setting"
internal const val RICH_SETTING_APPLY = "rich-setting-apply"
internal const val RICH_GUIDE_PAGE = "rich-guide-page"

private val PROGRESS_LABEL = mapOf("running" to "Running", "waiting" to "Needs you", "done" to "Done", "failed" to "Failed")
private val STEP_MARK = mapOf("pending" to "○", "running" to "◐", "done" to "✓", "failed" to "✕", "skipped" to "–")

private fun progressTone(state: String) = when (state) {
    "done" -> StatusTone.COMPLETED
    "failed" -> StatusTone.FAILED
    "waiting" -> StatusTone.BLOCKED
    else -> StatusTone.WORKING
}

/** "3 of 12 files", "40 files so far", or null when the block counts nothing. */
internal fun progressCount(b: UiBlock.Progress): String? {
    val unit = b.unit?.let { " $it" } ?: ""
    return when {
        b.total != null -> "${b.done ?: 0} of ${b.total}$unit"
        b.done != null -> "${b.done}$unit so far"
        else -> null
    }
}

/**
 * A `progress` block. The first card of an id in the conversation shows the
 * newest state; a later one is a line saying it moved on. No loader: a
 * running job shows its count and steps, and a job waiting on a person never
 * animates.
 */
@Composable
private fun ProgressCard(block: UiBlock.Progress, raw: String) {
    val board = LocalChatHost.current.progress
    val colors = MaterialTheme.colorScheme
    if (!board.home(block.id, raw)) {
        Text(
            "↑ ${block.title}: ${PROGRESS_LABEL[block.state].orEmpty().lowercase()}, shown above",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.testTag(RICH_PROGRESS_MOVED),
        )
        return
    }
    val shown = board.newest(block.id) ?: block
    val tone = progressTone(shown.state)
    Card(LocalStatusColors.current(tone).dot, RICH_PROGRESS) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Heading(shown.title) }
            Chip(PROGRESS_LABEL[shown.state] ?: shown.state, tone)
        }
        progressCount(shown)?.let { count ->
            if (shown.total != null) {
                val frac = ((shown.done ?: 0L).coerceAtMost(shown.total).toFloat() / shown.total).coerceIn(0f, 1f)
                ProgressRing(frac)
            }
            Note(count)
        }
        shown.steps?.forEach { st ->
            Text(
                "${STEP_MARK[st.state] ?: "○"}  ${st.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (st.state == "skipped" || st.state == "pending") colors.onSurfaceVariant else colors.onSurface,
            )
        }
        shown.note?.let { MarkdownText(it) }
        val updates = board.updates(block.id)
        if (updates > 0) Note(if (updates == 1) "1 update below" else "$updates updates below")
    }
}

/** A `results` block: numbers, a chart and a table, from the block itself. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultsCard(block: UiBlock.Results) {
    val colors = MaterialTheme.colorScheme
    val now = LocalChatHost.current.nowSeconds
    Card(colors.outlineVariant, RICH_RESULTS) {
        block.title?.let { Heading(it) }
        block.summary?.let { MarkdownText(it) }
        val stats = block.items.filterIsInstance<ResultItem.Stat>()
        if (stats.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (st in stats) {
                    Column {
                        Label(st.label)
                        val text = when (val v = st.value) {
                            is String -> v
                            else -> formatResultCell(st.ty ?: "int", JsonPrimitive(v as Double), now)
                        }
                        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        st.hint?.let { Note(it) }
                    }
                }
            }
        }
        for (item in block.items) {
            when (item) {
                is ResultItem.Chart -> ResultChart(item)
                is ResultItem.Table -> ResultTable(item, now)
                is ResultItem.Stat -> Unit
            }
        }
    }
}

/** A chart as the phone has room for it: bars, or a line, scaled to the largest value. */
@Composable
private fun ResultChart(item: ResultItem.Chart) {
    val colors = MaterialTheme.colorScheme
    val ys = item.points.map { it.second }
    val top = (ys.maxOrNull() ?: 0.0).coerceAtLeast(0.0)
    val bottom = (ys.minOrNull() ?: 0.0).coerceAtMost(0.0)
    val span = (top - bottom).takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (item.chart != "sparkline") Text(item.title, style = MaterialTheme.typography.labelLarge)
        val accent = colors.primary
        Canvas(
            Modifier.fillMaxWidth().height(if (item.chart == "sparkline") 32.dp else 96.dp)
                .semantics { contentDescription = "${item.title}: ${item.points.size} points" },
        ) {
            val n = item.points.size
            if (n == 0) return@Canvas
            fun yOf(v: Double) = size.height * (1f - ((v - bottom) / span).toFloat())
            if (item.chart == "bar") {
                val w = size.width / n
                item.points.forEachIndexed { k, (_, v) ->
                    val y = yOf(v)
                    val zero = yOf(0.0)
                    drawRect(accent, topLeft = Offset(k * w + w * 0.15f, minOf(y, zero)), size = Size(w * 0.7f, kotlin.math.abs(zero - y)))
                }
            } else {
                val step = if (n > 1) size.width / (n - 1) else 0f
                for (k in 1 until n) {
                    drawLine(accent, Offset((k - 1) * step, yOf(item.points[k - 1].second)), Offset(k * step, yOf(item.points[k].second)), strokeWidth = 2.dp.toPx())
                }
            }
        }
        if (item.chart != "sparkline" && item.points.isNotEmpty()) {
            Row {
                Text(xText(item.points.first().first), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(xText(item.points.last().first), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

private fun xText(x: Any): String = when (x) {
    is Double -> if (x == kotlin.math.floor(x)) x.toLong().toString() else x.toString()
    else -> x.toString()
}

/** A table, scrolling sideways when its columns do not fit. */
@Composable
private fun ResultTable(item: ResultItem.Table, now: Long) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item.title?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                for (c in item.columns) {
                    Text(c.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.width(112.dp).padding(end = 8.dp))
                }
            }
            for (row in item.rows) {
                Row {
                    item.columns.forEachIndexed { j, c ->
                        Text(
                            cellText(row.getOrNull(j), c.ty, now),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(112.dp).padding(end = 8.dp, top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A table cell: a bool as yes / no, a number by its column's type. */
internal fun cellText(c: JsonElement?, ty: String?, now: Long): String {
    val p = c as? JsonPrimitive
    if (p != null && !p.isString && p.booleanOrNull != null) return if (p.booleanOrNull == true) "yes" else "no"
    val isNumber = p != null && !p.isString && p !is JsonNull
    return formatResultCell(ty ?: if (isNumber) "int" else "text", c, now)
}

/** An `error` block: what failed, its code, and the next steps, which fill the composer. */
@Composable
private fun ErrorCard(block: UiBlock.Error) {
    val fill = LocalComposerFill.current
    val colors = MaterialTheme.colorScheme
    var showDetail by rememberSaveable(block.code, block.title) { mutableStateOf(false) }
    Card(colors.error, RICH_ERROR) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Heading(block.title) }
            Text(block.code, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = colors.error)
        }
        block.body?.let { MarkdownText(it) }
        block.detail?.let { d ->
            TextButton(onClick = { showDetail = !showDetail }, contentPadding = PaddingValues(0.dp)) {
                Text(if (showDetail) "Hide details" else "Details", style = MaterialTheme.typography.labelSmall)
            }
            if (showDetail) MarkdownText(fenced("", d))
        }
        if (block.next.isNotEmpty()) {
            for (o in block.next) {
                OutlinedButton(onClick = { fill?.invoke(o.prompt) }, enabled = fill != null, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(o.label, fontWeight = FontWeight.SemiBold)
                        o.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                    }
                }
            }
            Note(if (fill == null) "Next steps fill the composer of a session you can write to." else "A next step fills the composer; send it yourself.")
        }
    }
}

/**
 * A `setting` block: a settings change an agent proposed, decided here. The
 * change shown is the proposal's, read from the hub; Apply asks first.
 */
@Composable
private fun SettingCard(block: UiBlock.Setting) {
    val host = LocalChatHost.current
    val colors = MaterialTheme.colorScheme
    val actions = host.settings
    if (actions == null) {
        Card(colors.tertiary, RICH_SETTING) {
            Label("Settings change")
            block.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Note("An agent proposed a settings change. Review it in fleet's Settings › Review.")
        }
        return
    }
    val scope = rememberCoroutineScope()
    val model = remember(block.proposal, actions) { SettingCardModel(actions, block.proposal, scope, host.mayDecideSettings) }
    LaunchedEffect(model) { model.load() }
    val s by model.state.collectAsState()
    Card(colors.tertiary, RICH_SETTING) {
        Label("Settings change")
        val p = s.proposal
        when {
            s.done == "applied" -> {
                val applied = s.applied
                Text(
                    "Applied" + (applied?.let { ": ${s.label} is now ${s.words(it.second)}" } ?: "") + ".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Note("Undo it in Settings.")
            }
            p != null -> {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(colors.surfaceContainerHighest).padding(8.dp),
                ) {
                    Text(s.label ?: p.key, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text("− ${s.words(p.current)}", fontFamily = FontFamily.Monospace, color = colors.error, style = MaterialTheme.typography.bodySmall)
                    Text("+ ${s.words(p.value)}", fontFamily = FontFamily.Monospace, color = LocalStatusColors.current(StatusTone.COMPLETED).dot, style = MaterialTheme.typography.bodySmall)
                }
                block.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (s.moved) Note("Changed since it was proposed (it was ${s.words(p.before)}).")
                p.why?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                Note("✦ suggested by ${whoWords(p.source, p.sourceDetail)}")
                s.failure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
                when {
                    s.done == "later" -> Note("Not applied. It still waits in Settings › Review.")
                    !s.canWrite -> Note("This device cannot change the fleet's settings; the hub's operator decides it.")
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = model::askApply, enabled = !s.busy, modifier = Modifier.testTag(RICH_SETTING_APPLY)) { Text("Apply") }
                        OutlinedButton(onClick = model::later, enabled = !s.busy) { Text("Not now") }
                    }
                }
            }
            s.loaded && s.failure != null -> Text(s.failure!!, style = MaterialTheme.typography.bodySmall, color = colors.error)
            s.loaded -> Note("This change no longer waits for review: it was applied or rejected.")
            else -> Note("Reading the proposal…")
        }
    }
    if (s.confirming) {
        val danger = s.descriptor?.danger?.confirms == true
        AlertDialog(
            onDismissRequest = model::cancel,
            title = { Text("Apply this settings change?") },
            text = { Text(s.question) },
            confirmButton = {
                TextButton(onClick = { model.apply() }) {
                    Text("Apply", color = if (danger) colors.error else Color.Unspecified)
                }
            },
            dismissButton = { TextButton(onClick = model::cancel) { Text("Cancel") } },
        )
    }
}

/**
 * A `guide` block with `page`: a guide fleet already has. The phone does not
 * draw guide pages, so the card names it and says where it lives; its title
 * comes from the hub's pages when this device may read them.
 */
@Composable
private fun GuidePageCard(pageId: String) {
    val actions = LocalChatHost.current.settings
    val colors = MaterialTheme.colorScheme
    val page by produceState<Page?>(null, pageId, actions) {
        value = try {
            actions?.pages()?.pages?.firstOrNull { it.id == pageId && it.layout == "guide" }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
    }
    Card(colors.outlineVariant, RICH_GUIDE_PAGE) {
        Label("Guide")
        val p = page
        if (p != null) {
            Heading(p.title)
            p.intro?.let { MarkdownText(it) }
            p.sections.forEachIndexed { k, sec -> Text("${k + 1}. ${sec.title}", style = MaterialTheme.typography.bodyMedium) }
        } else {
            Text(pageId, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        Note("Walk it in fleet's Settings › Guides on the desktop.")
    }
}

@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StepsCard(block: UiBlock.Steps, raw: String) {
    val colors = MaterialTheme.colorScheme
    // A list, not a set: it has to survive the saved-state bundle.
    var done by rememberSaveable(raw) { mutableStateOf(listOf<Int>()) }
    Card(colors.outlineVariant, RICH_CARD) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Heading(block.title) }
            Text("${done.size} of ${block.steps.size} done", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        block.intro?.let { MarkdownText(it) }
        block.steps.forEachIndexed { k, s ->
            val checked = k in done
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox) {
                        done = if (it) done + k else done - k
                    },
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(
                        "${k + 1}. ${s.title}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (checked) colors.onSurfaceVariant else colors.onSurface,
                        textDecoration = if (checked) TextDecoration.LineThrough else null,
                    )
                }
                Column(Modifier.padding(start = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.body?.let { MarkdownText(it) }
                    s.code?.let { MarkdownText(fenced(s.lang ?: "", it)) }
                }
            }
        }
    }
}

/**
 * A fleet.form/1 form from a reply, one page with every shown step under its
 * title (the phone has no room for the desktop's wizard chrome, and a reply
 * form is short). Submitting puts the answers in the composer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormCard(form: ReplyForm, raw: String) {
    val fill = LocalComposerFill.current
    val colors = MaterialTheme.colorScheme
    var values by remember(raw) { mutableStateOf(formDefaults(form)) }
    // The text of each number field as typed, so "1." is not reformatted under the thumb.
    var numberText by remember(raw) { mutableStateOf(mapOf<String, String>()) }
    var sent by rememberSaveable(raw) { mutableStateOf(false) }
    val shown = visibleFields(form, values)
    val badNumbers = shown.flatMap { it.second }.filter { it.type == "number" && numberText[it.name]?.let { t -> t.isNotBlank() && parseNumber(it, t) == null } == true }
    val ready = shown.flatMap { it.second }.none { fieldMissing(it, values[it.name]) } && badNumbers.isEmpty()
    fun set(name: String, v: JsonElement?) {
        values = if (v == null) values - name else values + (name to v)
    }
    Card(colors.primary, RICH_CARD) {
        Heading(form.title)
        form.intro?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (sent) {
            Note("Answers are in the composer. Send them when you are ready.")
            return@Card
        }
        for ((step, fields) in shown) {
            if (form.steps.size > 1) Label(step.title)
            step.intro?.let { Note(it) }
            for (f in fields) {
                FieldView(
                    f,
                    values[f.name],
                    numberText[f.name],
                    enabled = fill != null,
                    onNumberText = { t ->
                        numberText = numberText + (f.name to t)
                        set(f.name, if (t.isBlank()) null else parseNumber(f, t))
                    },
                    onChange = { set(f.name, it) },
                )
            }
        }
        OutlinedButton(
            enabled = fill != null && ready,
            onClick = {
                fill?.invoke(formAnswerPrompt(form, values))
                sent = true
            },
        ) { Text(form.submit ?: "Submit") }
        Note(if (fill == null) "This form fills the composer of a session you can write to." else "Submitting puts the answers in the composer; nothing is sent until you send it.")
    }
}

/** A number as typed, inside the field's bounds, or null. A whole number is
 *  kept whole so the answers read `2`, not `2.0`, as on the desktop. */
internal fun parseNumber(f: ReplyField, t: String): JsonPrimitive? {
    val d = t.trim().toDoubleOrNull() ?: return null
    if (!d.isFinite()) return null
    if (f.integer && d != kotlin.math.floor(d)) return null
    if (f.min != null && d < f.min) return null
    if (f.max != null && d > f.max) return null
    return if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FieldView(
    f: ReplyField,
    value: JsonElement?,
    typed: String?,
    enabled: Boolean,
    onNumberText: (String) -> Unit,
    onChange: (JsonElement?) -> Unit,
) {
    val label = f.label + if (f.required && f.type != "bool") " *" else ""
    val str = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (f.type) {
            "bool" -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().toggleable(
                    value = (value as? JsonPrimitive)?.booleanOrNull == true,
                    enabled = enabled,
                    role = Role.Switch,
                ) { onChange(JsonPrimitive(it)) },
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = (value as? JsonPrimitive)?.booleanOrNull == true, enabled = enabled, onCheckedChange = null)
            }
            "select", "multiselect" -> {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                val picked: Set<String> = when (value) {
                    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
                    is JsonPrimitive -> setOf(value.content)
                    else -> emptySet()
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((v, l) in f.options) {
                        FilterChip(
                            selected = v in picked,
                            enabled = enabled,
                            onClick = {
                                if (f.type == "select") {
                                    onChange(JsonPrimitive(v))
                                } else {
                                    val next = if (v in picked) picked - v else picked + v
                                    // In option order, as the desktop sends a multiselect.
                                    onChange(JsonArray(f.options.map { it.first }.filter { it in next }.map { JsonPrimitive(it) }))
                                }
                            },
                            label = { Text(l) },
                        )
                    }
                }
            }
            "number" -> OutlinedTextField(
                value = typed ?: (value as? JsonPrimitive)?.content ?: "",
                onValueChange = onNumberText,
                label = { Text(label) },
                singleLine = true,
                enabled = enabled,
                isError = typed != null && typed.isNotBlank() && parseNumber(f, typed) == null,
                modifier = Modifier.fillMaxWidth(),
            )
            // Only in a form an agent's `ask` opened: its answer goes to a
            // file on the session's host, never into the transcript.
            "secret" -> OutlinedTextField(
                value = str,
                onValueChange = { onChange(if (it.isEmpty()) null else JsonPrimitive(it)) },
                label = { Text(label) },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            else -> OutlinedTextField(
                value = str,
                onValueChange = { onChange(if (it.isEmpty()) null else JsonPrimitive(it)) },
                label = { Text(label) },
                placeholder = if (f.placeholder != null) ({ Text(f.placeholder) }) else null,
                singleLine = f.type != "textarea",
                minLines = if (f.type == "textarea") 3 else 1,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        f.help?.let { Note(it) }
    }
}
