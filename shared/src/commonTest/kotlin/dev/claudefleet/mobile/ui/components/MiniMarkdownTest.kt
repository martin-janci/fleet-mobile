package dev.claudefleet.mobile.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [parseMarkdown] is a pure function -- no Composable in the call graph -- so
 * it is tested directly rather than through a rendered tree. See
 * `MarkdownText` in `MiniMarkdown.kt` for the Composable that walks its
 * output.
 */
class MiniMarkdownTest {
    @Test
    fun fenced_code_is_split_out_with_its_language() {
        val blocks = parseMarkdown("before\n```kotlin\nval x = 1\nval y = 2\n```\nafter")

        assertEquals(3, blocks.size)
        val before = assertIs<MdBlock.Paragraph>(blocks[0])
        assertEquals("before", before.text.text)
        val code = assertIs<MdBlock.Code>(blocks[1])
        assertEquals("val x = 1\nval y = 2", code.text)
        assertEquals("kotlin", code.lang)
        val after = assertIs<MdBlock.Paragraph>(blocks[2])
        assertEquals("after", after.text.text)
    }

    @Test
    fun a_fence_with_no_language_tag_has_a_null_lang() {
        val blocks = parseMarkdown("```\nplain\n```")

        val code = assertIs<MdBlock.Code>(blocks.single())
        assertEquals("plain", code.text)
        assertEquals(null, code.lang)
    }

    /**
     * A streamed/truncated transcript can end mid-code-block, with no
     * closing ` ``` ` at all. That must not swallow nothing (an empty code
     * block, the rest of the message lost) or throw -- everything after the
     * opening fence becomes the code block's text, ending at EOF.
     */
    @Test
    fun a_fence_without_a_closing_marker_runs_to_the_end() {
        val blocks = parseMarkdown("before\n```kotlin\nval x = 1\nval y = 2")

        assertEquals(2, blocks.size)
        val before = assertIs<MdBlock.Paragraph>(blocks[0])
        assertEquals("before", before.text.text)
        val code = assertIs<MdBlock.Code>(blocks[1])
        assertEquals("val x = 1\nval y = 2", code.text)
        assertEquals("kotlin", code.lang)
    }

    @Test
    fun markdown_inside_a_fence_is_left_alone() {
        // `- not a bullet` and `**not bold**` inside the fence must survive
        // as literal code text -- the fence is split BEFORE line-level
        // markdown (bullets, headings) is considered at all.
        val blocks = parseMarkdown("```\n- not a bullet\n**not bold**\n```")

        val code = assertIs<MdBlock.Code>(blocks.single())
        assertEquals("- not a bullet\n**not bold**", code.text)
    }

    @Test
    fun dash_star_and_plus_prefixed_lines_become_one_bullet_list() {
        val blocks = parseMarkdown("- one\n* two\n+ three")

        val list = assertIs<MdBlock.ListBlock>(blocks.single())
        assertFalse(list.ordered)
        assertEquals(listOf("one", "two", "three"), list.items.map { it.paragraphText() })
    }

    /**
     * The parser hands one logical bullet to the renderer regardless of its
     * length; wrapping under the marker (see `ListView`'s `Row` in
     * `MiniMarkdown.kt`, which needs `Modifier.weight(1f)` on the body
     * `Text` for this) is a layout concern, not a parsing one. This just
     * pins the parser side of that: a single, very long line prefixed with
     * `- ` stays exactly one list item, not split by its own length.
     */
    @Test
    fun a_long_bullet_stays_a_single_block() {
        val long = "word ".repeat(50).trim() // 249 chars, no newline
        assertTrue(long.length > 200)

        val blocks = parseMarkdown("- $long")

        val list = assertIs<MdBlock.ListBlock>(blocks.single())
        assertEquals(long, list.items.single().paragraphText())
    }

    @Test
    fun bold_italic_and_inline_code_become_spans_on_one_paragraph() {
        val blocks = parseMarkdown("**bold** and *italic* and `code`")

        val paragraph = assertIs<MdBlock.Paragraph>(blocks.single()).text
        assertEquals("bold and italic and code", paragraph.text)

        val bold = paragraph.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        assertEquals("bold", paragraph.text.substring(bold.start, bold.end))

        val italic = paragraph.spanStyles.first { it.item.fontStyle == FontStyle.Italic }
        assertEquals("italic", paragraph.text.substring(italic.start, italic.end))

        val code = paragraph.spanStyles.first { it.item.fontFamily == FontFamily.Monospace }
        assertEquals("code", paragraph.text.substring(code.start, code.end))
    }

    @Test
    fun atx_headings_keep_their_level_one_to_six() {
        val blocks = parseMarkdown((1..6).joinToString("\n") { "#".repeat(it) + " H$it" })

        assertEquals(6, blocks.size)
        blocks.forEachIndexed { idx, block ->
            val heading = assertIs<MdBlock.Heading>(block)
            assertEquals(idx + 1, heading.level)
            assertEquals("H${idx + 1}", heading.text.text)
        }
    }

