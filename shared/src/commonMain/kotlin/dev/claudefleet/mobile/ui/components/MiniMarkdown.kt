package dev.claudefleet.mobile.ui.components

import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.claudefleet.mobile.ui.theme.FleetIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * A dependency-free Markdown renderer for hub-sourced assistant text
 * (`ConvItem.Text`), drawn with native Compose -- no WebView, no HTML.
 *
 * Route B of the Task 2 markdown work (2026-09-21): the alternative was
 * `com.mikepenz:multiplatform-markdown-renderer-m3`, which resolved and
 * compiled fine against Compose Multiplatform 1.12 / Kotlin 2.4.20, but grew
 * the iOS simulator debug framework by ~7.4 MB against a 2 MB gate --
 * abandoned for that reason, not for a compatibility failure. See the task
 * report for both measurements.
 *
 * It started as a tiny subset (a fence, a flat `- ` list, `#` demoted to
 * bold, three inline spans). Since 2026-09-28 it covers the practical
 * GitHub-Flavored Markdown a Claude Code transcript actually contains: ATX
 * and setext headings at distinct sizes, nested ordered / unordered / task
 * lists, blockquotes that hold other blocks, GFM tables with column
 * alignment, thematic breaks, ``` and ~~~ fences with a language label, and
 * inline bold / italic / strikethrough / code / links / autolinks / bare
 * URLs / backslash escapes. Still deliberately not a general CommonMark
 * implementation: no reference links, no raw HTML, no indented code blocks,
 * and emphasis pairs by a nearest-valid-closer rule rather than the full
 * delimiter-run algorithm.
 *
 * The text is untrusted (a transcript holds whatever a tool printed), so:
 * - Nesting is bounded by [MAX_DEPTH], both for block containers (quotes,
 *   list items) and for inline spans. Kotlin/Native reports a blown stack
 *   as `SIGBUS`, not a catchable error, so the bound is applied to the input
 *   before recursing, not caught afterwards. Past it, a `>` or a list marker
 *   is plain paragraph text and inline markers are literal.
 * - The inline scan stays linear per nesting level: every "where does this
 *   marker close" question is an O(1) read of a precomputed table (see
 *   [nextMarker]).
 * - Only `http:`, `https:` and `mailto:` links are live; any other scheme
 *   (`javascript:`, `file:`, `intent:`, a relative path) renders as its
 *   plain text. Images are never fetched: `![alt](src)` shows its alt text
 *   (a link to `src` when that is a safe URL).
 *
 * [parseMarkdown] is a pure function: no Compose runtime, no Composable in
 * its call graph, so it is unit-testable on its own (`MiniMarkdownTest.kt`)
 * without a rendering environment. [MarkdownText] is the Composable that
 * walks its output.
 */
sealed class MdBlock {
    data class Paragraph(val text: AnnotatedString) : MdBlock()

    /** An ATX (`#`..`######`) or setext heading; [level] is 1..6. */
    data class Heading(val level: Int, val text: AnnotatedString) : MdBlock()

    data class Code(val text: String, val lang: String?) : MdBlock()

    /** A bullet or ordered list; [start] is the first item's number (0 for a bullet list). */
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<MdListItem>) : MdBlock()

    data class Quote(val blocks: List<MdBlock>) : MdBlock()

    /**
     * A GFM table. Every row in [rows] has exactly `header.size` cells (a
     * short row is padded with empty cells and extra cells are dropped, as
     * GFM does), and [align] has one entry per column.
     */
    data class Table(
        val header: List<AnnotatedString>,
        val align: List<MdAlign>,
        val rows: List<List<AnnotatedString>>,
    ) : MdBlock()

    /** A thematic break: `---`, `***` or `___`. */
    data object Rule : MdBlock()
}

/** [checked] is null for a plain item, true / false for a `- [x]` / `- [ ]` task item. */
data class MdListItem(val blocks: List<MdBlock>, val checked: Boolean? = null)

enum class MdAlign { None, Start, Center, End }

/**
 * How deep block containers (a quote in a list in a quote ...) and inline
 * spans (bold in a link in a strike ...) may nest before the rest is taken
 * literally.
 */
internal const val MAX_DEPTH = 12

/**
 * The theme-dependent parts of inline spans. [parseMarkdown] without it uses
 * [Default] (no colours), which is what the unit tests see; [MarkdownText]
 * passes the Material colours.
 */
internal data class MdInlineStyles(
    val codeBackground: Color = Color.Unspecified,
    val linkColor: Color = Color.Unspecified,
    val mutedColor: Color = Color.Unspecified,
) {
    val code = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em, background = codeBackground)
    val link = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    val strike = SpanStyle(textDecoration = TextDecoration.LineThrough, color = mutedColor)

    companion object {
        val Default = MdInlineStyles()
    }
}

fun parseMarkdown(text: String): List<MdBlock> = parseMarkdown(text, MdInlineStyles.Default)

internal fun parseMarkdown(text: String, styles: MdInlineStyles): List<MdBlock> {
    // `\r\n` -> `\n`, then a lone `\r` (old Mac line endings, or a stray
    // carriage return) -> `\n` too, so every downstream line-based check
    // sees one newline convention regardless of what the hub sent, and no
    // `\r` ever ends up inside a rendered `Text`.
    val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    return BlockParser(styles).parse(normalized.split("\n"), 0)
}

// ---------------------------------------------------------------- blocks

/** Width of the leading whitespace, a tab advancing to the next multiple of 4. */
private fun indentOf(s: String): Int {
    var w = 0
    for (c in s) {
        when (c) {
            ' ' -> w++
            '\t' -> w += 4 - w % 4
            else -> return w
        }
    }
    return w
}

