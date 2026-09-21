package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityTest {
    @Test
    fun repl_chrome_is_dropped_entirely() {
        assertNull(Activity.sanitize("⏵⏵ bypass permissions on (shift+tab to cycle) · ← 3 agents"))
        assertNull(Activity.sanitize("⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents"))
        assertNull(Activity.sanitize("❯"))
        assertNull(Activity.sanitize("❯ "))
        assertNull(Activity.sanitize("? for shortcuts"))
        assertNull(Activity.sanitize("esc to interrupt"))
        assertNull(Activity.sanitize(null))
        assertNull(Activity.sanitize("   "))
    }

    @Test
    fun ansi_and_mouse_report_residue_is_stripped() {
        assertNull(Activity.sanitize("❯ 0;16;27M0;16;27m"))
        assertEquals("Running tests", Activity.sanitize("\u001B[32mRunning tests\u001B[0m"))
        assertEquals("build ok", Activity.sanitize(" build ok "))
        assertEquals("tail", Activity.sanitize("\u0007tail\u0000"))
    }

    @Test
    fun a_waiting_line_keeps_the_question_and_drops_the_prefix() {
        assertEquals(
            "☐ Recreate turanga?",
            Activity.sanitize("waiting for input: ☐ Recreate turanga?"),
        )
        assertEquals(
            "Do you want to make this edit?",
            Activity.sanitize("waiting for permission: Do you want to make this edit?"),
        )
        assertEquals("☐ Recreate turanga?", Activity.pending("waiting for input: ☐ Recreate turanga?"))
        assertNull(Activity.pending("Running tests"))
        assertNull(Activity.pending("waiting for input"))
    }

    @Test
    fun ordinary_activity_is_kept_trimmed() {
        assertEquals("Reading src/main.rs", Activity.sanitize("  Reading src/main.rs  "))
    }
}