    @Test
    fun a_heading_drops_its_closing_hashes_but_not_a_hash_in_a_word() {
        assertEquals("Title", assertIs<MdBlock.Heading>(parseMarkdown("## Title ##").single()).text.text)
        assertEquals("C#", assertIs<MdBlock.Heading>(parseMarkdown("## C#").single()).text.text)
        // No space after the hashes: a hashtag, not a heading.
        assertIs<MdBlock.Paragraph>(parseMarkdown("#hashtag").single())
        // Seven hashes is not a heading either.
        assertIs<MdBlock.Paragraph>(parseMarkdown("####### seven").single())
    }

    @Test
    fun setext_underlines_make_level_one_and_two_headings() {
        val blocks = parseMarkdown("Big\n===\n\nSmall\n---")

        val big = assertIs<MdBlock.Heading>(blocks[0])
        assertEquals(1, big.level)
        assertEquals("Big", big.text.text)
        val small = assertIs<MdBlock.Heading>(blocks[1])
        assertEquals(2, small.level)
    }

    @Test
    fun a_bold_marker_that_never_closes_stays_literal() {
        val blocks = parseMarkdown("a **b without a close")

        val paragraph = assertIs<MdBlock.Paragraph>(blocks.single()).text
        assertEquals("a **b without a close", paragraph.text)
        assertTrue(paragraph.spanStyles.none { it.item.fontWeight == FontWeight.Bold })
    }

    /** The single-backtick counterpart to the never-closing `**` case above. */
    @Test
    fun a_stray_single_backtick_stays_literal() {
        val blocks = parseMarkdown("a `b without a close")

        val paragraph = assertIs<MdBlock.Paragraph>(blocks.single()).text
        assertEquals("a `b without a close", paragraph.text)
        assertTrue(paragraph.spanStyles.none { it.item.fontFamily == FontFamily.Monospace })
    }

    /** And the single-`*` (italic) counterpart. */
    @Test
    fun a_stray_single_star_stays_literal() {
        val blocks = parseMarkdown("a *b without a close")

        val paragraph = assertIs<MdBlock.Paragraph>(blocks.single()).text
        assertEquals("a *b without a close", paragraph.text)
        assertTrue(paragraph.spanStyles.none { it.item.fontStyle == FontStyle.Italic })
    }

    /**
     * Review finding: `parseInline` searched for each marker's close with
     * `source.indexOf(marker, i)`, walking the rest of the string from the
     * current position every time. A transcript heavy with un-escaped `*`
     * (a bullet-like diff, a glob pattern) put the same tail of the string
     * under the microscope once per marker, which is quadratic in how many
     * there are. The fix precomputes, in one forward pass, where the next
     * occurrence of each marker sits, so every lookup in the scan itself is
     * O(1). This does not assert on wall-clock time -- that is flaky across
     * machines and CI runners -- the test itself timing out (or the suite
     * hanging) is what the old, quadratic scan would have done here; the
     * point is that it returns at all, promptly.
     *
     * A run of `*` this long finds no closer that is not immediately adjacent,
     * and an EMPTY span is not a span, so every marker falls through to the
     * literal branch and the text is its own 5000 stars. Genuinely stray
     * behaviour is what the two single-occurrence tests above pin; this one
     * pins the large-input performance the fix exists for, and that a run of
     * markers survives as itself rather than being consumed as empty spans and
     * disappearing — which is what it used to do.
     */
    @Test
    fun five_thousand_adjacent_stars_complete_quickly_and_deterministically() {
        // A leading `x` keeps this on the inline path: a line of nothing but
        // `*` is a GFM thematic break (pinned at the end of this test).
        val input = "x" + "*".repeat(5000)

        val blocks = parseMarkdown(input)

        val paragraph = assertIs<MdBlock.Paragraph>(blocks.single())
        // Every `*` is its own character. An EMPTY span is not a span, so no
        // pair of markers is consumed and the run renders as itself, which is
        // what GFM does — it used to disappear entirely, "x" and nothing else,
        // and that is how any `****` in a transcript was swallowed.
        assertEquals(input, paragraph.text.text)

        assertEquals(MdBlock.Rule, parseMarkdown("*".repeat(5000)).single())
    }

    /**
     * An inline run of four or more markers is its own text. `****` closed an
     * empty bold span at `i + 2`, so the markers were consumed and vanished
     * from the rendered line — a transcript's `****` or `~~~~` simply gone.
     */
    @Test
    fun an_empty_span_is_not_a_span() {
        for (run in listOf("****", "______", "~~~~", "********")) {
            val p = assertIs<MdBlock.Paragraph>(parseMarkdown("x$run y").single())
            assertEquals("x$run y", p.text.text, run)
        }
        // A span with something in it still pairs.
        assertEquals("bold", assertIs<MdBlock.Paragraph>(parseMarkdown("**bold**").single()).text.text)
        assertEquals("gone", assertIs<MdBlock.Paragraph>(parseMarkdown("~~gone~~").single()).text.text)
    }

