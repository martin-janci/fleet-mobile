package dev.claudefleet.mobile.ui.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The light check (gap plan G5.9, the MobileLight board): every colour the
 * phone writes text in holds 4.5:1 on every ground a screen draws on, in the
 * light theme as in the dark. Measured on the tokens themselves, so a token
 * change that breaks a light screen fails here before anyone holds a phone
 * next to the board.
 */
class LightCheckTest {

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val v = ((argb shr shift) and 0xFFL) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun ratio(a: Long, b: Long): Double {
        val la = luminance(a) + 0.05
        val lb = luminance(b) + 0.05
        return max(la, lb) / min(la, lb)
    }

    /** The screens' grounds: the page, a card or sheet, a sunk field, a raised row. */
    private val grounds = listOf("bg", "bg-pane", "bg-sunk", "bg-raise")

    /** The colours text is drawn in: body, secondary, muted, links and every status word. */
    private val text = listOf(
        "fg", "fg-2", "fg-muted", "accent", "code", "danger", "control-fg",
        "status-working", "status-waiting", "status-done", "status-failed", "status-idle",
    )

    private fun under(dark: Boolean): List<String> = text.flatMap { t ->
        grounds.mapNotNull { g ->
            val r = ratio(OrbitTokens.argb(t, dark), OrbitTokens.argb(g, dark))
            if (r < 4.5) "$t on $g (${if (dark) "dark" else "light"}): ${(r * 100).toInt() / 100.0}:1" else null
        }
    }

    @Test
    fun every_text_colour_reads_on_every_light_ground() {
        assertEquals(emptyList(), under(dark = false))
    }

    @Test
    fun and_on_every_dark_ground() {
        assertEquals(emptyList(), under(dark = true))
    }

    /** The accent button's label on its own fill, the one place text sits on a colour. */
    @Test
    fun a_filled_buttons_label_reads_in_both_themes() {
        for (dark in listOf(false, true)) {
            val r = ratio(OrbitTokens.argb("accent-fg", dark), OrbitTokens.argb("accent", dark))
            assertTrue(r >= 4.5, "accent-fg on accent, dark=$dark: $r:1")
        }
    }
}
