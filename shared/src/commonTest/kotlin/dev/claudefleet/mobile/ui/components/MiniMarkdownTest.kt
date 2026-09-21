package dev.claudefleet.mobile.ui.components

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun dash_and_star_prefixed_lines_become_bullets() {
        val blocks = parseMarkdown("- one\n* two")

        assertEquals(2, blocks.size)
        val one = assertIs<MdBlock.Bullet>(blocks[0])
        assertEquals("one", one.text.text)
        val two = assertIs<MdBlock.Bullet>(blocks[1])
        assertEquals("two", two.text.text)
    }

    /**
     * The parser hands one logical bullet to the renderer regardless of its
     * length; wrapping under the marker (see `MarkdownText`'s `Row` in
     * `MiniMarkdown.kt`, which needs `Modifier.weight(1f)` on the body
     * `Text` for this) is a layout concern, not a parsing one. This just
     * pins the parser side of that: a single, very long line prefixed with
     * `- ` stays exactly one `Bullet` block, not split by its own length.
     */
    @Test
    fun a_long_bullet_stays_a_single_block() {
        val long = "word ".repeat(50).trim() // 249 chars, no newline
        assertTrue(long.length > 200)

        val blocks = parseMarkdown("- $long")

        assertEquals(1, blocks.size)
        val bullet = assertIs<MdBlock.Bullet>(blocks.single())
        assertEquals(long, bullet.text.text)
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
    fun a_heading_is_demoted_to_a_bold_paragraph() {
        val blocks = parseMarkdown("# Title")

        val paragraph = assertIs<MdBlock.Paragraph>(blocks.single()).text
        assertEquals("Title", paragraph.text)
        val bold = paragraph.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        assertEquals(0, bold.start)
        assertEquals(paragraph.text.length, bold.end)
    }

    @Test
    fun a_deeper_heading_level_is_also_demoted_to_bold() {
        val blocks = parseMarkdown("### Subheading")

        val paragraph = assertIs<MdBlock.Paragraph>(blocks.single()).text
        assertEquals("Subheading", paragraph.text)
        assertTrue(paragraph.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
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
     * A run of `*` this long always pairs up under the shared matching rule
     * (any two identical, un-escaped markers find each other, nearest first)
     * -- there is no input shape that leaves *many* identical single-character
     * markers simultaneously stray, since any earlier one finds a later one.
     * Genuinely stray behaviour is what the two single-occurrence tests above
     * pin; this one pins the large-input performance the fix exists for, and
     * the exact (paired, mostly-empty) text a run of adjacent `*` produces --
     * unchanged from what the un-fixed scan already returned, just returned
     * fast rather than quadratically.
     */
    @Test
    fun five_thousand_adjacent_stars_complete_quickly_and_deterministically() {
        val input = "*".repeat(5000)

        val blocks = parseMarkdown(input)

        val paragraph = assertIs<MdBlock.Paragraph>(blocks.single())
        // Every `*` pairs with its immediate neighbour as an (empty) bold
        // span -- 5000 is a multiple of 4, so the whole run is consumed and
        // nothing is left over to fall back to a literal, unmatched `*`.
        assertEquals("", paragraph.text.text)
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
        val heading = assertIs<MdBlock.Paragraph>(blocks[0])
        assertEquals("Title", heading.text.text)
        assertTrue(heading.text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        val paragraph = assertIs<MdBlock.Paragraph>(blocks[1])
        assertEquals("line", paragraph.text.text)

        assertTrue(blocks.none { block ->
            val plain = when (block) {
                is MdBlock.Paragraph -> block.text.text
                is MdBlock.Bullet -> block.text.text
                is MdBlock.Code -> block.text
            }
            '\r' in plain
        })
    }
}
