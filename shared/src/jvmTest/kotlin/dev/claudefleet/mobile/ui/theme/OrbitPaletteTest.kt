package dev.claudefleet.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import dev.claudefleet.mobile.host.Repo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    /**
     * Golden values from claude-fleet's `src/lib/palette.ts`, read from
     * `docs/design/palette-golden.json` (its file, copied unchanged and held to
     * claude-fleet main by `scripts/check-design-tokens.sh`; review w20-12).
     * One byte of float drift is allowed.
     */
    @Test
    fun paints_what_the_desktop_paints() {
        val golden = Json.parseToJsonElement(Repo.file("docs/design/palette-golden.json").readText()).jsonObject
        val cases = golden["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty(), "palette-golden.json has no cases")
        for (case in cases) {
            val sun = case["sun"]!!.jsonPrimitive.double
            val level = ContrastLevel.valueOf(case["contrast"]!!.jsonPrimitive.content.uppercase())
            val hue = case["hue"]!!.jsonPrimitive.double
            val p = OrbitPalette.colors(sun, level, hue)
            for ((name, hex) in case["colors"]!!.jsonObject) {
                val want = hex.jsonPrimitive.content.removePrefix("#").toLong(16)
                val got = assertNotNull(p[name], "$name at sun=$sun $level $hue") and 0xFFFFFFL
                for (shift in listOf(16, 8, 0)) {
                    val d = abs(((got shr shift) and 0xFFL) - ((want shr shift) and 0xFFL))
                    assertTrue(d <= 1, "$name at sun=$sun $level $hue: got #${got.toString(16).padStart(6, '0')}, desktop paints ${hex.jsonPrimitive.content}")
                }
            }
        }
    }

    /**
     * Galaxy and Radar draw `loader-accent` on `brand-ink`, dark in both
     * themes; the engine's day accent is dark too and fell to 2.4:1 there, so
     * the engine leaves the brand value alone (review w20-2).
     */
    @Test
    fun the_loader_accent_reads_on_its_dark_stage() {
        for (level in ContrastLevel.entries) for (accent in OrbitAccent.entries) for (sun in suns) {
            val o = OrbitColors(sun < 0, OrbitPalette.colors(sun, level, accent.hue))
            val r = ratio(o.loaderAccent, o.brandInk)
            assertTrue(r >= 4.5f, "$level/$accent sun=$sun: loader-accent on brand-ink $r < 4.5")
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
