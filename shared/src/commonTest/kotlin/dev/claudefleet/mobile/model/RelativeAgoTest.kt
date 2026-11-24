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
        assertEquals("under a minute", relativeWithin(1_001, 1_000))
        assertEquals("5 min", relativeWithin(1_300, 1_000))
        // w07-8 / w15-2: a wait is not floored to one unit.
        assertEquals("1 h 59 min", relativeWithin(1_000 + 119 * 60, 1_000))
        assertEquals("2 h", relativeWithin(1_000 + 7_200, 1_000))
        assertEquals("1 d 23 h", relativeWithin(1_000 + 47 * 3600, 1_000))
        assertEquals("6 d 23 h", relativeWithin(1_000 + 167 * 3600 + 59, 1_000))
        assertEquals("2 d", relativeWithin(1_000 + 2 * 86_400, 1_000))
    }

    @Test
    fun within_a_passed_deadline_names_no_time_left() {
        assertEquals(null, relativeWithin(1_000, 1_000))
        assertEquals(null, relativeWithin(900, 1_000))
        assertEquals(null, relativeWithin(1_000 - 86_400, 1_000))
        assertEquals(null, relativeWithin(null, 1_000))
    }
}
