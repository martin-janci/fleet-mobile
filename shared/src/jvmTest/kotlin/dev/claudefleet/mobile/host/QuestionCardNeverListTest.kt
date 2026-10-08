package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The question card (redesign 14.4) never pre-selects Approve.
 *
 * The manual's never-list (`design-system/ai.md`) says a permission is
 * answered by a person's tap, and that nothing draws one answer as the
 * default. `questionAnswers` decides which answers there are (and is tested in
 * `SessionWorkspaceTest`); this pins how they are drawn, by a source scan
 * because a composable cannot be rendered here: every answer goes through the
 * one `AnswerRow`, which has no selected, primary or focused form, and the
 * card holds no filled button and asks for no focus.
 */
class QuestionCardNeverListTest {

    private val card by lazy { Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/QuestionCard.kt").readText() }

    private val code by lazy { card.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "") }

    @Test
    fun every_answer_is_drawn_by_the_one_plain_row() {
        assertEquals(1, Regex("""(?<!fun )\bAnswerRow\(\s*label""").findAll(code).count(), "one call site, inside the loop over the answers")
        assertTrue(Regex("""for \(answer in answers\) \{\s*AnswerRow\(""").containsMatchIn(code))
        val row = code.substringAfter("private fun AnswerRow(")
        assertTrue("selected" !in row && "primary" !in row && "default" !in row.lowercase(), "AnswerRow has no emphasised variant")
    }

    @Test
    fun nothing_on_the_card_is_filled_selected_or_focused() {
        val banned = listOf("FilledIconButton(", "FilledTonalButton(", " Button(", "selected =", "requestFocus", "FocusRequester", "autofocus")
        assertEquals(emptyList(), banned.filter { it in code }, "the question card must not emphasise or pre-select an answer")
    }
}
