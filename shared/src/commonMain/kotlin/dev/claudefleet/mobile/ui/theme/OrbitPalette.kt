package dev.claudefleet.mobile.ui.theme

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Orbit palette engine (dynamic day/night theme, "Mission HUD" look): a
 * port of claude-fleet's `src/lib/palette.ts`, same inputs, same targets, same
 * colours (`OrbitPaletteTest` holds golden values taken from the desktop).
 *
 * Three inputs make every colour it owns: how high the sun is ([sun], -1 at
 * midnight to 1 at noon; dark exactly while it is below the horizon), a
 * contrast level and one accent hue. Colours are built in OKLCH and each text
 * colour is solved for a WCAG ratio against the hardest ground it sits on, so
 * contrast holds while the grounds drift through the day. Status hues stay
 * fixed and only move in lightness. Brand, terminal and destructive-fill
 * colours are not touched: they stay [OrbitTokens]'.
 */
enum class ContrastLevel { SOFT, STD, HIGH }

/** The accent hues the phone offers, the desktop's list; Cyan is the HUD default. */
enum class OrbitAccent(val label: String, val hue: Double) {
    CYAN("Cyan", 205.0),
    BLUE("Blue", 258.0),
    VIOLET("Violet", 290.0),
    TEAL("Teal", 168.0),
    COPPER("Copper", 45.0),
}

internal object OrbitPalette {

    private class Ok(val hex: Long, val y: Double, val l: Double)

    private fun linear(l: Double, c: Double, h: Double): DoubleArray {
        val r = h * PI / 180
        val a = c * cos(r)
        val b = c * sin(r)
        val l_ = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m_ = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s_ = (l - 0.0894841775 * a - 1.291485548 * b).pow(3)
        return doubleArrayOf(
            4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_,
            -1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_,
            -0.0041960863 * l_ - 0.7034186147 * m_ + 1.707614701 * s_,
        )
    }

    private fun inGamut(v: DoubleArray) = v.all { it >= -0.0005 && it <= 1.0005 }

    private fun oklch(lIn: Double, cIn: Double, h: Double): Ok {
        val l = max(0.0, min(1.0, lIn))
        var c = cIn
        var v = linear(l, c, h)
        while (!inGamut(v) && c > 0.001) {
            c *= 0.92
            v = linear(l, c, h)
        }
        val bytes = v.map { x ->
            val k = max(0.0, min(1.0, x))
            ((if (k <= 0.0031308) 12.92 * k else 1.055 * k.pow(1 / 2.4) - 0.055) * 255).roundToInt()
        }
        val argb = 0xFF000000L or (bytes[0].toLong() shl 16) or (bytes[1].toLong() shl 8) or bytes[2].toLong()
        return Ok(argb, luminance(bytes), l)
    }

