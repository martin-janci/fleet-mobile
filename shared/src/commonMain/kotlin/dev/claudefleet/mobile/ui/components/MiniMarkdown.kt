package dev.claudefleet.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.theme.FleetIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A minimal, dependency-free Markdown renderer for hub-sourced assistant
 * text (`ConvItem.Text`).
 *
 * Route B of the Task 2 markdown work (2026-09-21): the alternative was
 * `com.mikepenz:multiplatform-markdown-renderer-m3`, which resolved and
 * compiled fine against Compose Multiplatform 1.12 / Kotlin 2.4.20, but grew
 * the iOS simulator debug framework by ~7.4 MB against a 2 MB gate --
 * abandoned for that reason, not for a compatibility failure. See the task
 * report for both measurements.
 *
 * [parseMarkdown] is a pure function: no Compose runtime, no Composable in
 * its call graph, so it is unit-testable on its own (`MiniMarkdownTest.kt`)
 * without a rendering environment. [MarkdownText] is the thin Composable
 * that walks its output.
 *
 * Deliberately not a general Markdown implementation -- no tables, no nested
 * emphasis, no reference links. It covers exactly what a Claude Code
 * transcript actually contains: a fenced code block, a `- `/`* ` list, a
 * `#` heading (demoted to a bold paragraph, not a distinct visual size --
 * there is no heading tier in a chat bubble), and `**bold**`/`*italic*`/
 * `` `code` `` inline spans.
 */
sealed class MdBlock {
    data class Paragraph(val text: AnnotatedString) : MdBlock()
    data class Code(val text: String, val lang: String?) : MdBlock()
    data class Bullet(val text: AnnotatedString) : MdBlock()
}

private val FENCE = Regex("^```(\\S*)\\s*$")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")

/**
 * Splits fenced ` ``` ` blocks out first -- their contents are never
 * reinterpreted as bullets/headings/inline spans, only carried verbatim --
 * then walks the remaining lines for bullets, headings and paragraphs.
 * Consecutive plain lines join into one paragraph, split on a blank line.
 */
fun parseMarkdown(text: String): List<MdBlock> {
    // `\r\n` -> `\n`, then a lone `\r` (old Mac line endings, or a stray
    // carriage return) -> `\n` too, so every downstream line-based check
    // (fence, bullet, heading, blank-line paragraph split) sees one newline
    // convention regardless of what the hub sent, and no `\r` ever ends up
    // inside a rendered `Text`.
    val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    val lines = normalized.split("\n")
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(parseInline(paragraph.toString()))
            paragraph.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fenceOpen = FENCE.matchEntire(line.trim())
        if (fenceOpen != null) {
            flushParagraph()
            val lang = fenceOpen.groupValues[1].ifBlank { null }
            val code = StringBuilder()
            i++
            while (i < lines.size && !FENCE.matches(lines[i].trim())) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[i])
                i++
            }
            // A fence with no closing ``` still ends the code block at EOF
            // rather than swallowing nothing -- `i` is already past the last
            // code line; skip the closing fence line itself if one exists.
            if (i < lines.size) i++
            blocks += MdBlock.Code(code.toString(), lang)
            continue
        }

        val trimmed = line.trimStart()
        val heading = HEADING.matchEntire(trimmed)
        when {
            trimmed.isBlank() -> flushParagraph()
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                blocks += MdBlock.Bullet(parseInline(trimmed.substring(2)))
            }
            heading != null -> {
                flushParagraph()
                val inner = parseInline(heading.groupValues[2])
                val bolded = buildAnnotatedString {
                    append(inner)
                    if (inner.isNotEmpty()) {
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, inner.length)
                    }
                }
                blocks += MdBlock.Paragraph(bolded)
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
        i++
    }
    flushParagraph()
    return blocks
}