/** Removes up to [k] columns of leading whitespace (a tab straddling [k] leaves its remainder as spaces). */
private fun stripIndent(s: String, k: Int): String {
    var w = 0
    var idx = 0
    while (idx < s.length && w < k) {
        when (s[idx]) {
            ' ' -> {
                w++
                idx++
            }
            '\t' -> {
                val nw = w + 4 - w % 4
                if (nw > k) return " ".repeat(nw - k) + s.substring(idx + 1)
                w = nw
                idx++
            }
            else -> break
        }
    }
    return s.substring(idx)
}

private class Fence(val char: Char, val length: Int, val lang: String?)

/** A ``` or ~~~ opening fence on an already left-trimmed line, or null. */
private fun fenceOpen(t: String): Fence? {
    if (t.length < 3) return null
    val ch = t[0]
    if (ch != '`' && ch != '~') return null
    var k = 0
    while (k < t.length && t[k] == ch) k++
    if (k < 3) return null
    val info = t.substring(k).trim()
    // A backtick fence's info string cannot itself hold a backtick: that is
    // an inline code span such as ```` ```x``` ````, not a fence.
    if (ch == '`' && '`' in info) return null
    val lang = info.takeWhile { !it.isWhitespace() }.ifEmpty { null }
    return Fence(ch, k, lang)
}

private fun isFenceClose(t: String, f: Fence): Boolean {
    val body = t.trimEnd()
    return body.length >= f.length && body.all { it == f.char }
}

/** `---`, `***`, `___` (three or more of one, spaces allowed between). */
private fun isRule(t: String): Boolean {
    if (t.isEmpty()) return false
    val ch = t[0]
    if (ch != '-' && ch != '*' && ch != '_') return false
    var count = 0
    for (c in t) {
        when {
            c == ch -> count++
            c == ' ' || c == '\t' -> {}
            else -> return false
        }
    }
    return count >= 3
}

/** A setext underline under a paragraph (`===` -> 1, `---` -> 2), or 0. */
private fun setextLevel(t: String): Int {
    val body = t.trimEnd()
    if (body.isEmpty()) return 0
    return when {
        body.all { it == '=' } -> 1
        body.all { it == '-' } -> 2
        else -> 0
    }
}

private class Atx(val level: Int, val text: String)

private fun atxHeading(t: String): Atx? {
    var k = 0
    while (k < t.length && t[k] == '#') k++
    if (k == 0 || k > 6) return null
    if (k < t.length && t[k] != ' ' && t[k] != '\t') return null
    var body = t.substring(k).trim()
    // An optional closing sequence: `## Title ##` (but `## C#` keeps its `#`).
    val stripped = body.trimEnd('#')
    if (stripped.length < body.length && (stripped.isEmpty() || stripped.last() == ' ' || stripped.last() == '\t')) {
        body = stripped.trimEnd()
    }
    return Atx(k, body)
}

private class ListMarker(
    val indent: Int,
    val ordered: Boolean,
    val number: Int,
    val contentIndent: Int,
    val content: String,
)

private fun Char.isDigitAscii() = this in '0'..'9'

private fun listMarker(line: String): ListMarker? {
    val ind = indentOf(line)
    val t = stripIndent(line, ind)
    if (t.isEmpty() || isRule(t)) return null
    var k: Int
    val ordered: Boolean
    var number = 0
    if (t[0] == '-' || t[0] == '*' || t[0] == '+') {
        k = 1
        ordered = false
    } else {
        k = 0
        while (k < t.length && k < 9 && t[k].isDigitAscii()) k++
        if (k == 0 || k >= t.length || (t[k] != '.' && t[k] != ')')) return null
        number = t.substring(0, k).toInt()
        k++
        ordered = true
    }
    if (k < t.length && t[k] != ' ' && t[k] != '\t') return null
    val rest = t.substring(k)
    val blank = rest.isBlank()
    val sp = indentOf(rest)
    val pad = if (blank || sp > 4) 1 else sp
    val content = if (blank) "" else stripIndent(rest, pad)
    return ListMarker(ind, ordered, number, ind + k + pad, content)
}

/** Splits a table row on unescaped `|`, dropping one leading and trailing pipe; `\|` becomes `|`. */
private fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' && i + 1 < s.length && s[i + 1] == '|' -> {
                cur.append('|')
                i++
            }
            c == '|' -> {
                cells += cur.toString().trim()
                cur.clear()
            }
            else -> cur.append(c)
        }
        i++
    }
    cells += cur.toString().trim()
    return cells
}

/** The alignment row of a GFM table (`| :--- | :---: | ---: |`), or null. */
private fun delimiterRow(line: String): List<MdAlign>? {
    if ('|' !in line) return null
    val cells = splitRow(line)
    val out = ArrayList<MdAlign>(cells.size)
    for (cell in cells) {
        if (cell.isEmpty()) return null
        val left = cell.startsWith(':')
        val right = cell.length > 1 && cell.endsWith(':')
        val dashes = cell.substring(if (left) 1 else 0, cell.length - if (right) 1 else 0)
        if (dashes.isEmpty() || !dashes.all { it == '-' }) return null
        out += when {
            left && right -> MdAlign.Center
            left -> MdAlign.Start
            right -> MdAlign.End
            else -> MdAlign.None
        }
    }
    return out
}

private class BlockParser(private val styles: MdInlineStyles) {

    private fun inline(s: String) = InlineParser(s, styles).parse()