    /**
     * A table costs `columns × rows` and is bought with `columns + rows`
     * characters, so a few kilobytes of pipes used to buy millions of parsed
     * cells — composed and measured in a non-lazy layout on the main thread.
     * Past either bound the lines are a paragraph, exactly as a table whose
     * delimiter row does not match its header already is.
     */
    @Test
    fun a_table_is_bounded_in_both_dimensions() {
        val wide = "|".repeat(TABLE_MAX_COLS + 3) + "\n" + "|" + "-|".repeat(TABLE_MAX_COLS + 2) + "\n|x|"
        assertTrue(
            parseMarkdown(wide).none { it is MdBlock.Table },
            "a header past TABLE_MAX_COLS falls through to text",
        )

        val cols = 8
        val tall = buildString {
            append("|".repeat(cols + 1)).append('\n')
            append("|").append("-|".repeat(cols)).append('\n')
            repeat(4000) { append("|x|\n") }
        }
        val table = parseMarkdown(tall).filterIsInstance<MdBlock.Table>().single()
        assertEquals(cols, table.header.size)
        assertTrue(table.rows.size * cols <= TABLE_MAX_CELLS, "cells: ${table.rows.size * cols}")
        assertTrue(table.rows.isNotEmpty(), "what fits is still drawn")

        // An ordinary table is untouched.
        val ok = parseMarkdown("| a | b |\n| --- | --- |\n| 1 | 2 |").filterIsInstance<MdBlock.Table>().single()
        assertEquals(2, ok.header.size)
        assertEquals(1, ok.rows.size)
    }

    @Test
    fun blank_lines_separate_paragraphs() {
        val blocks = parseMarkdown("first paragraph\n\nsecond paragraph")

        assertEquals(2, blocks.size)
        val first = assertIs<MdBlock.Paragraph>(blocks[0])
        assertEquals("first paragraph", first.text.text)
        val second = assertIs<MdBlock.Paragraph>(blocks[1])
        assertEquals("second paragraph", second.text.text)
    }

    /**
     * The hub is not guaranteed to send `\n`-only line endings. `\r\n` (and
     * a lone `\r`) has to become `\n` before any line-based check runs, or a
     * heading/bullet/fence match silently fails on the trailing `\r` and a
     * stray `\r` ends up inside a rendered `Text`.
     */
    @Test
    fun crlf_line_endings_are_normalized_to_a_single_newline() {
        val blocks = parseMarkdown("# Title\r\nline")

        assertEquals(2, blocks.size)
        val heading = assertIs<MdBlock.Heading>(blocks[0])
        assertEquals("Title", heading.text.text)
        assertEquals(1, heading.level)
        val paragraph = assertIs<MdBlock.Paragraph>(blocks[1])
        assertEquals("line", paragraph.text.text)

        assertTrue(blocks.none { block ->
            val plain = when (block) {
                is MdBlock.Paragraph -> block.text.text
                is MdBlock.Heading -> block.text.text
                is MdBlock.Code -> block.text
                else -> ""
            }
            '\r' in plain
        })
    }

    // ------------------------------------------------------------ lists

    @Test
    fun an_ordered_list_honours_its_start_number_and_both_delimiters() {
        val list = assertIs<MdBlock.ListBlock>(parseMarkdown("3. three\n4) four").single())

        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(listOf("three", "four"), list.items.map { it.paragraphText() })
    }

    @Test
    fun lists_nest_by_indentation() {
        val md = "1. Reset\n2. Cap\n   - so a long outage\n     - does not stall\n   - matches the hub\n3. Test"

        val outer = assertIs<MdBlock.ListBlock>(parseMarkdown(md).single())
        assertEquals(3, outer.items.size)
        val second = outer.items[1]
        assertEquals("Cap", assertIs<MdBlock.Paragraph>(second.blocks[0]).text.text)
        val inner = assertIs<MdBlock.ListBlock>(second.blocks[1])
        assertFalse(inner.ordered)
        assertEquals(2, inner.items.size)
        val innermost = assertIs<MdBlock.ListBlock>(inner.items[0].blocks[1])
        assertEquals("does not stall", innermost.items.single().paragraphText())
        assertEquals("Test", outer.items[2].paragraphText())
    }

    /** Transcripts often nest with two spaces under `1. `, short of the content column. */
    @Test
    fun a_marker_indented_past_its_parent_marker_nests_leniently() {
        val outer = assertIs<MdBlock.ListBlock>(parseMarkdown("1. parent\n  - child").single())

        val child = assertIs<MdBlock.ListBlock>(outer.items.single().blocks[1])
        assertEquals("child", child.items.single().paragraphText())
    }

