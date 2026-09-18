package dev.claudefleet.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolchainTest {
    @Test
    fun the_shared_module_compiles_and_tests_run() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun shared_code_is_reachable_from_common_tests() {
        assertEquals("fleet-mobile", greeting())
    }
}
