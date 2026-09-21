package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelativeTimeTest {
    private val now = 1_790_000_000L

    @Test
    fun buckets() {
        assertNull(relativeTime(null, now))
        assertEquals("just now", relativeTime(now - 20, now))
        assertEquals("4 min", relativeTime(now - 4 * 60, now))
        assertEquals("2 h", relativeTime(now - 2 * 3600, now))
        assertEquals("3 d", relativeTime(now - 3 * 86_400, now))
        assertEquals("just now", relativeTime(now + 30, now), "a clock ahead of the hub is not the future")
    }
}
