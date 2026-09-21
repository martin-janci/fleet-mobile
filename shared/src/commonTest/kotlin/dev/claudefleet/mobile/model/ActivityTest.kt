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

    /**
     * The ANSI pattern is anchored on the ESC. Unanchored it was "[" plus a
     * terminating byte, which is an ordinary square bracket followed by an
     * ordinary letter — so it ate the app's own text wherever a bracket
     * appeared, and brackets are everywhere a session says anything: branch
     * names, task counters, y/n prompts.
     */
    @Test
    fun a_square_bracket_is_not_an_escape_sequence() {
        assertEquals(
            "Committing to [main]",
            Activity.sanitize("Committing to \u001B[1m[main]\u001B[0m"),
        )
        assertEquals("Running task [1] of 3", Activity.sanitize("Running task [1] of 3"))
        assertEquals("[y/n]?", Activity.sanitize("waiting for input: [y/n]?"))
        assertEquals("[y/n]?", Activity.pending("waiting for input: [y/n]?"))
    }

    /**
     * `for agents` is chrome only in the REPL footer it came from — a
     * `·`-separated strip with a `←` in it. On its own it is a session saying
     * what it is doing, and dropping it blanked the row at the one moment it
     * had something to say.
     */
    @Test
    fun waiting_on_agents_is_activity_but_the_footer_is_not() {
        assertEquals("waiting for agents to finish", Activity.sanitize("waiting for agents to finish"))
        assertNull(Activity.sanitize("\u23f5\u23f5 on \u00b7 \u2190 for agents"))
    }

    /** The prompt glyph is drawn by the REPL; it is not part of what the agent said. */
    @Test
    fun a_leading_prompt_glyph_is_stripped_from_what_is_shown() {
        assertEquals("Reading a.kt", Activity.sanitize("\u276f Reading a.kt"))
        assertEquals("Reading a.kt", Activity.sanitize("\u203a Reading a.kt"))
    }
}
