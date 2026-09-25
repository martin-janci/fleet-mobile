package dev.claudefleet.mobile.ui.theme

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spiral's geometry and timing, checked against the same keyframes the
 * Svelte original asserts (`claude-fleet` `src/lib/spiral.test.ts`). Both ends
 * are ports of one pair of Lottie clips, so the numbers here are the contract
 * between them: if a frame drifts, the two apps no longer show the same
 * animation.
 *
 * Everything under test is pure, which is the point — the animation is
 * verifiable without a device, a screenshot or a running frame clock.
 */
class SpiralTest {
    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 1e-3f) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "expected $expected but was $actual (tolerance $tolerance)",
        )
    }

    @Test
    fun cubicBezierPinsItsEnds() {
        assertEquals(0f, cubicBezier(0.3f, 0.1f, 0.7f, 0.9f, 0f))
        assertEquals(1f, cubicBezier(0.3f, 0.1f, 0.7f, 0.9f, 1f))
    }

    @Test
    fun cubicBezierIsTheIdentityOnTheDiagonal() {
        assertClose(0.4f, cubicBezier(0.25f, 0.25f, 0.75f, 0.75f, 0.4f), 1e-4f)
    }

    @Test
    fun startsOnTheLottieFirstKeyframe() {
        val f = spiralFrame(0f)
        assertEquals(SpiralPhase.FAST, f.phase)
        assertEquals(12f, f.x)
        assertEquals(23f, f.start)
        assertEquals(44f, f.end)
    }

    @Test
    fun reachesTheLastKeyframeAtTheEndOfAFastClip() {
        val f = spiralFrame(499f)
        assertEquals(SpiralPhase.FAST, f.phase)
        assertClose(4f, f.x)
        assertClose(57f, f.start)
        assertClose(77f, f.end)
    }

    @Test
    fun playsFourFastClipsThenTwoSlowOnesThenLoops() {
        assertEquals(4000, SPIRAL_CYCLE_MS)
        assertEquals(SpiralPhase.FAST, spiralFrame(1999f).phase)
        assertEquals(SpiralPhase.SLOW, spiralFrame(2000f).phase)
        assertEquals(SpiralPhase.SLOW, spiralFrame(3999f).phase)
        assertEquals(spiralFrame(0f), spiralFrame(4000f))
    }

    @Test
    fun aSlowClipSlidesTheSquiggleHomeOverAFullSecond() {
        // The first slow clip ends 3 s in, by which point the squiggle has slid
        // all the way home; halfway through it is still out to the right.
        assertClose(4f, spiralFrame(2999f).x)
        assertTrue(spiralFrame(2500f).x > 5f, "was ${spiralFrame(2500f).x}")
    }

    @Test
    fun keepsTheVisibleWindowInsideTheStroke() {
        var t = 0f
        while (t < SPIRAL_CYCLE_MS) {
            val f = spiralFrame(t)
            assertTrue(f.start >= 23f - 1e-4f, "start ${f.start} at $t")
            assertTrue(f.end <= 77f + 1e-4f, "end ${f.end} at $t")
            assertTrue(f.end > f.start, "window ${f.start}..${f.end} at $t")
            t += 37f
        }
    }

    @Test
    fun aNegativeOrOverlongClockWrapsIntoTheCycle() {
        // `withInfiniteAnimationFrameMillis` hands out a clock that neither
        // starts at zero nor stops at the cycle length.
        assertEquals(spiralFrame(250f), spiralFrame(250f + 3 * SPIRAL_CYCLE_MS))
        assertEquals(spiralFrame(250f), spiralFrame(250f - SPIRAL_CYCLE_MS))
    }

    @Test
    fun theSquiggleIsOneOpenRunThroughAllThirteenVertices() {
        // 13 vertices joined by 12 cubics, spanning x -12..12 and y -6..6 —
        // the box `SpiralLoader` translates and clips.
        assertEquals(13, SPIRAL_VERTICES.size)
        assertEquals(-12f to 6f, SPIRAL_VERTICES.first())
        assertEquals(12f to 6f, SPIRAL_VERTICES.last())
        assertEquals(SPIRAL_VERTICES.size, SPIRAL_IN_TANGENTS.size)
        assertEquals(SPIRAL_VERTICES.size, SPIRAL_OUT_TANGENTS.size)
    }
}