    /** A line that would open some other block, so it cannot be a lazy paragraph continuation. */
    private fun startsBlock(line: String): Boolean {
        val t = line.trimStart()
        return fenceOpen(t) != null || t.startsWith(">") || atxHeading(t) != null ||
            isRule(t) || listMarker(line) != null
    }

    fun parse(lines: List<String>, depth: Int): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val para = mutableListOf<String>()
        // Past MAX_DEPTH no container opens: `>` and list markers are text.
        val containers = depth < MAX_DEPTH

        fun flush() {
            if (para.isNotEmpty()) {
                out += MdBlock.Paragraph(inline(para.joinToString("\n")))
                para.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                flush()
                i++
                continue
            }
            val ind = indentOf(line)
            val t = line.trimStart()

            // Fenced code: its contents are never reinterpreted, only
            // carried verbatim (less the fence's own indentation).
            val fence = fenceOpen(t)
            if (fence != null) {
                flush()
                i++
                val code = mutableListOf<String>()
                while (i < lines.size) {
                    if (isFenceClose(lines[i].trimStart(), fence)) {
                        i++
                        break
                    }
                    code += stripIndent(lines[i], ind)
                    i++
                }
                // A fence with no closing marker (a streamed or truncated
                // transcript) runs to EOF rather than losing the rest.
                out += MdBlock.Code(code.joinToString("\n"), fence.lang)
                continue
            }

            if (containers && ind < 4 && t.startsWith(">")) {
                flush()
                val inner = mutableListOf<String>()
                while (i < lines.size) {
                    val l = lines[i]
                    val lt = l.trimStart()
                    if (indentOf(l) < 4 && lt.startsWith(">")) {
                        var s = lt.substring(1)
                        if (s.startsWith(" ") || s.startsWith("\t")) s = s.substring(1)
                        inner += s
                        i++
                    } else if (l.isNotBlank() && inner.isNotEmpty() && inner.last().isNotBlank() && !startsBlock(l)) {
                        inner += l // a lazy continuation of the quoted paragraph
                        i++
                    } else {
                        break
                    }
                }
                out += MdBlock.Quote(parse(inner, depth + 1))
                continue
            }

            val atx = atxHeading(t)
            if (atx != null) {
                flush()
                out += MdBlock.Heading(atx.level, inline(atx.text))
                i++
                continue
            }

            if (para.isNotEmpty() && ind < 4) {
                val level = setextLevel(t)
                if (level > 0) {
                    out += MdBlock.Heading(level, inline(para.joinToString("\n")))
                    para.clear()
                    i++
                    continue
                }
            }

            if (isRule(t)) {
                flush()
                out += MdBlock.Rule
                i++
                continue
            }

            val marker = if (containers) listMarker(line) else null
            // As in CommonMark, only a non-empty bullet item or an ordered
            // list starting at 1 may interrupt a running paragraph -- so
            // "until\n2021. It was" stays prose.
            if (marker != null &&
                (para.isEmpty() || (marker.content.isNotBlank() && (!marker.ordered || marker.number == 1)))
            ) {
                flush()
                i = parseList(lines, i, marker, depth, out)
                continue
            }

            if ('|' in line && i + 1 < lines.size) {
                val align = delimiterRow(lines[i + 1])
                if (align != null) {
                    val header = splitRow(line)
                    if (header.size == align.size) {
                        flush()
                        i = parseTable(lines, i, header, align, out)
                        continue
                    }
                }
            }

            para += t
            i++
        }
        flush()
        return out
    }

    private fun parseTable(
        lines: List<String>,
        start: Int,
        header: List<String>,
        align: List<MdAlign>,
        out: MutableList<MdBlock>,
    ): Int {
        val cols = header.size
        var i = start + 2
        val rows = mutableListOf<List<AnnotatedString>>()
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank() || '|' !in l || startsBlock(l)) break
            val cells = splitRow(l)
            rows += List(cols) { c -> inline(cells.getOrElse(c) { "" }) }
            i++
        }
        out += MdBlock.Table(header.map { inline(it) }, align, rows)
        return i
    }

    /** Parses the list whose first item is [first] (on line [start]); returns the index of the line after it. */
    private fun parseList(
        lines: List<String>,
        start: Int,
        first: ListMarker,
        depth: Int,
        out: MutableList<MdBlock>,
    ): Int {
        val items = mutableListOf<MdListItem>()
        var i = start
        var m: ListMarker? = first
        while (m != null) {
            val itemLines = mutableListOf(m.content)
            i++
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) {
                    itemLines += ""
                    i++
                    continue
                }
                val li = indentOf(l)
                if (li >= m.contentIndent) {
                    itemLines += stripIndent(l, m.contentIndent)
                    i++
                    continue
                }
                val lm = listMarker(l)
                // Lenient nesting: a marker indented past this item's own
                // marker is a child even when short of the content column
                // (`1. x` then `  - y`), which is how transcripts often indent.
                if (lm != null && li > m.indent) {
                    itemLines += stripIndent(l, li)
                    i++
                    continue
                }
                if (lm == null && itemLines.last().isNotBlank() && !startsBlock(l)) {
                    itemLines += l.trimStart() // a lazy paragraph continuation
                    i++
                    continue
                }
                break
            }
            while (itemLines.size > 1 && itemLines.last().isBlank()) itemLines.removeAt(itemLines.size - 1)

            var checked: Boolean? = null
            val head = itemLines[0]
            if (head.length >= 3 && head[0] == '[' && head[2] == ']' && head[1] in " xX" &&
                (head.length == 3 || head[3] == ' ' || head[3] == '\t')
            ) {
                checked = head[1] != ' '
                itemLines[0] = head.substring(min(4, head.length))
            }
            items += MdListItem(parse(itemLines, depth + 1), checked)

            // Blank lines consumed above sit between siblings, so a loose
            // list (items separated by a blank line) stays one list.
            m = if (i < lines.size) listMarker(lines[i])?.takeIf { it.ordered == first.ordered } else null
        }
        out += MdBlock.ListBlock(first.ordered, first.number, items)
        return i
    }
}

