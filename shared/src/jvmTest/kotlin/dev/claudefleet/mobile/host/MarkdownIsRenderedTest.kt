package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Assistant text is hub-sourced Markdown, not plain text — `**bold**`, `- `
 * lists and fenced code blocks are common in a transcript (a test summary
 * line, a diff, a shell command) and used to render as literal source
 * characters because `ConvItem.Text` was drawn with a bare `Text(item.text)`.
 *
 * This is a source scan, not a rendering test, for the same reason
 * [NoLetterTabIconsTest] is one: it guards a property of the call site (does
 * `SessionScreen.kt` still hand `item.text` straight to a bare `Text(...)`?)
 * rather than of a pure function, and there is no framework-agnostic way to
 * assert "this Composable parses Markdown" from a JVM unit test. What *can*
 * be asserted, cheaply and durably, is that the old literal-text call site is
 * gone — so a regression that reverts to `Text(item.text)` fails loudly
 * instead of shipping quietly.
 */
class MarkdownIsRenderedTest {
    @Test
    fun conv_item_text_no_longer_renders_through_a_bare_text_call() {
        val screen = Repo.file(
            "shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionScreen.kt",
        ).readText()

        assertTrue(
            "is ConvItem.Text ->" in screen,
            "expected an is ConvItem.Text branch in SessionScreen.kt's Item(...)",
        )
        assertFalse(
            BARE_TEXT_OF_ITEM_TEXT.containsMatchIn(screen),
            "ConvItem.Text still renders through a bare Text(item.text / Text(text = item.text -- " +
                "Markdown syntax (bold, lists, code fences) would show as literal source characters",
        )
    }

    private companion object {
        // A bare `Text(` call -- not `MarkdownText(` or any other `*Text(`,
        // hence the negative lookbehind for an identifier character right
        // before it -- followed, after any whitespace/newlines and an
        // optional `text =` label, by `item.text`. Tolerant of the call
        // being split across lines, which is how it is actually formatted
        // in the file.
        val BARE_TEXT_OF_ITEM_TEXT = Regex(
            """(?<![A-Za-z0-9_])Text\(\s*(text\s*=\s*)?item\.text\b""",
        )
    }
}