    @Test
    fun a_loose_list_with_blank_lines_between_items_stays_one_list() {
        val list = assertIs<MdBlock.ListBlock>(parseMarkdown("- a\n\n- b\n\n- c").single())

        assertEquals(3, list.items.size)
    }

    @Test
    fun task_items_carry_their_checked_state() {
        val list = assertIs<MdBlock.ListBlock>(parseMarkdown("- [x] done\n- [ ] todo\n- [X] also\n- plain").single())

        assertEquals(listOf(true, false, true, null), list.items.map { it.checked })
        assertEquals(listOf("done", "todo", "also", "plain"), list.items.map { it.paragraphText() })
    }

    @Test
    fun an_ordered_marker_other_than_one_does_not_interrupt_a_paragraph() {
        val blocks = parseMarkdown("we waited until\n2021. It was worth it")

        val paragraph = assertIs<MdBlock.Paragraph>(blocks.single())
        assertEquals("we waited until\n2021. It was worth it", paragraph.text.text)
    }

    // ------------------------------------------------------------ quotes, rules

    @Test
    fun a_blockquote_holds_other_blocks_and_nests() {
        val quote = assertIs<MdBlock.Quote>(parseMarkdown("> **Note:** shared\n> - one\n>> deeper").single())

        assertEquals("Note: shared", assertIs<MdBlock.Paragraph>(quote.blocks[0]).text.text)
        assertIs<MdBlock.ListBlock>(quote.blocks[1])
        val inner = assertIs<MdBlock.Quote>(quote.blocks[2])
        assertEquals("deeper", assertIs<MdBlock.Paragraph>(inner.blocks.single()).text.text)
    }

    @Test
    fun a_lazy_line_continues_the_quoted_paragraph() {
        val quote = assertIs<MdBlock.Quote>(parseMarkdown("> first\nsecond").single())

        assertEquals("first\nsecond", assertIs<MdBlock.Paragraph>(quote.blocks.single()).text.text)
    }

    @Test
    fun three_dashes_stars_or_underscores_are_a_rule() {
        for (rule in listOf("---", "***", "___", "- - -", "* * *")) {
            val blocks = parseMarkdown("above\n\n$rule\n\nbelow")
            assertEquals(MdBlock.Rule, blocks[1], "for $rule")
        }
    }

    /**
     * Kotlin/Native blows the stack as SIGBUS, not a catchable error, so
     * nesting is bounded before recursing: 5000 `>` (or list markers) nest
     * only MAX_DEPTH deep and the rest is paragraph text.
     */
    @Test
    fun block_nesting_is_bounded() {
        fun quoteDepth(blocks: List<MdBlock>): Int {
            val q = blocks.singleOrNull() as? MdBlock.Quote ?: return 0
            return 1 + quoteDepth(q.blocks)
        }
        val quotes = parseMarkdown(">".repeat(5000) + " deep")
        assertEquals(MAX_DEPTH, quoteDepth(quotes))

        fun listDepth(blocks: List<MdBlock>): Int {
            val l = blocks.firstOrNull() as? MdBlock.ListBlock ?: return 0
            return 1 + listDepth(l.items.first().blocks)
        }
        val lists = parseMarkdown("- ".repeat(5000) + "x")
        assertEquals(MAX_DEPTH, listDepth(lists))
    }

    /**
     * Inline spans nest (see `emphasis_nests`) but never past MAX_DEPTH:
     * thousands of alternating markers complete, with the text intact.
     */
    @Test
    fun deeply_alternating_inline_markers_complete() {
        val open = listOf("**", "_", "~~", "*")
        val input = (0 until 4000).joinToString("") { open[it % 4] + "a " } + "x" +
            (3999 downTo 0).joinToString("") { " b" + open[it % 4] }

        val paragraph = assertIs<MdBlock.Paragraph>(parseMarkdown(input).single()).text
        assertTrue('x' in paragraph.text)
    }

    // ------------------------------------------------------------ fences

    @Test
    fun a_tilde_fence_and_a_longer_backtick_fence_work() {
        val tilde = assertIs<MdBlock.Code>(parseMarkdown("~~~python\nprint(1)\n~~~").single())
        assertEquals("print(1)", tilde.text)
        assertEquals("python", tilde.lang)

        // A four-backtick fence is not closed by three.
        val outer = assertIs<MdBlock.Code>(parseMarkdown("````md\n```\ninner\n```\n````").single())
        assertEquals("```\ninner\n```", outer.text)
    }

    @Test
    fun an_indented_fence_inside_a_list_item_strips_its_indent() {
        val list = assertIs<MdBlock.ListBlock>(parseMarkdown("1. run:\n   ```sh\n   make\n   ```").single())

        val code = assertIs<MdBlock.Code>(list.items.single().blocks[1])
        assertEquals("make", code.text)
        assertEquals("sh", code.lang)
    }