/**
 * `**bold**`, `*italic*` and `` `code` `` -- a single left-to-right scan, no
 * nesting between the three. An opening marker with no matching close (an
 * unterminated `**`, a stray `` ` ``) is emitted as literal text rather than
 * silently dropped or left to swallow the rest of the line.
 *
 * Three lookup tables -- [nextMarker] -- are built in one forward pass before
 * the scan starts, one each for `` ` ``, `**` and a lone `*`, so every
 * "where is this marker's close" question below is an O(1) array read rather
 * than an `indexOf` that walks the rest of the string. `indexOf` from the
 * current position was the shape a stray marker made expensive: a review
 * finding on a transcript heavy with un-escaped `` ` ``/`*` (a diff, a shell
 * one-liner) put the same tail of the string under the microscope once per
 * stray character, which is quadratic in the number of them. The table read
 * back is exactly what `source.indexOf(marker, from)` would have returned --
 * this changes nothing about *which* marker closes which, only how fast the
 * answer comes back.
 */
private fun parseInline(source: String): AnnotatedString = buildAnnotatedString {
    val n = source.length
    val nextBacktick = nextMarker(n) { j -> source[j] == '`' }
    val nextDoubleStar = nextMarker(n) { j -> source[j] == '*' && j + 1 < n && source[j + 1] == '*' }
    val nextStar = nextMarker(n) { j -> source[j] == '*' }

    var i = 0
    while (i < n) {
        val c = source[i]
        when {
            c == '`' -> {
                val close = nextBacktick[i + 1]
                if (close == n) {
                    append(c)
                    i++
                } else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(source.substring(i + 1, close))
                    }
                    i = close + 1
                }
            }
            c == '*' && i + 1 < n && source[i + 1] == '*' -> {
                val close = nextDoubleStar[i + 2]
                if (close == n) {
                    append("**")
                    i += 2
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(source.substring(i + 2, close))
                    }
                    i = close + 2
                }
            }
            c == '*' -> {
                val close = nextStar[i + 1]
                if (close == n) {
                    append(c)
                    i++
                } else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(source.substring(i + 1, close))
                    }
                    i = close + 1
                }
            }
            else -> {
                append(c)
                i++
            }
        }
    }
}

/**
 * `table[k]` is the smallest `j >= k` with `at(j)` true, or `n` (one past the
 * end -- `source.indexOf`'s `-1`, in an index this array can hold) when there
 * is none. Built backwards in one pass, `n` down to `0`, so every entry is
 * either `k` itself or copied from `table[k + 1]` -- O(n) total, filled once
 * per [parseInline] call and read from thereafter.
 */
private inline fun nextMarker(n: Int, at: (Int) -> Boolean): IntArray {
    val table = IntArray(n + 1)
    table[n] = n
    for (k in n - 1 downTo 0) {
        table[k] = if (at(k)) k else table[k + 1]
    }
    return table
}

/**
 * Renders [text] as Markdown: paragraphs and bullets as native `Text`, a
 * fenced code block as a horizontally scrollable monospace block with a
 * copy button. `style` is the paragraph/bullet text style; headings are
 * always bold on top of it (see [parseMarkdown]).
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> Text(text = block.text, style = style)
                // `weight(1f)` on the body text, not just `fillMaxWidth()` on
                // the Row: without it the body is measured against the
                // Row's full width including the marker column, so a
                // wrapped line starts back at the Row's left edge instead of
                // hanging under the marker, and can overflow past it.
                // `Alignment.Top` keeps the marker glyph aligned with the
                // body's first line rather than the (taller, wrapped)
                // block's vertical center.
                is MdBlock.Bullet -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(text = "• ", style = style)
                    Text(text = block.text, style = style, modifier = Modifier.weight(1f))
                }
                is MdBlock.Code -> CodeBlock(block, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

/**
 * A fenced code block: horizontally scrollable (code lines are not wrapped
 * or truncated) monospace text on `surfaceContainerHighest`, with a copy
 * button that swaps to [FleetIcons.Check] for a beat after a tap --
 * [LocalClipboardManager] alone gives no other feedback that the tap
 * registered.
 */
@Composable
private fun CodeBlock(code: MdBlock.Code, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember(code) { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = code.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        )
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(code.text))
                copied = true
                scope.launch {
                    delay(1500)
                    copied = false
                }
            },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = if (copied) FleetIcons.Check else FleetIcons.Copy,
                contentDescription = if (copied) "Copied" else "Copy code",
            )
        }
    }
}
