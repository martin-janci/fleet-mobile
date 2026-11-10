package dev.claudefleet.mobile.host

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import dev.claudefleet.mobile.ui.theme.OrbitColors
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The "Plan ready" card (claude-fleet `docs/plan-first.md`) asks for a yes
 * that starts the work, so it is held to the question card's rules
 * ([QuestionCardNeverListTest], [QuestionCardContrastTest]): no answer is
 * drawn as the default, a tap in the first moments is not an approval, and
 * its text reads on the amber tint. A composable cannot be drawn here, so
 * this reads the card's source with its comments removed.
 */
class PlanReadyCardTest {

    private val code by lazy {
        Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/PlanReady.kt").readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")
    }

    /** The card, up to the row its answers are drawn by. */
    private val card by lazy { code.substringAfter("internal fun PlanReadyCard(").substringBefore("private fun PlanRow(") }

    private val row by lazy { code.substringAfter("private fun PlanRow(") }

    @Test
    fun every_answer_is_drawn_by_the_one_plain_row() {
        assertTrue("private fun PlanRow(" in code && card != code, "the scan has gone stale")
        val labels = Regex("""\bPlanRow\(\s*label = (?:if \(host\.sending\) "[^"]*" else )?"([^"]*)"""").findAll(card).map { it.groupValues[1] }.toList()
        assertEquals(listOf("Read plan", "Approve and start", "Ask for changes…"), labels)
        assertTrue("selected" !in row && "primary" !in row && "default" !in row.lowercase(), "PlanRow has no emphasised variant")
    }

    @Test
    fun nothing_on_the_card_is_filled_selected_or_focused() {
        val banned = listOf("FilledIconButton(", "FilledTonalButton(", "OutlinedButton(", " Button(", "selected =", "requestFocus", "FocusRequester", "autofocus")
        assertEquals(emptyList(), banned.filter { it in code }, "the plan card must not emphasise or pre-select Approve")
    }

    @Test
    fun a_tap_as_the_card_appears_is_not_an_approval() {
        assertTrue(Regex("""LaunchedEffect\(plan\) \{\s*delay\(ANSWER_ARM_MS\)\s*armed = true\s*\}""").containsMatchIn(card), "the card arms itself after the question card's wait")
        assertTrue("var armed by remember(plan) { mutableStateOf(false) }" in card, "and starts over for another plan")
        assertEquals(listOf("{ if (armed) approve() }"), Regex("""onClick = (\{[^}]*approve\(\)[^}]*\})""").findAll(card).map { it.groupValues[1] }.toList())
        assertEquals(1, Regex("""\bapprove\(\)""").findAll(card).count(), "Approve is sent from that one tap and nowhere else")
    }

    private fun ratio(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    /** The colour tokens the card may write in, by their Kotlin name; another one fails the test by name. */
    private fun token(o: OrbitColors, name: String): Color = when (name) {
        "fg" -> o.fg
        "fg2" -> o.fg2
        "fgMuted" -> o.fgMuted
        "accent" -> o.accent
        "code" -> o.code
        "statusWaiting" -> o.statusWaiting
        else -> fail("PlanReadyCardTest does not know the colour token `$name`; add it to `token`")
    }

    @Test
    fun every_text_colour_on_the_card_reads_on_its_tint() {
        assertTrue("val tint = Fleet.colors.waitingSoft" in card && "color = tint," in card, "the card sits on the question card's amber")
        val names = Regex("""(?:color|contentColor) = Fleet\.colors\.(\w+)""").findAll(card).map { it.groupValues[1] }.toSet()
        assertTrue("fg" in names && "fg2" in names, "the scan reads the card's colours: $names")
        val under = mutableListOf<String>()
        for (dark in listOf(false, true)) {
            val o = OrbitColors(isDark = dark)
            for (ground in listOf(o.bg, o.bgPane)) {
                val fill = o.waitingSoft.compositeOver(ground)
                for (n in names) {
                    val r = ratio(token(o, n), fill)
                    if (r < 4.5f) under += "$n on waitingSoft, dark=$dark: $r:1"
                }
            }
        }
        assertEquals(emptyList(), under.distinct())
    }

    @Test
    fun every_text_button_on_the_card_names_its_colour() {
        // A TextButton's default label is the accent, ~4.0:1 on the tint.
        val buttons = Regex("""TextButton\(([^)]*)\)""").findAll(card).map { it.groupValues[1] }.toList()
        assertTrue(buttons.isNotEmpty())
        assertEquals(emptyList(), buttons.filterNot { "colors =" in it })
    }
}