// ---------------------------------------------------------------- inline

private fun isAsciiPunct(c: Char) = c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

private fun safeUrl(url: String): Boolean {
    val lower = url.lowercase()
    return (lower.startsWith("https://") && lower.length > 8) ||
        (lower.startsWith("http://") && lower.length > 7) ||
        (lower.startsWith("mailto:") && lower.length > 7)
}

private val EMAIL = Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$")

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val BOLD_ITALIC = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)

/**
 * Inline spans over one block's text: `**bold**` / `__bold__`, `*italic*` /
 * `_italic_` (an `_` never opens or closes inside a word, so `snake_case`
 * stays literal), `***both***`, `` `code` `` and ``` ``co`de`` ```,
 * `~~strike~~`, `[text](url)`, `![alt](src)`, `<https://…>`, bare
 * `https://…` URLs and `\` escapes. Spans nest (bold inside a link inside a
 * strike) up to [MAX_DEPTH].
 *
 * An opening marker with no matching close is emitted as literal text
 * rather than silently dropped or left to swallow the rest of the line.
 *
 * Every "where is this marker's close" question is an O(1) read of a table
 * built once per block, in one backward pass over the whole string
 * ([nextMarker]); a nested span is scanned as a sub-range of the same string
 * against the same tables, a close past the range's end counting as none.
 * So each nesting level is one linear pass and the whole scan is
 * O(n * depth). `indexOf` from the current position was the shape a stray
 * marker made expensive: a review finding on a transcript heavy with
 * un-escaped `` ` ``/`*` (a diff, a shell one-liner) put the same tail of
 * the string under the microscope once per stray character, which is
 * quadratic in the number of them.
 */
private class InlineParser(private val src: String, private val styles: MdInlineStyles) {
    private val n = src.length

    /** `escaped[j]`: the character at j follows an escaping backslash. */
    private val escaped = BooleanArray(n + 1).also { esc ->
        var j = 0
        while (j < n) {
            if (src[j] == '\\' && j + 1 < n && isAsciiPunct(src[j + 1])) {
                esc[j + 1] = true
                j += 2
            } else {
                j++
            }
        }
    }

    private fun at(j: Int): Char = if (j in 0 until n) src[j] else ' '
    private fun spaceAt(j: Int) = at(j).isWhitespace()
    private fun wordAt(j: Int) = at(j).isLetterOrDigit()

    /** A run of exactly [len] backticks starts at [j]. */
    private fun tickRun(j: Int, len: Int): Boolean {
        if (src[j] != '`' || at(j - 1) == '`') return false
        for (k in 1 until len) if (at(j + k) != '`') return false
        return at(j + len) != '`'
    }

    // Code spans ignore escapes (a backslash is literal inside one), so
    // their closers do not consult `escaped`.
    private val tick1 = nextMarker(n) { j -> tickRun(j, 1) }
    private val tick2 = nextMarker(n) { j -> tickRun(j, 2) }

    private val star3Close = nextMarker(n) { j ->
        src[j] == '*' && at(j + 1) == '*' && at(j + 2) == '*' && !escaped[j] && j > 0 && !spaceAt(j - 1)
    }
    private val star2Close = nextMarker(n) { j ->
        src[j] == '*' && at(j + 1) == '*' && !escaped[j] && j > 0 && !spaceAt(j - 1)
    }
    // A single `*` closes a run of one, or starts a run of three or more
    // (`*italic***` inside bold) -- never the first half of a `**`.
    private val star1Close = nextMarker(n) { j ->
        src[j] == '*' && !escaped[j] && j > 0 && !spaceAt(j - 1) && at(j - 1) != '*' &&
            (at(j + 1) != '*' || at(j + 2) == '*')
    }
    private val star1Open = nextMarker(n) { j ->
        src[j] == '*' && !escaped[j] && at(j - 1) != '*' && at(j + 1) != '*' && !spaceAt(j + 1)
    }
    private val star2Open = nextMarker(n) { j ->
        src[j] == '*' && at(j + 1) == '*' && !escaped[j] && at(j - 1) != '*' && at(j + 2) != '*' && !spaceAt(j + 2)
    }
    private val under2Close = nextMarker(n) { j ->
        src[j] == '_' && at(j + 1) == '_' && !escaped[j] && j > 0 && !spaceAt(j - 1) && !wordAt(j + 2)
    }
    private val under1Close = nextMarker(n) { j ->
        src[j] == '_' && !escaped[j] && j > 0 && !spaceAt(j - 1) && at(j - 1) != '_' &&
            at(j + 1) != '_' && !wordAt(j + 1)
    }
    private val tilde2Close = nextMarker(n) { j ->
        src[j] == '~' && at(j + 1) == '~' && !escaped[j] && j > 0 && !spaceAt(j - 1)
    }
    private val closeBracket = nextMarker(n) { j -> src[j] == ']' && !escaped[j] }
    private val closeParen = nextMarker(n) { j -> src[j] == ')' && !escaped[j] }
    private val nextGt = nextMarker(n) { j -> src[j] == '>' }
    private val nextLt = nextMarker(n) { j -> src[j] == '<' }
    private val nextSpace = nextMarker(n) { j -> src[j].isWhitespace() }

