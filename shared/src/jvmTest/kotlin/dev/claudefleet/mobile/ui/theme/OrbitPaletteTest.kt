package dev.claudefleet.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The phone's port of the palette engine paints what the desktop paints, and
 * every pair the phone draws holds its floor from midnight to noon at every
 * level and accent.
 */
class OrbitPaletteTest {

    private val suns = (0..40).map { -1.0 + it * 0.05 }

    private fun ratio(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    /** Golden values from claude-fleet's `src/lib/palette.ts` (same inputs). One byte of float drift is allowed. */
    @Test
    fun paints_what_the_desktop_paints() {
        val golden = listOf(
            Triple(Triple(1.0, ContrastLevel.STD, 205.0), "bg,bg-pane,fg,fg-muted,accent,accent-fg,status-waiting,control-border,code",
                "#f7fbfe,#feffff,#161a1e,#565c62,#02717a,#ffffff,#84540e,#a1a8ae,#03707e"),
            Triple(Triple(0.3, ContrastLevel.SOFT, 258.0), "bg,bg-pane,fg,fg-muted,accent,accent-fg,status-waiting,control-border,code",
                "#f0f6fa,#f8fcff,#2d3338,#565f66,#085dc7,#ffffff,#83530c,#afbbc1,#03707e"),
            Triple(Triple(-0.1, ContrastLevel.STD, 205.0), "bg,bg-pane,fg,fg-muted,accent,accent-fg,status-waiting,control-border,code",
                "#16212e,#1c2834,#ffffff,#8baaca,#34d5e4,#0b0f17,#f3a949,#476381,#22cde5"),
            Triple(Triple(-1.0, ContrastLevel.HIGH, 45.0), "bg,bg-pane,fg,fg-muted,accent,accent-fg,status-waiting,control-border,code",
                "#0b1016,#10151c,#e1edfa,#a0aec0,#fca57e,#0b0f17,#f3a949,#5a6877,#22cde5"),
        )
        for ((input, names, hexes) in golden) {
            val p = OrbitPalette.colors(input.first, input.second, input.third)
            for ((name, hex) in names.split(",").zip(hexes.split(","))) {
                val want = hex.removePrefix("#").toLong(16)
                val got = assertNotNull(p[name], name) and 0xFFFFFFL
                for (shift in listOf(16, 8, 0)) {
                    val d = abs(((got shr shift) and 0xFFL) - ((want shr shift) and 0xFFL))
                    assertTrue(d <= 1, "$name at $input: got #${got.toString(16).padStart(6, '0')}, desktop paints $hex")
                }
            }
        }
    }

    @Test
    fun every_pair_holds_through_the_day() {
        val fails = mutableListOf<String>()
        for (level in ContrastLevel.entries) for (accent in OrbitAccent.entries) for (sun in suns) {
            val o = OrbitColors(sun < 0, OrbitPalette.colors(sun, level, accent.hue))
            fun check(what: String, fg: Color, bg: Color, floor: Float) {
                val r = ratio(fg, bg)
                if (r < floor) fails += "$level/$accent sun=$sun: $what $r < $floor"
            }
            for ((gn, ground) in listOf("bg" to o.bg, "bg-pane" to o.bgPane, "bg-raise" to o.bgRaise)) {
                check("fg on $gn", o.fg, ground, 4.5f)
                check("fg-2 on $gn", o.fg2, ground, 4.5f)
                check("fg-muted on $gn", o.fgMuted, ground, 4.5f)
            }
            check("fg-muted on chip-bg", o.fgMuted, o.chipBg, 4.5f)
            check("fg on accent-soft", o.fg, o.accentSoft, 4.5f)
            check("accent-fg on accent", o.accentFg, o.accent, 4.5f)
            check("accent on bg", o.accent, o.bg, 3f)
            check("on-waiting on status-waiting", o.onWaiting, o.statusWaiting, 4.5f)
            for (ground in listOf(o.bg, o.bgPane)) {
                for (tone in StatusTone.entries.filterNot { it == StatusTone.STOPPED || it == StatusTone.UNKNOWN }) {
                    val c = orbitStatusColors(o, tone)
                    check("$tone word on its chip", c.onContainer, c.container.compositeOver(ground), 4.5f)
                }
            }
        }
        assertEquals(emptyList(), fails)
    }

    @Test
    fun is_dark_exactly_while_the_sun_is_down() {
        for (sun in suns) {
            val bg = Color(OrbitPalette.colors(sun, ContrastLevel.STD, 205.0).getValue("bg"))
            assertEquals(sun < 0, bg.luminance() < 0.1f, "sun=$sun")
        }
    }

    @Test
    fun the_sun_rises_and_sets_about_when_it_does_in_bratislava_at_midsummer() {
        // 21 June (day 172), CEST: sunrise 04:50, sunset 20:52.
        val t = assertNotNull(OrbitPalette.sunTimes(172, 2.0, 48.15, 17.11))
        assertTrue(abs(t.rise - 4.83) < 0.2, "rise ${t.rise}")
        assertTrue(abs(t.set - 20.87) < 0.2, "set ${t.set}")
        assertEquals(null, OrbitPalette.sunTimes(172, 1.0, 78.0, 15.0))
        assertTrue(OrbitPalette.polarDay(172, 78.0))
        val noon = LocalClock(dayOfYear = 172, hour = 13, minute = 0, utcOffsetHours = 2.0, standardOffsetHours = 1.0)
        val night = noon.copy(hour = 1)
        assertTrue(OrbitPalette.sunNow(noon) > 0.9)
        assertTrue(OrbitPalette.sunNow(night) < -0.9)
    }
}
