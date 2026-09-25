package dev.claudefleet.mobile.ui.theme

// The spiral loader's geometry and timing, ported from the desktop app's
// `src/lib/spiral.ts` — which is itself a port of a pair of Lottie animations
// ("spiral2" fast, "spiral1" slow), so neither end needs a Lottie runtime.
//
// One looped squiggle slides left by exactly one period while a trimmed window
// of its stroke runs along it; the fast clip plays FAST_REPEATS times, the slow
// one SLOW_REPEATS times, forever.
//
// Everything in this file is pure. That is deliberate: it makes the animation
// checkable frame by frame in `SpiralTest` against the same keyframes the
// Svelte side asserts, rather than by looking at a device.

import androidx.compose.ui.graphics.Path

/** The Lottie shape's vertices, verbatim: x -12..12, y -6..6, centred on the origin. */
internal val SPIRAL_VERTICES: List<Pair<Float, Float>> = listOf(
    -12f to 6f, -4.975f to -1.012f, -8f to -6f, -11.025f to -1.012f, -4f to 6f,
    3.025f to -1.012f, 0f to -6f, -3.025f to -1.012f, 4f to 6f, 11.025f to -1.012f,
    8f to -6f, 4.98f to -1.012f, 12f to 6f,
)

/** Each vertex's incoming tangent, relative to the vertex. */
internal val SPIRAL_IN_TANGENTS: List<Pair<Float, Float>> = listOf(
    0f to 0f, -0.289f to 3.296f, 2.218f to 0f, -0.23f to -2.627f, -4.452f to 0f,
    -0.289f to 3.296f, 2.218f to 0f, -0.23f to -2.627f, -4.452f to 0f,
    -0.289f to 3.296f, 2.218f to 0f, -0.232f to -2.627f, -4.443f to 0f,
)

/** Each vertex's outgoing tangent, relative to the vertex. */
internal val SPIRAL_OUT_TANGENTS: List<Pair<Float, Float>> = listOf(
    4.452f to 0f, 0.23f to -2.627f, -2.218f to 0f, 0.289f to 3.296f, 4.452f to 0f,
    0.23f to -2.627f, -2.218f to 0f, 0.289f to 3.296f, 4.452f to 0f,
    0.23f to -2.627f, -2.218f to 0f, 0.292f to 3.296f, 0f to 0f,
)

/**
 * The squiggle as a [Path], centred on the origin — thirteen vertices joined by
 * twelve cubics. Built once: the shape never changes, only the window of it
 * that is drawn and where it sits horizontally.
 */
internal val SPIRAL_PATH: Path by lazy {
    Path().apply {
        val (x0, y0) = SPIRAL_VERTICES[0]
        moveTo(x0, y0)
        for (k in 0 until SPIRAL_VERTICES.size - 1) {
            val (vx, vy) = SPIRAL_VERTICES[k]
            val (ox, oy) = SPIRAL_OUT_TANGENTS[k]
            val (nx, ny) = SPIRAL_VERTICES[k + 1]
            val (ix, iy) = SPIRAL_IN_TANGENTS[k + 1]
            cubicTo(vx + ox, vy + oy, nx + ix, ny + iy, nx, ny)
        }
    }
}

/** CSS `cubic-bezier(x1, y1, x2, y2)` evaluated at progress [t] (0..1). */
internal fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float, t: Float): Float {
    if (t <= 0f) return 0f
    if (t >= 1f) return 1f
    fun bx(s: Float) = 3f * (1 - s) * (1 - s) * s * x1 + 3f * (1 - s) * s * s * x2 + s * s * s
    fun by(s: Float) = 3f * (1 - s) * (1 - s) * s * y1 + 3f * (1 - s) * s * s * y2 + s * s * s
    // Bisection on x(s) = t: monotone for x1, x2 in [0, 1], and 24 halvings is
    // well past a pixel at any size this is drawn.
    var lo = 0f
    var hi = 1f
    repeat(24) {
        val mid = (lo + hi) / 2f
        if (bx(mid) < t) lo = mid else hi = mid
    }
    return by((lo + hi) / 2f)
}

private class Clip(
    /** Clip length in ms (Lottie: frames at 60 fps). */
    val ms: Float,
    /** Last keyframe as a fraction of the clip (keyed at op - 1). */
    val last: Float,
    val startEase: FloatArray,
    val endEase: FloatArray,
)

private val FAST = Clip(
    ms = 500f,
    last = 29f / 30f,
    startEase = floatArrayOf(0.32f, 0.154f, 0.826f, 0.579f),
    endEase = floatArrayOf(0.341f, 0.488f, 0.269f, 0.75f),
)
private val SLOW = Clip(
    ms = 1000f,
    last = 59f / 60f,
    startEase = floatArrayOf(0.32f, 0.313f, 0.826f, 0.143f),
    endEase = floatArrayOf(0.341f, 0.992f, 0.269f, 0.491f),
)
private val SLIDE = floatArrayOf(0.167f, 0.167f, 0.833f, 0.833f)

internal const val FAST_REPEATS = 4
internal const val SLOW_REPEATS = 2

/** One full fast-then-slow cycle, in ms. */
internal const val SPIRAL_CYCLE_MS: Int = FAST_REPEATS * 500 + SLOW_REPEATS * 1000

internal enum class SpiralPhase { FAST, SLOW }

internal data class SpiralFrame(
    val phase: SpiralPhase,
    /** Horizontal offset of the squiggle inside the 16x16 box (12 -> 4). */
    val x: Float,
    /** Visible window of the stroke, as percentages of its length. */
    val start: Float,
    val end: Float,
)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun ease(e: FloatArray, t: Float) = cubicBezier(e[0], e[1], e[2], e[3], t)

/**
 * The frame shown [ms] milliseconds into the loop.
 *
 * [ms] is wrapped into the cycle, so it takes a free-running clock — including
 * a negative one — rather than a value the caller has to normalise.
 */
internal fun spiralFrame(ms: Float): SpiralFrame {
    val cycle = SPIRAL_CYCLE_MS.toFloat()
    val t = ((ms % cycle) + cycle) % cycle
    val fastSpan = FAST_REPEATS * FAST.ms
    val phase = if (t < fastSpan) SpiralPhase.FAST else SpiralPhase.SLOW
    val clip = if (phase == SpiralPhase.FAST) FAST else SLOW
    val local = if (phase == SpiralPhase.FAST) t % FAST.ms else (t - fastSpan) % SLOW.ms
    val p = minOf(1f, local / clip.ms / clip.last)
    return SpiralFrame(
        phase = phase,
        x = lerp(12f, 4f, ease(SLIDE, p)),
        start = lerp(23f, 57f, ease(clip.startEase, p)),
        end = lerp(44f, 77f, ease(clip.endEase, p)),
    )
}