    fun parse(): AnnotatedString = buildAnnotatedString { scan(this, 0, n, 0, links = true) }

    /** A [len]-char close found at [close] counts only if it ends inside the range ending at [to]. */
    private fun within(close: Int, len: Int, to: Int) = close < n && close + len <= to

    private fun scan(b: AnnotatedString.Builder, from: Int, to: Int, depth: Int, links: Boolean) {
        if (depth >= MAX_DEPTH) {
            b.append(src, from, to)
            return
        }
        var i = from
        while (i < to) {
            val c = src[i]
            // Each helper returns the index just past what it consumed, or
            // null when [i] opens nothing and [c] is literal.
            val next: Int? = if (escaped[i]) {
                null
            } else {
                when (c) {
                    // `\` + punctuation: drop the backslash (the next char is
                    // `escaped`, so literal). `\` at a line end is a hard
                    // break, which the kept newline already is.
                    '\\' -> if (i + 1 < to && (escaped[i + 1] || src[i + 1] == '\n')) i + 1 else null
                    '`' -> codeSpan(b, i, to)
                    '*', '_' -> emphasis(b, i, to, depth, links, c)
                    '~' -> strike(b, i, to, depth, links)
                    '[' -> if (links) link(b, i, to, depth, image = false) else null
                    '!' -> if (links && i + 1 < to && src[i + 1] == '[') link(b, i + 1, to, depth, image = true) else null
                    '<' -> if (links) autolink(b, i, to) else null
                    'h', 'H' -> if (links) bareUrl(b, i, to, from) else null
                    else -> null
                }
            }
            if (next != null) {
                i = next
            } else {
                b.append(c)
                i++
            }
        }
    }

    private fun span(b: AnnotatedString.Builder, style: SpanStyle, from: Int, to: Int, depth: Int, links: Boolean) {
        b.pushStyle(style)
        scan(b, from, to, depth + 1, links)
        b.pop()
    }

    private fun codeSpan(b: AnnotatedString.Builder, i: Int, to: Int): Int {
        var len = 0
        while (i + len < to && src[i + len] == '`') len++
        val table = when (len) {
            1 -> tick1
            2 -> tick2
            else -> null
        }
        val close = table?.get(i + len) ?: n
        if (!within(close, len, to)) {
            b.append(src, i, i + len)
            return i + len
        }
        var code = src.substring(i + len, close).replace('\n', ' ')
        if (code.length >= 2 && code.startsWith(" ") && code.endsWith(" ") && code.isNotBlank()) {
            code = code.substring(1, code.length - 1)
        }
        b.pushStyle(styles.code)
        b.append(code)
        b.pop()
        return close + len
    }

    /** Exactly `***` starts at [j] and ends inside the range ending at [to]. */
    private fun tripleStarAt(j: Int, to: Int) =
        j + 3 <= to && at(j - 1) != '*' && at(j) == '*' && at(j + 1) == '*' && at(j + 2) == '*' && at(j + 3) != '*'

    private fun emphasis(b: AnnotatedString.Builder, i: Int, to: Int, depth: Int, links: Boolean, ch: Char): Int? {
        val star = ch == '*'
        // `_` never opens inside a word: `snake_case_name` stays literal.
        if (!star && wordAt(i - 1)) return null
        if (star && at(i + 1) == '*' && at(i + 2) == '*' && i + 3 < to && !spaceAt(i + 3)) {
            val close = star3Close[i + 3]
            // An empty `******` falls through to the `**` pairing below, so
            // a long run of `*` still pairs up two by two.
            if (within(close, 3, to) && close > i + 3) {
                span(b, BOLD_ITALIC, i + 3, close, depth, links)
                return close + 3
            }
        }
        if (i + 1 < to && at(i + 1) == ch) {
            if (i + 2 < to && !spaceAt(i + 2)) {
                var close = (if (star) star2Close else under2Close)[i + 2]
                // `**bold *italic***`: the closing `***` gives its last two
                // stars to the bold when an italic opened inside it.
                if (star && tripleStarAt(close, to) && star1Open[i + 2] < close) close++
                if (within(close, 2, to)) {
                    span(b, BOLD, i + 2, close, depth, links)
                    return close + 2
                }
            }
            b.append(ch).append(ch)
            return i + 2
        }
        if (i + 1 < to && !spaceAt(i + 1)) {
            var close = (if (star) star1Close else under1Close)[i + 1]
            // `*italic **bold***`: the mirror image -- the italic takes the last star.
            if (star && tripleStarAt(close, to) && star2Open[i + 1] < close) close += 2
            if (within(close, 1, to)) {
                span(b, ITALIC, i + 1, close, depth, links)
                return close + 1
            }
        }
        return null
    }

    private fun strike(b: AnnotatedString.Builder, i: Int, to: Int, depth: Int, links: Boolean): Int? {
        if (at(i + 1) != '~' || i + 2 >= to || spaceAt(i + 2)) return null
        val close = tilde2Close[i + 2]
        if (!within(close, 2, to)) return null
        span(b, styles.strike, i + 2, close, depth, links)
        return close + 2
    }