    // ------------------------------------------------------------ tables

    @Test
    fun a_table_keeps_its_header_rows_and_alignment() {
        val md = "| File | Change | Lines |\n|:-----|:------:|------:|\n| `a.ts` | **reset** | +4 |\n| b.ts | test | +22 |"

        val table = assertIs<MdBlock.Table>(parseMarkdown(md).single())
        assertEquals(listOf("File", "Change", "Lines"), table.header.map { it.text })
        assertEquals(listOf(MdAlign.Start, MdAlign.Center, MdAlign.End), table.align)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("a.ts", "reset", "+4"), table.rows[0].map { it.text })
        assertTrue(table.rows[0][0].spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        assertTrue(table.rows[0][1].spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun a_table_without_outer_pipes_and_with_default_alignment_parses() {
        val table = assertIs<MdBlock.Table>(parseMarkdown("a | b\n--- | ---\n1 | 2").single())

        assertEquals(listOf(MdAlign.None, MdAlign.None), table.align)
        assertEquals(listOf("1", "2"), table.rows.single().map { it.text })
    }

    @Test
    fun ragged_rows_are_padded_or_truncated_to_the_header() {
        val md = "| a | b | c |\n|---|---|---|\n| 1 |\n| 1 | 2 | 3 | 4 | 5 |"

        val table = assertIs<MdBlock.Table>(parseMarkdown(md).single())
        assertEquals(listOf("1", "", ""), table.rows[0].map { it.text })
        assertEquals(listOf("1", "2", "3"), table.rows[1].map { it.text })
    }

    @Test
    fun an_escaped_pipe_stays_inside_its_cell() {
        val table = assertIs<MdBlock.Table>(parseMarkdown("| expr | means |\n|---|---|\n| `a \\| b` | or |").single())

        assertEquals(listOf("a | b", "or"), table.rows.single().map { it.text })
    }

    @Test
    fun a_header_and_delimiter_with_different_cell_counts_is_not_a_table() {
        val blocks = parseMarkdown("| a | b |\n|---|\n| 1 | 2 |")

        assertTrue(blocks.none { it is MdBlock.Table })
    }

    @Test
    fun a_table_ends_at_a_blank_line_or_a_line_without_a_pipe() {
        val blocks = parseMarkdown("| a |\n|---|\n| 1 |\nafter")

        assertEquals(1, assertIs<MdBlock.Table>(blocks[0]).rows.size)
        assertEquals("after", assertIs<MdBlock.Paragraph>(blocks[1]).text.text)
    }

    // ------------------------------------------------------------ inline

    @Test
    fun underscores_emphasise_but_never_inside_a_word() {
        val p = paragraph("__bold__ and _italic_ but snake_case_name stays")

        assertEquals("bold and italic but snake_case_name stays", p.text)
        assertEquals("bold", p.styled { it.fontWeight == FontWeight.Bold })
        assertEquals("italic", p.styled { it.fontStyle == FontStyle.Italic })
    }

    @Test
    fun triple_stars_are_bold_and_italic() {
        val p = paragraph("***both***")

        assertEquals("both", p.text)
        assertTrue(p.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.item.fontStyle == FontStyle.Italic })
    }

    /**
     * `___foo___` is bold-italic too, as `***foo***` already was.
     *
     * The triple handling had a table for `*` only, so the `__` branch paired
     * two of the three underscores and left the third as literal text: bold
     * `_foo` followed by a stray `_`.
     */
    @Test
    fun triple_underscores_are_bold_and_italic() {
        val p = paragraph("___both___")

        assertEquals("both", p.text)
        assertTrue(
            p.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.item.fontStyle == FontStyle.Italic },
            "${p.text} / ${p.spanStyles}",
        )

        // `_` still never OPENS against a word, which is what keeps
        // `snake_case_name` literal (asserted above). A run of three mid-word is
        // a pre-existing case this fix does not touch and does not pin:
        // `wordAt` counts letters and digits, not `_`, so the second underscore
        // of `snake___x` is not "inside a word" by that rule. Changing that is a
        // change to the `_` flanking rules, not to the triple.

        // `__bold__` and `_italic_` are unchanged.
        val two = paragraph("__b__ and _i_")
        assertEquals("b and i", two.text)
        assertEquals("b", two.styled { it.fontWeight == FontWeight.Bold })
        assertEquals("i", two.styled { it.fontStyle == FontStyle.Italic })
    }

