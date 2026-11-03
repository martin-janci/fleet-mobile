package dev.claudefleet.mobile.ui

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CatchingTest {
    /** A failed read is null, as `runCatching { }.getOrNull()`; a cancellation is not swallowed (hardening review 2026-11-03). */
    @Test
    fun a_failure_is_null_and_a_cancellation_goes_on_up() {
        assertEquals(3, orNullUnlessCancelled { 3 })
        assertNull(orNullUnlessCancelled<Int> { error("hub down") })
        assertFailsWith<CancellationException> { orNullUnlessCancelled<Int> { throw CancellationException("screen gone") } }
    }
}