    /** `[text](url)`, or with [image] the `[alt](src)` of `![alt](src)`, opening at [i]. */
    private fun link(b: AnnotatedString.Builder, i: Int, to: Int, depth: Int, image: Boolean): Int? {
        val close = closeBracket[i + 1]
        if (!within(close, 1, to) || close + 1 >= to || src[close + 1] != '(') return null
        var paren = closeParen[close + 2]
        if (!within(paren, 1, to)) return null
        // One level of balanced parentheses inside the URL (Wikipedia-style).
        var open = 0
        for (k in close + 2 until paren) {
            if (src[k] == '(') open++ else if (src[k] == ')') open--
        }
        while (open > 0 && paren + 1 < to && src[paren + 1] == ')') {
            paren++
            open--
        }
        var dest = src.substring(close + 2, paren).trim()
        dest = if (dest.startsWith("<") && '>' in dest) {
            dest.substring(1, dest.indexOf('>'))
        } else {
            dest.takeWhile { !it.isWhitespace() } // drops an optional "title"
        }
        val live = safeUrl(dest)
        if (live) b.pushLink(LinkAnnotation.Url(dest, styles.link))
        if (image) b.pushStyle(ITALIC)
        scan(b, i + 1, close, depth + 1, links = false)
        if (image) b.pop()
        if (live) b.pop()
        return paren + 1
    }

    /** `<https://…>`, `<mailto:…>` or `<a@b.c>` opening at [i]. */
    private fun autolink(b: AnnotatedString.Builder, i: Int, to: Int): Int? {
        val close = nextGt[i + 1]
        if (!within(close, 1, to) || close == i + 1) return null
        if (nextSpace[i + 1] < close || nextLt[i + 1] < close) return null
        val body = src.substring(i + 1, close)
        val url = when {
            safeUrl(body) -> body
            ':' !in body && body.length <= 254 && EMAIL.matches(body) -> "mailto:$body"
            else -> return null
        }
        b.pushLink(LinkAnnotation.Url(url, styles.link))
        b.append(body)
        b.pop()
        return close + 1
    }

    /** A bare `http://` / `https://` URL starting at [i] on a word boundary. */
    private fun bareUrl(b: AnnotatedString.Builder, i: Int, to: Int, from: Int): Int? {
        if (i > from && wordAt(i - 1)) return null
        val scheme = when {
            src.regionMatches(i, "https://", 0, 8, ignoreCase = true) -> 8
            src.regionMatches(i, "http://", 0, 7, ignoreCase = true) -> 7
            else -> return null
        }
        var end = min(min(nextSpace[i], nextLt[i]), to)
        var balance = 0
        for (k in i until end) {
            if (src[k] == '(') balance++ else if (src[k] == ')') balance--
        }
        // Trailing punctuation belongs to the sentence, not the URL; a `)`
        // stays only while it balances a `(` inside the URL.
        while (end > i + scheme) {
            val last = src[end - 1]
            if (last in ".,:;!?\"'*_~") {
                end--
            } else if (last == ')' && balance < 0) {
                end--
                balance++
            } else {
                break
            }
        }
        if (end <= i + scheme) return null
        val url = src.substring(i, end)
        b.pushLink(LinkAnnotation.Url(url, styles.link))
        b.append(url)
        b.pop()
        return end
    }
}

/**
 * `table[k]` is the smallest `j >= k` with `at(j)` true, or `n` (one past the
 * end -- `source.indexOf`'s `-1`, in an index this array can hold) when there
 * is none. Built backwards in one pass, `n` down to `0`, so every entry is
 * either `k` itself or copied from `table[k + 1]` -- O(n) total, filled once
 * per block and read from thereafter.
 */
private inline fun nextMarker(n: Int, at: (Int) -> Boolean): IntArray {
    val table = IntArray(n + 1)
    table[n] = n
    for (k in n - 1 downTo 0) {
        table[k] = if (at(k)) k else table[k + 1]
    }
    return table
}

// ---------------------------------------------------------------- rendering

private val BLOCK_GAP = 8.dp
private val ITEM_GAP = 4.dp
private val MARKER_COLUMN = 20.dp
private val MARKER_GAP = 6.dp

/** List levels past this keep this level's indent, so a deep list does not squeeze its text. */
private const val MAX_VISUAL_LIST_LEVEL = 3