    /**
     * A code span opened with three or more backticks is a code span.
     *
     * Only runs of 1 and 2 had closer tables, so ```` ```x``` ```` rendered as
     * literal backticks — while `fenceOpen`'s own comment calls exactly that
     * "an inline code span, not a fence".
     */
    @Test
    fun a_long_backtick_run_is_still_a_code_span() {
        val p = paragraph("see ```x``` done")
        assertEquals("see x done", p.text)
        assertEquals("x", p.styled { it.fontFamily == FontFamily.Monospace })

        // It can hold shorter runs, which is the whole point of a long run.
        val held = paragraph("see ````a ``b`` c```` done")
        assertEquals("see a ``b`` c done", held.text)
        assertEquals("a ``b`` c", held.styled { it.fontFamily == FontFamily.Monospace })

        // An unclosed run is literal text, as a short one is — tested mid-line,
        // because a line that STARTS with three backticks and a word is a fence
        // with that word as its language, which `fenceOpen` decides before any
        // of this runs.
        val open = paragraph("see ```x and more")
        assertEquals("see ```x and more", open.text)
        assertTrue(open.spanStyles.isEmpty(), "${open.spanStyles}")
    }

    @Test
    fun emphasis_nests() {
        val p = paragraph("**bold with `code` and *italic***")

        assertEquals("bold with code and italic", p.text)
        assertEquals("code", p.styled { it.fontFamily == FontFamily.Monospace })
        assertEquals("italic", p.styled { it.fontStyle == FontStyle.Italic })
    }

    @Test
    fun a_spaced_star_is_arithmetic_not_emphasis() {
        val p = paragraph("2 * 3 * 4")

        assertEquals("2 * 3 * 4", p.text)
        assertTrue(p.spanStyles.isEmpty())
    }

