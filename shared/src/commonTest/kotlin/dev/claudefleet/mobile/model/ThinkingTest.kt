package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** The line under a working session's newest turn (MobileSession: "Thinking · reading HostsViewModel.kt"). */
class ThinkingTest {

    private fun turn(vararg items: ConvItem) = ConvTurn(prompt = "Run the tests", items = items.toList())

    @Test
    fun a_turn_with_nothing_read_last_is_just_thinking() {
        assertEquals("Thinking", thinkingLine(null))
        assertEquals("Thinking", thinkingLine(turn()))
        assertEquals("Thinking", thinkingLine(turn(ConvItem.Text("Updating the test to match."))))
        assertEquals("Thinking", thinkingLine(turn(ConvItem.Tool(summary = "Bash(ls)", name = "Bash", target = "ls"))))
    }

    @Test
    fun a_read_last_names_the_file_alone() {
        val read = ConvItem.Tool(summary = "Read", name = "Read", target = "/home/me/p/shared/ui/HostsViewModel.kt")
        assertEquals("Thinking · reading HostsViewModel.kt", thinkingLine(turn(ConvItem.Text("One failure."), read)))
    }

    @Test
    fun a_search_last_names_its_pattern() {
        val grep = ConvItem.Tool(summary = "Grep", name = "Grep", target = "lastPing")
        assertEquals("Thinking · searching lastPing", thinkingLine(turn(grep)))
    }

    @Test
    fun text_after_the_read_means_it_is_no_longer_reading() {
        val read = ConvItem.Tool(summary = "Read", name = "Read", target = "/a/B.kt")
        assertEquals("Thinking", thinkingLine(turn(read, ConvItem.Text("Found it."))))
    }

    @Test
    fun an_older_hubs_one_liner_still_reads() {
        val read = ConvItem.Tool(summary = "Read(file_path=/srv/app/Main.kt)")
        assertEquals("Thinking · reading Main.kt", thinkingLine(turn(read)))
    }

    @Test
    fun a_read_with_no_target_or_a_long_pattern_stays_one_short_line() {
        assertEquals("Thinking", thinkingLine(turn(ConvItem.Tool(summary = "Read", name = "Read", target = null))))
        val long = "x".repeat(80)
        val line = thinkingLine(turn(ConvItem.Tool(summary = "Grep", name = "Grep", target = long)))
        assertEquals("Thinking · searching " + "x".repeat(39) + "…", line)
    }
}
