package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A prompt the hub refuses stays in the conversation as "Not sent" on every
 * screen, Control's chat included (review w08-9): one rule, not a Control
 * exception left over from the Classic layout. The view model's half is
 * `SessionViewModelTest`'s `keepNotSent = true` cases; this pins the wiring,
 * which a composable test cannot reach from here.
 */
class NotSentIsOneRuleTest {

    private fun code(path: String): String = Repo.file(path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")

    @Test
    fun every_session_screen_keeps_a_refused_prompt() {
        val app = code("shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt")
        val passed = Regex("""keepNotSent = ([^,\n]+)""").findAll(app).map { it.groupValues[1].trim() }.toList()
        assertEquals(listOf("true"), passed, "App.kt builds its one SessionViewModel with keepNotSent = true, whatever the screen")
    }

    @Test
    fun the_not_sent_card_is_drawn_without_tabs_too() {
        val screen = code("shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionScreen.kt")
        val at = screen.indexOf("NotSentCard(\n")
        assertTrue(at > 0, "the scan has gone stale: SessionScreen draws no NotSentCard")
        // The `if (tabs != null) {` block before the card closes before it: count braces from the block's start.
        val block = screen.lastIndexOf("if (tabs != null) {", at)
        assertTrue(block > 0, "the scan has gone stale")
        var depth = 0
        for (c in screen.substring(block, at)) {
            if (c == '{') depth++ else if (c == '}') depth--
        }
        // Inside the block the count stays above zero; the card sits in `state.notSent?.let {`, one level of its own.
        assertEquals(1, depth, "NotSentCard must not sit inside `if (tabs != null)`: Control's chat has no tabs")
    }
}
