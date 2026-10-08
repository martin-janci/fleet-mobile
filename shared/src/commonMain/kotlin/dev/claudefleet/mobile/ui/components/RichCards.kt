package dev.claudefleet.mobile.ui.components

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

/*
 * The cards a reply can hold (model/RichBlocks.kt; claude-fleet's
 * docs/chat-blocks.md is the format, src/lib/RichText.svelte the desktop's
 * drawing of it). A card is a view of the reply: nothing is stored and
 * nothing is sent from one. A card that acts only puts text in the composer,
 * through [LocalComposerFill], and the person sends it.
 */

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
    }
}

@Composable
private fun Note(text: String) {
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
private fun parseNumber(f: ReplyField, t: String): JsonPrimitive? {
    val d = t.trim().toDoubleOrNull() ?: return null
    if (!d.isFinite()) return null
    if (f.integer && d != kotlin.math.floor(d)) return null
    if (f.min != null && d < f.min) return null
    if (f.max != null && d > f.max) return null
    return if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldView(
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
