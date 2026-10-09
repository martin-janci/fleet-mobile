package dev.claudefleet.mobile.host

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import dev.claudefleet.mobile.ui.theme.OrbitColors
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every colour the question card writes text in holds 4.5:1 on the card's
 * own tint (review r11). The card sits on `waitingSoft`, or `failedSoft` when
 * the session is stuck: a 13% fill over the ground, which pulls fg-muted,
 * code and the accent under 4.5:1 in the light theme. A composable cannot be
 * drawn here, so this reads the card's source for its text colours and
 * measures each one against both tints over both grounds, in both themes.
 */
class QuestionCardContrastTest {

    private val card by lazy {
        Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/QuestionCard.kt").readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")
    }

    /** The card body, up to the answer row (which draws on its own ground). */
    private val body by lazy { card.substringAfter("Surface(").substringBefore("private fun AnswerRow(") }

    private fun ratio(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    private fun token(o: OrbitColors, name: String): Color =
        OrbitColors::class.java.getMethod("get" + name.replaceFirstChar { it.uppercase() }).invoke(o) as Color

    @Test
    fun every_text_colour_on_the_card_reads_on_its_tint() {
        val names = Regex("""(?:color|contentColor) = Fleet\.colors\.(\w+)""").findAll(body).map { it.groupValues[1] }.toSet()
        assertEquals(true, "fg2" in names, "the scan reads the card's colours")
        val under = mutableListOf<String>()
        for (dark in listOf(false, true)) {
            val o = OrbitColors(isDark = dark)
            for (tint in listOf("waitingSoft", "failedSoft")) {
                for (ground in listOf(o.bg, o.bgPane)) {
                    val fill = token(o, tint).compositeOver(ground)
                    for (n in names) {
                        val r = ratio(token(o, n), fill)
                        if (r < 4.5f) under += "$n on $tint, dark=$dark: $r:1"
                    }
                }
            }
        }
        assertEquals(emptyList(), under.distinct())
    }

    @Test
    fun every_text_button_on_the_card_names_its_colour() {
        // A TextButton's default label is the accent, ~4.0:1 on the tint.
        val buttons = Regex("""TextButton\(([^)]*)\)""").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(true, buttons.isNotEmpty())
        assertEquals(emptyList(), buttons.filterNot { "colors =" in it })
    }
}