    private fun luminance(bytes: List<Int>): Double {
        fun ch(n: Int): Double {
            val c = n / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(bytes[0]) + 0.7152 * ch(bytes[1]) + 0.0722 * ch(bytes[2])
    }

    private fun ratio(a: Double, b: Double) = (max(a, b) + 0.05) / (min(a, b) + 0.05)

    private fun solve(target: Double, ground: Ok, c: Double, h: Double, dark: Boolean): Ok {
        var lo = if (dark) ground.l else 0.0
        var hi = if (dark) 1.0 else ground.l
        var best = oklch(if (dark) 1.0 else 0.0, c, h)
        repeat(24) {
            val mid = (lo + hi) / 2
            val k = oklch(mid, c, h)
            val pass = ratio(k.y, ground.y) >= target
            if (dark == pass) hi = mid else lo = mid
            if (pass) best = k
        }
        return best
    }

    private fun prefer(target: Double, ground: Ok, l: Double, c: Double, h: Double, dark: Boolean): Ok {
        val k = oklch(l, c, h)
        return if (ratio(k.y, ground.y) >= target) k else solve(target, ground, c, h, dark)
    }

    private class Targets(
        val fg: Double, val fg2: Double, val muted: Double, val status: Double, val accent: Double,
        val border: Double, val control: Double, val strong: Double,
    )

    private val TARGETS = mapOf(
        ContrastLevel.SOFT to Targets(9.0, 6.0, 4.6, 4.6, 3.4, 1.2, 1.9, 3.1),
        ContrastLevel.STD to Targets(13.0, 8.0, 5.0, 4.8, 3.6, 1.3, 2.4, 3.4),
        ContrastLevel.HIGH to Targets(17.0, 11.0, 7.0, 7.0, 4.6, 1.6, 3.2, 4.6),
    )

    private const val DAY_H = 240.0
    private const val DAY_C = 0.006
    private const val GOLDEN_H = 228.0
    private const val GOLDEN_C = 0.01
    private const val BLUE_H = 250.0
    private const val BLUE_C = 0.034
    private const val NIGHT_H = 252.0
    private const val NIGHT_C = 0.015

    private fun alpha(argb: Long, a: Double): Long = ((a * 255).roundToInt().toLong() shl 24) or (argb and 0xFFFFFF)

    private const val WHITE = 0xFFFFFFFFL
    private const val DARK_INK = 0xFF0B0F17L

    /** Every token the engine owns, as ARGB by the manual's names, for one moment of the day. */
    fun colors(sun: Double, contrast: ContrastLevel, hue: Double): Map<String, Long> {
        val dark = sun < 0
        val t = TARGETS.getValue(contrast)
        val nh: Double
        val nc: Double
        val bgL: Double
        var ease = 1.0
        if (!dark) {
            val e = sqrt(min(1.0, sun))
            val w = 1 - e
            nh = DAY_H + (GOLDEN_H - DAY_H) * w
            nc = DAY_C + (GOLDEN_C - DAY_C) * w
            bgL = 0.952 + 0.033 * e
        } else {
            val d = min(1.0, -sun)
            val q = d.pow(0.6)
            nh = BLUE_H + (NIGHT_H - BLUE_H) * q
            nc = BLUE_C + (NIGHT_C - BLUE_C) * q
            bgL = 0.27 - 0.1 * q
            ease = 1 - 0.22 * d
        }
        val s = if (dark) 1 else -1
        fun g(dl: Double) = oklch(bgL + s * dl, nc, nh)
        val bg = g(0.0)
        val pane = oklch(min(1.0, bgL + if (dark) 0.025 else 0.02), nc, nh)
        val raise = g(0.04)
        val sunk = g(0.035)
        val chip = g(0.055)
        val hover = g(0.065)
        val count = g(0.06)
        val accentSoft = oklch(bgL + s * 0.075, if (dark) 0.045 else 0.035, hue)
        val hardest = g(0.085)

        val fg = solve(max(7.0, t.fg * ease), hardest, nc * 1.5, nh, dark)
        val fg2 = solve(max(5.5, t.fg2 * ease), hardest, nc * 2, nh, dark)
        val muted = solve(t.muted, hardest, nc * 2, nh, dark)
        val border = solve(t.border, pane, nc * 2, nh, dark)
        val control = solve(t.control, pane, nc * 2, nh, dark)
        val strong = solve(t.strong, hardest, nc * 2, nh, dark)
        val accent = prefer(t.accent, hardest, if (dark) 0.8 else 0.5, if (dark) 0.15 else 0.18, hue, dark)
        val accentFg = if (ratio(accent.y, 1.0) >= ratio(accent.y, 0.004)) WHITE else DARK_INK

        fun status(h: Double, c: Double) = prefer(t.status, hardest, if (dark) 0.79 else 0.5, c, h, dark)
        val working = status(262.0, 0.15)
        val waiting = status(70.0, 0.14)
        val failed = status(27.0, 0.17)
        val done = status(150.0, 0.14)
        fun syntax(h: Double) = prefer(4.6, sunk, if (dark) 0.78 else 0.5, 0.13, h, dark)
        val code = syntax(210.0)
        val onWaiting = if (ratio(waiting.y, 1.0) >= ratio(waiting.y, 0.004)) WHITE else 0xFF1A1205L

        return mapOf(
            "bg" to bg.hex,
            "bg-pane" to pane.hex,
            "bg-raise" to raise.hex,
            "bg-hover" to hover.hex,
            "bg-sunk" to sunk.hex,
            "fg" to fg.hex,
            "fg-2" to fg2.hex,
            "fg-muted" to muted.hex,
            "border" to border.hex,
            "control-border" to control.hex,
            "accent" to accent.hex,
            "accent-fg" to accentFg,
            "accent-soft" to accentSoft.hex,
            "ring" to accent.hex,
            "ai-pre" to accent.hex,
            "status-working" to working.hex,
            "status-waiting" to waiting.hex,
            "status-failed" to failed.hex,
            "status-done" to done.hex,
            "status-idle" to muted.hex,
            "danger" to failed.hex,
            "usage-ok" to done.hex,
            "usage-warn" to waiting.hex,
            "usage-crit" to failed.hex,
            "on-waiting" to onWaiting,
            "waiting-soft" to alpha(waiting.hex, 0.13),
            "waiting-faint" to alpha(waiting.hex, 0.07),
            "waiting-line" to alpha(waiting.hex, 0.45),
            "failed-soft" to alpha(failed.hex, 0.12),
            "failed-line" to alpha(failed.hex, 0.35),
            "done-soft" to alpha(done.hex, 0.12),
            "chip-bg" to chip.hex,
            "count-bg" to count.hex,
            "track" to g(0.09).hex,
            "org-1" to accent.hex,
            "control-bg" to (if (dark) raise.hex else pane.hex),
            "control-bg-hover" to hover.hex,
            "control-bg-active" to g(0.1).hex,
            "control-border-strong" to strong.hex,
            "control-fg" to fg.hex,
            "control-fg-quiet" to muted.hex,
            "code" to code.hex,
            "syn-code" to code.hex,
            "syn-kw" to syntax(315.0).hex,
            "syn-str" to syntax(135.0).hex,
            "syn-num" to syntax(62.0).hex,
        )
    }

    // ─── The sun ──────────────────────────────────────────────────────────

    /** Sunrise and sunset in local hours, or null for the polar day ([polarDay]) and night. */
    class SunTimes(val rise: Double, val set: Double)

    /**
     * The NOAA approximation, as the desktop computes it: [dayOfYear] 1–366,
     * [utcOffsetHours] the zone's offset right now (summer time included).
     * Returns null when the sun never rises or never sets; [polarDay] tells which.
     */
    fun sunTimes(dayOfYear: Int, utcOffsetHours: Double, lat: Double, lon: Double): SunTimes? {
        val rad = PI / 180
        val decl = -23.44 * cos(2 * PI / 365 * (dayOfYear + 10)) * rad
        val b = 2 * PI / 365 * (dayOfYear - 81)
        val eot = 9.87 * sin(2 * b) - 7.53 * cos(b) - 1.5 * sin(b)
        val cosW = (sin(-0.833 * rad) - sin(lat * rad) * sin(decl)) / (cos(lat * rad) * cos(decl))
        if (abs(cosW) >= 1) return null
        val w = acos(cosW) / rad / 15
        val noonUtc = 12 - lon / 15 - eot / 60
        return SunTimes(wrap(noonUtc - w + utcOffsetHours), wrap(noonUtc + w + utcOffsetHours))
    }

    fun polarDay(dayOfYear: Int, lat: Double): Boolean {
        val decl = -23.44 * cos(2 * PI / 365 * (dayOfYear + 10))
        return (lat >= 0) == (decl >= 0)
    }

    private fun wrap(h: Double) = ((h % 24) + 24) % 24

    /** -1 … 1: a sine over the day from sunrise to sunset, its negative over the night. */
    fun sunHeight(hour: Double, times: SunTimes): Double {
        fun span(a: Double, b: Double) = wrap(b - a)
        val dayLen = span(times.rise, times.set)
        val since = span(times.rise, hour)
        return if (since < dayLen) sin(PI * since / dayLen) else -sin(PI * (since - dayLen) / (24 - dayLen))
    }

    /**
     * The sun's height for a [LocalClock], without asking for a location: the
     * zone's standard meridian (15° per hour, summer time left out) at a
     * temperate latitude, as the desktop does.
     */
    fun sunNow(clock: LocalClock): Double {
        val lat = 48.0
        val lon = clock.standardOffsetHours * 15
        val hour = clock.hour + clock.minute / 60.0
        val times = sunTimes(clock.dayOfYear, clock.utcOffsetHours, lat, lon)
            ?: return if (polarDay(clock.dayOfYear, lat)) 1.0 else -1.0
        return sunHeight(hour, times)
    }

    /** Where Light and Dark sit on the sun's curve: noon, and a mid-night. */
    const val LIGHT_SUN = 1.0
    const val DARK_SUN = -0.6
}

/** The local wall clock and zone, which commonMain cannot read by itself. */
data class LocalClock(
    val dayOfYear: Int,
    val hour: Int,
    val minute: Int,
    /** The zone's UTC offset now, summer time included. */
    val utcOffsetHours: Double,
    /** The zone's UTC offset without summer time. */
    val standardOffsetHours: Double,
)

expect fun localClock(): LocalClock
