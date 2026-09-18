package dev.claudefleet.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolchainTest {
    @Test
    fun the_shared_module_compiles_and_tests_run() {
        assertEquals(4, 2 + 2)
    }

    /**
     * Was `assertEquals("fleet-mobile", greeting())`. `greeting()` was Task 1's
     * placeholder copy, kept "until the app has real screens"; it has them now,
     * so it is gone rather than left as dead code with a test defending it.
     *
     * `platformName()` proves the same thing — common code reaching an
     * `expect`/`actual` pair — and the assertion is a membership test because
     * `commonTest` runs on every target.
     */
    @Test
    fun shared_code_is_reachable_from_common_tests() {
        assertTrue(platformName() in setOf("Android", "iOS", "JVM"), platformName())
    }
}