/**
 * Renders [text] as Markdown with native Compose: paragraphs as `Text` with
 * spans, headings at distinct Material sizes, lists with a hanging indent,
 * quotes with a side bar, tables as a horizontally scrollable grid, and
 * fenced code as a scrollable monospace block with a copy button. `style`
 * is the body text style; headings, table headers and code use their own.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val colors = MaterialTheme.colorScheme
    val styles = remember(colors.surfaceContainerHighest, colors.primary, colors.onSurfaceVariant) {
        MdInlineStyles(
            codeBackground = colors.surfaceContainerHighest,
            linkColor = colors.primary,
            mutedColor = colors.onSurfaceVariant,
        )
    }
    val blocks = remember(text, styles) { parseMarkdown(text, styles) }
    Blocks(blocks, style, BLOCK_GAP, listLevel = 0, modifier = modifier)
}

@Composable
private fun Blocks(blocks: List<MdBlock>, style: TextStyle, gap: Dp, listLevel: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MdBlock.Paragraph -> Text(text = block.text, style = style)
                is MdBlock.Heading -> HeadingView(block, first = index == 0)
                is MdBlock.Code -> CodeBlock(block)
                is MdBlock.ListBlock -> ListView(block, style, listLevel)
                is MdBlock.Quote -> QuoteView(block, style, listLevel)
                is MdBlock.Table -> TableView(block, style)
                MdBlock.Rule -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun HeadingView(block: MdBlock.Heading, first: Boolean) {
    val type = MaterialTheme.typography
    val base = when (block.level) {
        1 -> type.titleLarge
        2 -> type.titleMedium
        3 -> type.titleSmall
        else -> type.labelLarge
    }
    val color = if (block.level >= 4) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
    // BLOCK_GAP below; above, more, so a heading reads as the start of a
    // section rather than the end of the previous one.
    val above = when {
        first -> 0.dp
        block.level <= 2 -> 12.dp
        else -> 8.dp
    }
    Text(
        text = block.text,
        style = base.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        modifier = Modifier.padding(top = above),
    )
}

@Composable
private fun lineHeightOf(style: TextStyle): Dp = with(LocalDensity.current) {
    when {
        style.lineHeight.isSp -> style.lineHeight.toDp()
        style.fontSize.isSp && style.lineHeight.isEm -> (style.fontSize * style.lineHeight.value).toDp()
        style.fontSize.isSp -> (style.fontSize * 1.4f).toDp()
        else -> 20.dp
    }
}

@Composable
private fun ListView(block: MdBlock.ListBlock, style: TextStyle, level: Int) {
    val lineHeight = lineHeightOf(style)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val markerWidth = if (block.ordered) {
        // Wide enough for the widest number, right-aligned, so `9.` and
        // `10.` end on the same column.
        val widest = "${block.start + block.items.size - 1}."
        val px = remember(widest, style) { measurer.measure(widest, style).size.width }
        max(MARKER_COLUMN.value, with(density) { px.toDp().value }).dp
    } else {
        MARKER_COLUMN
    }
    // Past MAX_VISUAL_LIST_LEVEL the list is pulled back by one indent step,
    // lining up with its parent's markers instead of indenting further (the
    // parent item's marker column is empty below its first line).
    val pullBack = if (level >= MAX_VISUAL_LIST_LEVEL) MARKER_COLUMN + MARKER_GAP else 0.dp
    Column(
        modifier = Modifier.fillMaxWidth().pullLeft(pullBack),
        verticalArrangement = Arrangement.spacedBy(ITEM_GAP),
    ) {
        block.items.forEachIndexed { idx, item ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                // The marker box is one body line tall, so the glyph centres
                // on the item's first line however far the body wraps.
                Box(
                    modifier = Modifier.width(markerWidth).heightIn(min = lineHeight),
                    contentAlignment = if (block.ordered && item.checked == null) Alignment.CenterEnd else Alignment.Center,
                ) {
                    when {
                        item.checked != null -> TaskBox(item.checked)
                        block.ordered -> Text(text = "${block.start + idx}.", style = style, color = muted)
                        else -> BulletGlyph(level, muted)
                    }
                }
                Spacer(Modifier.width(MARKER_GAP))
                // `weight(1f)`: the body is measured against what is left
                // after the marker column, so a wrapped line hangs under the
                // body's first character rather than under the marker.
                val bodyModifier = Modifier.weight(1f)
                if (item.checked == true) {
                    CompositionLocalProvider(LocalContentColor provides muted) {
                        Blocks(item.blocks, style.copy(color = Color.Unspecified), ITEM_GAP, level + 1, bodyModifier)
                    }
                } else {
                    Blocks(item.blocks, style, ITEM_GAP, level + 1, bodyModifier)
                }
            }
        }
    }
}

/** Shifts the content [by] toward the start while keeping its end edge where it was. */
private fun Modifier.pullLeft(by: Dp): Modifier = if (by == 0.dp) {
    this
} else {
    layout { measurable, constraints ->
        val shift = by.roundToPx()
        val wider = if (constraints.hasBoundedWidth) {
            constraints.copy(minWidth = constraints.minWidth + shift, maxWidth = constraints.maxWidth + shift)
        } else {
            constraints
        }
        val placeable = measurable.measure(wider)
        val w = (placeable.width - shift).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(w, placeable.height) { placeable.place(-shift, 0) }
    }
}

/** • ◦ ▪ by nesting level, drawn rather than typeset so no font can render them as tofu. */
@Composable
private fun BulletGlyph(level: Int, color: Color) {
    when (level % 3) {
        0 -> Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        1 -> Box(Modifier.size(6.dp).border(1.2.dp, color, CircleShape))
        else -> Box(Modifier.size(5.dp).background(color))
    }
}

