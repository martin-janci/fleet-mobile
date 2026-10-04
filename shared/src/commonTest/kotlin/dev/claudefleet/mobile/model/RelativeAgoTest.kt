package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** "… ago" and "within …" as phrases: never "just now ago", never "within just now". */
class RelativeAgoTest {

    @Test
    fun ago_reads_as_a_phrase() {
        assertEquals("just now", relativeAgo(1_000, 1_030))
        assertEquals("5 min ago", relativeAgo(1_000, 1_300))
        assertEquals(null, relativeAgo(null, 1_000))
    }

    @Test
    fun within_never_says_just_now() {
        assertEquals("under a minute", relativeWithin(1_030, 1_000))
        assertEquals("under a minute", relativeWithin(900, 1_000))
        assertEquals("5 min", relativeWithin(1_300, 1_000))
    }
}