    @Test
    fun strikethrough_uses_line_through() {
        val p = paragraph("~~old~~ new")

        assertEquals("old new", p.text)
        assertEquals("old", p.styled { it.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun double_backtick_code_can_hold_a_backtick() {
        val p = paragraph("``a ` b`` done")

        assertEquals("a ` b done", p.text)
        assertEquals("a ` b", p.styled { it.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun inline_code_keeps_backslashes_and_stars_literal() {
        val p = paragraph("`C:\\*path*\\`")

        assertEquals("C:\\*path*\\", p.text)
        assertTrue(p.spanStyles.none { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun backslash_escapes_make_markers_literal() {
        val p = paragraph("\\*not italic\\* and \\[not a link\\](x) and \\\\")

        assertEquals("*not italic* and [not a link](x) and \\", p.text)
        assertTrue(p.spanStyles.isEmpty())
        assertTrue(p.links().isEmpty())
    }

    @Test
    fun a_markdown_link_becomes_a_url_annotation_over_its_text() {
        val p = paragraph("see the [**spec**](https://example.com/spec \"title\") now")

        assertEquals("see the spec now", p.text)
        val link = p.links().single()
        assertEquals("https://example.com/spec", (link.item as LinkAnnotation.Url).url)
        assertEquals("spec", p.text.substring(link.start, link.end))
        assertEquals("spec", p.styled { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun a_link_url_may_hold_balanced_parentheses() {
        val p = paragraph("[Foo](https://en.wikipedia.org/wiki/Foo_(bar)) x")

        assertEquals("Foo x", p.text)
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", (p.links().single().item as LinkAnnotation.Url).url)
    }

    @Test
    fun unsafe_link_schemes_render_as_plain_text() {
        for (url in listOf("javascript:alert(1)", "file:///etc/passwd", "intent://x", "relative/path", "data:text/html,x")) {
            val p = paragraph("[click]($url)")
            assertEquals("click", p.text, "for $url")
            assertTrue(p.links().isEmpty(), "for $url")
        }
        assertTrue(paragraph("<javascript:alert(1)>").links().isEmpty())
    }

    @Test
    fun mailto_links_are_allowed() {
        val p = paragraph("[mail](mailto:a@b.dev) and <c@d.dev>")

        assertEquals(
            listOf("mailto:a@b.dev", "mailto:c@d.dev"),
            p.links().map { (it.item as LinkAnnotation.Url).url },
        )
        assertEquals("mail and c@d.dev", p.text)
    }

    /**
     * Six heading levels parse as six levels — the renderer draws each at its
     * own size, which the file comment promises and 4, 5 and 6 did not get
     * (they shared one style).
     */
    @Test
    fun every_heading_level_parses_as_its_own_level() {
        val md = (1..6).joinToString("\n\n") { "${"#".repeat(it)} h$it" }
        val levels = parseMarkdown(md).map { assertIs<MdBlock.Heading>(it).level }
        assertEquals(listOf(1, 2, 3, 4, 5, 6), levels)
        // Seven is not a heading.
        assertIs<MdBlock.Paragraph>(parseMarkdown("####### h7").single())

        // And each level draws at its OWN step. 4, 5 and 6 shared one style, so
        // they were indistinguishable on screen while the file comment promised
        // "distinct sizes".
        val type = androidx.compose.material3.Typography()
        val styles = (1..6).map { headingStyle(type, it) }
        // The three that collided: 4, 5 and 6 shared ONE style, and now differ.
        assertEquals(3, styles.drop(3).distinct().size, "$styles")
        assertEquals(3, styles.drop(3).map { it.fontSize }.distinct().size, "by size, not just weight")
        // 1, 2 and 3 were already distinct from each other.
        assertEquals(3, styles.take(3).distinct().size)
        // Level 3 and level 4 are the same step of the default type scale
        // (`titleSmall` and `labelLarge` are one style there); `HeadingView`
        // tells them apart with the variant colour it gives level 4 and up, so
        // this is not the collision the fix was about.
        assertEquals(styles[2], styles[3])
    }

    @Test
    fun autolinks_and_bare_urls_become_links() {
        val p = paragraph("<https://a.dev/x> and https://b.dev/y_z. And (https://c.dev/q) end")

        assertEquals(
            listOf("https://a.dev/x", "https://b.dev/y_z", "https://c.dev/q"),
            p.links().map { (it.item as LinkAnnotation.Url).url },
        )
        assertEquals("https://a.dev/x and https://b.dev/y_z. And (https://c.dev/q) end", p.text)
    }

    /**
     * A bare URL ends at a closing bracket it does not own.
     *
     * The trim set had `)` (balanced against a `(` inside the URL) but not `]`,
     * `}`, `` ` `` or `>`, so a URL written inside brackets or braces swallowed
     * the closer into the LIVE link, not merely the drawn text — the thing a tap
     * then opened was not the URL.
     */
    @Test
    fun a_bare_url_does_not_swallow_a_bracket_it_does_not_own() {
        val url = "https://x.dev/a"
        for ((open, close) in listOf("[" to "]", "{" to "}")) {
            val p = paragraph("$open$url$close rest")
            assertEquals(
                listOf(url),
                p.links().map { (it.item as LinkAnnotation.Url).url },
                "$open$url$close",
            )
        }

        // A balanced `(` inside the URL is still part of it — the case the `)`
        // rule exists for.
        val wiki = "https://en.wikipedia.org/wiki/Foo_(bar)"
        assertEquals(
            listOf(wiki),
            paragraph("see $wiki end").links().map { (it.item as LinkAnnotation.Url).url },
        )

        // And an unbalanced one still ends the URL.
        assertEquals(
            listOf(url),
            paragraph("($url) end").links().map { (it.item as LinkAnnotation.Url).url },
        )
    }

    @Test
    fun an_image_is_never_fetched_and_shows_its_alt_text() {
        val p = paragraph("![a diagram](https://example.com/d.png)")

        assertEquals("a diagram", p.text)
        // A tap-to-open link, not an inline image.
        assertEquals("https://example.com/d.png", (p.links().single().item as LinkAnnotation.Url).url)
    }

    @Test
    fun a_link_inside_link_text_is_not_nested() {
        val p = paragraph("[see https://x.dev](https://y.dev)")

        assertEquals(1, p.links().size)
    }

    /**
     * The O(n) guarantee for the other markers: thousands of stray openers
     * of every kind complete promptly (no wall-clock assertion, see the
     * 5000-star test above for why).
     */
    @Test
    fun many_stray_markers_of_every_kind_complete() {
        val input = "[`_~<!(".repeat(3000) + "https://".repeat(2000)

        val p = paragraph(input)
        assertTrue(p.text.isNotEmpty())
    }

    /**
     * A link's text ends at the `]` that MATCHES its `[`, not the first one.
     *
     * A README badge is an image inside a link —
     * `[![build](badge.svg)](ci-url)` — and taking the first `]` took the
     * image's, so the link resolved to the badge image's own URL and
     * `](ci-url)` stayed on screen as literal text. A tap went to the picture
     * rather than to the build.
     */
    @Test
    fun a_link_whose_text_holds_an_image_keeps_its_own_url() {
        val p = paragraph("[![build](https://img.dev/badge.svg)](https://ci.dev/job) after")

        assertEquals(
            listOf("https://ci.dev/job"),
            p.links().map { (it.item as LinkAnnotation.Url).url },
            "the LINK's url, not the image's",
        )
        assertFalse("](" in p.text, "no leftover markup on screen: ${p.text}")
        assertEquals("build after", p.text)
    }

    /** Plain nesting too, with no image involved. */
    @Test
    fun a_bracket_inside_a_links_text_does_not_end_it() {
        val p = paragraph("[see [1] below](https://a.dev/x)")

        assertEquals(
            listOf("https://a.dev/x"),
            p.links().map { (it.item as LinkAnnotation.Url).url },
        )
        assertEquals("see [1] below", p.text)
    }

    /**
     * And an UNBALANCED `]` inside a code span in the link text is text, not a
     * closer.
     *
     * Balanced brackets (`a[0]`) are handled by the depth count alone; only a
     * lone one inside a span needs the span to be skipped whole, and that is
     * the case a code snippet about Markdown itself produces.
     */
    @Test
    fun an_unbalanced_bracket_in_a_code_span_does_not_end_a_links_text() {
        val p = paragraph("[the `]` case](https://a.dev/x)")

        assertEquals(
            listOf("https://a.dev/x"),
            p.links().map { (it.item as LinkAnnotation.Url).url },
        )
        assertEquals("the ] case", p.text)

        // And the balanced form, which the depth count alone would also get.
        val balanced = paragraph("[the `a[0]` case](https://a.dev/x)")
        assertEquals(
            listOf("https://a.dev/x"),
            balanced.links().map { (it.item as LinkAnnotation.Url).url },
        )
        assertEquals("the a[0] case", balanced.text)
    }

    /**
     * A link's URL ends at the `)` that MATCHES its `(`, wherever the balanced
     * pair sits inside it.
     *
     * The old rule took the first `)` and then extended only while the very
     * NEXT character was another `)` — so a URL whose parentheses are not at
     * its very end was cut at the first one, and the truncated string became
     * the live `LinkAnnotation.Url`. The tap opened a page that does not exist.
     */
    @Test
    fun a_links_url_keeps_balanced_parentheses_wherever_they_sit() {
        val mid = "https://en.wikipedia.org/wiki/Foo_(bar)/edit"
        assertEquals(
            listOf(mid),
            paragraph("[x]($mid) end").links().map { (it.item as LinkAnnotation.Url).url },
            "parentheses in the MIDDLE of the path",
        )

        val end = "https://en.wikipedia.org/wiki/Foo_(bar)"
        assertEquals(
            listOf(end),
            paragraph("[x]($end) end").links().map { (it.item as LinkAnnotation.Url).url },
            "and at its end, which the old rule did handle",
        )

        val nested = "https://a.dev/f(g(h))/i"
        assertEquals(
            listOf(nested),
            paragraph("[x]($nested)").links().map { (it.item as LinkAnnotation.Url).url },
            "two levels deep",
        )

        // A title after a space still ends the destination.
        assertEquals(
            listOf("https://a.dev/x"),
            paragraph("""[x](https://a.dev/x "a title")""").links().map { (it.item as LinkAnnotation.Url).url },
        )
    }

    /**
     * A list item ends at the prose that follows its closed code fence.
     *
     * The lazy-continuation test was "the previous line is not blank", and a
     * fence's closing ``` satisfies it — so the shape of every "do this:" /
     * code / "then that" transcript silently folded the closing prose into the
     * bullet.
     */
    @Test
    fun prose_after_an_items_closed_fence_ends_the_list() {
        // No blank line before the prose: with one, the OLD rule already
        // ended the item (its last collected line was blank), so the fence
        // was never what the test turned on.
        val blocks = parseMarkdown("- run it:\n  ```sh\n  ./gradlew check\n  ```\nThen read the report.")

        assertEquals(2, blocks.size, "a list and a paragraph: ${blocks.map { it::class.simpleName }}")
        assertIs<MdBlock.ListBlock>(blocks[0])
        assertEquals("Then read the report.", assertIs<MdBlock.Paragraph>(blocks[1]).text.text)
    }

    /** The same for a quote. */
    @Test
    fun prose_after_a_quotes_closed_fence_ends_the_quote() {
        val blocks = parseMarkdown("> run it:\n> ```sh\n> ./gradlew check\n> ```\nThen read the report.")

        assertEquals(2, blocks.size, "a quote and a paragraph: ${blocks.map { it::class.simpleName }}")
        assertIs<MdBlock.Quote>(blocks[0])
        assertEquals("Then read the report.", assertIs<MdBlock.Paragraph>(blocks[1]).text.text)
    }

    /** An ordinary lazy continuation of a paragraph still works. */
    @Test
    fun a_lazy_paragraph_continuation_still_joins_its_item() {
        val blocks = parseMarkdown("- a sentence that\nwraps onto the next line")

        val list = assertIs<MdBlock.ListBlock>(blocks.single())
        assertEquals("a sentence that\nwraps onto the next line", list.items.single().paragraphText())
    }

    // ------------------------------------------------------------ helpers

    private fun paragraph(md: String): AnnotatedString = assertIs<MdBlock.Paragraph>(parseMarkdown(md).single()).text

    private fun AnnotatedString.links() = getLinkAnnotations(0, length)

    private fun AnnotatedString.styled(pred: (androidx.compose.ui.text.SpanStyle) -> Boolean): String {
        val span = spanStyles.first { pred(it.item) && it.end > it.start }
        return text.substring(span.start, span.end)
    }

    private fun MdListItem.paragraphText(): String = assertIs<MdBlock.Paragraph>(blocks.single()).text.text
}