/** A read-only checkbox glyph for a task item: not clickable, the transcript is not editable. */
@Composable
private fun TaskBox(checked: Boolean) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    if (checked) {
        Box(
            modifier = Modifier.size(18.dp).padding(1.dp).clip(shape).background(colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = FleetIcons.Check,
                contentDescription = "Done",
                tint = colors.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    } else {
        Box(Modifier.size(18.dp).padding(1.dp).border(1.5.dp, colors.onSurfaceVariant, shape))
    }
}

@Composable
private fun QuoteView(block: MdBlock.Quote, style: TextStyle, listLevel: Int) {
    val bar = MaterialTheme.colorScheme.outlineVariant
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
        Blocks(
            blocks = block.blocks,
            style = style.copy(color = Color.Unspecified),
            gap = BLOCK_GAP,
            listLevel = listLevel,
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRoundRect(
                        color = bar,
                        size = Size(3.dp.toPx(), size.height),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
                .padding(start = 15.dp),
        )
    }
}

/** Row boundaries from the last measure pass, read by the draw pass that follows it. */
private class TableGrid {
    var rowBottoms: IntArray = IntArray(0)
}

/**
 * A GFM table as a grid: each column as wide as its widest cell, clamped to
 * 64..240dp (a longer cell wraps inside its column), the header row on
 * `surfaceContainerHigh`, 1dp row dividers, all inside a rounded card that
 * scrolls horizontally on its own when the table is wider than the screen.
 * A custom `Layout` rather than `SubcomposeLayout`: it only needs each
 * cell's intrinsic width, and it stays safe under a parent's intrinsic
 * measurement, which `SubcomposeLayout` does not support.
 */
@Composable
private fun TableView(table: MdBlock.Table, style: TextStyle) {
    val cols = table.header.size
    if (cols == 0) return
    val colors = MaterialTheme.colorScheme
    val grid = remember(table) { TableGrid() }
    val headerStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val shape = RoundedCornerShape(12.dp)
    val divider = colors.outlineVariant
    val headerBg = colors.surfaceContainerHigh
    Box(
        modifier = Modifier
            .clip(shape)
            .background(colors.surfaceContainerLow)
            .border(1.dp, divider, shape),
    ) {
        Layout(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .drawWithContent {
                    val rows = grid.rowBottoms
                    if (rows.isNotEmpty()) drawRect(headerBg, size = Size(size.width, rows[0].toFloat()))
                    drawContent()
                    val t = 1.dp.toPx()
                    for (r in 0 until rows.size - 1) {
                        drawRect(divider, topLeft = Offset(0f, rows[r].toFloat()), size = Size(size.width, t))
                    }
                },
            content = {
                for (r in -1 until table.rows.size) {
                    for (c in 0 until cols) {
                        val cell = if (r < 0) table.header[c] else table.rows[r].getOrNull(c) ?: AnnotatedString("")
                        val align = table.align.getOrNull(c) ?: MdAlign.None
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = when (align) {
                                MdAlign.Center -> Alignment.TopCenter
                                MdAlign.End -> Alignment.TopEnd
                                else -> Alignment.TopStart
                            },
                        ) {
                            Text(
                                text = cell,
                                style = if (r < 0) headerStyle else style,
                                textAlign = when (align) {
                                    MdAlign.Center -> TextAlign.Center
                                    MdAlign.End -> TextAlign.End
                                    else -> TextAlign.Start
                                },
                            )
                        }
                    }
                }
            },
        ) { measurables, _ ->
            val rowCount = measurables.size / cols
            val minW = 64.dp.roundToPx()
            val maxW = 240.dp.roundToPx()
            val divPx = max(1, 1.dp.roundToPx())
            val widths = IntArray(cols) { minW }
            for (idx in 0 until rowCount * cols) {
                val w = measurables[idx].maxIntrinsicWidth(Constraints.Infinity).coerceIn(minW, maxW)
                widths[idx % cols] = max(widths[idx % cols], w)
            }
            val placeables = List(rowCount * cols) { idx ->
                measurables[idx].measure(Constraints.fixedWidth(widths[idx % cols]))
            }
            val heights = IntArray(rowCount)
            for (idx in placeables.indices) {
                heights[idx / cols] = max(heights[idx / cols], placeables[idx].height)
            }
            val bottoms = IntArray(rowCount)
            var y = 0
            for (r in 0 until rowCount) {
                y += heights[r]
                bottoms[r] = y
                if (r < rowCount - 1) y += divPx
            }
            grid.rowBottoms = bottoms
            layout(widths.sum(), y) {
                var top = 0
                for (r in 0 until rowCount) {
                    var x = 0
                    for (c in 0 until cols) {
                        placeables[r * cols + c].place(x, top)
                        x += widths[c]
                    }
                    top += heights[r] + divPx
                }
            }
        }
    }
}

/**
 * A fenced code block on `surfaceContainerHigh`: a header strip with the
 * language label and a copy button (never drawn over the code), then the
 * code, horizontally scrollable (lines are not wrapped or truncated) until
 * the wrap toggle beside copy wraps them instead — on a phone a long line is
 * otherwise read a screen-width at a time, sideways. The copy
 * button swaps to [FleetIcons.Check] for a beat after a tap --
 * [LocalClipboardManager] alone gives no other feedback that the tap
 * registered.
 */
@Composable
private fun CodeBlock(code: MdBlock.Code, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember(code) { mutableStateOf(false) }
    var wrap by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = code.lang.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            IconButton(onClick = { wrap = !wrap }, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = FleetIcons.WrapText,
                    contentDescription = if (wrap) "Scroll long lines" else "Wrap long lines",
                    tint = if (wrap) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code.text))
                    copied = true
                    scope.launch {
                        delay(1500)
                        copied = false
                    }
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = if (copied) FleetIcons.Check else FleetIcons.Copy,
                    contentDescription = if (copied) "Copied" else "Copy code",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // A long block shows its head and a way to the rest: a 150-line dump
        // drawn whole put the sentence after it many flings away.
        val lines = remember(code) { code.text.lines() }
        var all by remember(code) { mutableStateOf(false) }
        val folded = !all && lines.size > CODE_FOLD_LINES
        Text(
            text = if (folded) lines.take(CODE_FOLD_LINES).joinToString("\n") else code.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            softWrap = wrap,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                .padding(start = 12.dp, end = 12.dp, bottom = if (lines.size > CODE_FOLD_LINES) 0.dp else 12.dp),
        )
        if (lines.size > CODE_FOLD_LINES) {
            TextButton(onClick = { all = !all }, modifier = Modifier.padding(start = 4.dp)) {
                Text(if (all) "Show less" else "Show all ${lines.size} lines")
            }
        }
    }
}

/** A code block longer than this is folded to its first lines. */
internal const val CODE_FOLD_LINES = 20
