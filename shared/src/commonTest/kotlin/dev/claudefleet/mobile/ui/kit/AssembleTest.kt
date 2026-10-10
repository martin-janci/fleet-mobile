package dev.claudefleet.mobile.ui.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Assemble's timeline (gap plan G5.9, the manual's Startup at 1.3 s): in, held on the ring, out. */
class AssembleTest {

    @Test
    fun a_particle_flies_in_holds_and_streams_on() {
        val start = assembleAt(0, 22, 0f)
        assertEquals(0, start.leg)
        assertEquals(0f, start.alpha, "it starts unseen at the edge")
        assertEquals(AssembleStep(1, 1f, 1f), assembleAt(0, 22, 0.6f), "held on the ring between 45 % and 72 %")
        val out = assembleAt(0, 22, 0.99f)
        assertEquals(2, out.leg)
        assertTrue(out.alpha < 0.1f, "gone by the end of the loop")
    }

    @Test
    fun later_particles_arrive_a_little_after_the_first() {
        val first = assembleAt(0, 22, 0.3f)
        val last = assembleAt(21, 22, 0.3f)
        assertEquals(0, last.leg)
        assertTrue(last.k < first.k, "a stream, not one jump")
    }
}
