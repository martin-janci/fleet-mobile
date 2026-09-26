package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FakeQuickReplyActions
import dev.claudefleet.mobile.model.QuickReply
import dev.claudefleet.mobile.store.FakePrefs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun chip(text: String, label: String = text) = QuickReply(label = label, text = text)

class QuickRepliesTest {

    @Test
    fun a_first_run_with_no_cache_draws_nothing_until_the_hub_answers() = runTest {
        val hub = FakeQuickReplyActions(held = listOf(chip("go on")))
        val qr = QuickReplies(FakePrefs(), hub)

        assertEquals(emptyList(), qr.chips.value, "no cache yet")
        qr.refresh()
        assertEquals(listOf(chip("go on")), qr.chips.value)
    }

    @Test
    fun the_served_list_is_cached_so_the_next_launch_draws_it_at_once() = runTest {
        val prefs = FakePrefs()
        QuickReplies(prefs, FakeQuickReplyActions(held = listOf(chip("/clear", label = "Clear")))).refresh()

        val next = QuickReplies(prefs, FakeQuickReplyActions(held = emptyList()))

        assertEquals(listOf(chip("/clear", label = "Clear")), next.chips.value)
    }

    @Test
    fun a_device_upgrading_from_the_bare_string_cache_keeps_its_chips() = runTest {
        // What the pre-hub build wrote under the same key: plain prompts.
        val prefs = FakePrefs()
        prefs.putStringList("quick_reply_chips", listOf("go on", "run the tests"))

        val qr = QuickReplies(prefs, FakeQuickReplyActions())

        assertEquals(listOf(chip("go on"), chip("run the tests")), qr.chips.value)
    }

    @Test
    fun a_failed_read_leaves_the_cached_row_alone() = runTest {
        val prefs = FakePrefs()
        QuickReplies(prefs, FakeQuickReplyActions(held = listOf(chip("go on")))).refresh()

        val offline = QuickReplies(prefs, FakeQuickReplyActions(fail = true))
        assertFailsWith<IllegalStateException> { offline.refresh() }

        assertEquals(listOf(chip("go on")), offline.chips.value)
    }

    @Test
    fun add_sends_the_whole_list_and_takes_the_hub_answer_back() = runTest {
        val hub = FakeQuickReplyActions(held = listOf(chip("go on")))
        val qr = QuickReplies(FakePrefs(), hub)
        qr.refresh()

        qr.add(QuickReply(label = "Tests", text = "run the tests"))

        assertEquals(listOf(chip("go on"), chip("run the tests", label = "Tests")), hub.held)
        assertEquals(hub.held, qr.chips.value)
    }

    @Test
    fun add_ignores_a_blank_or_duplicate_chip_without_calling_the_hub() = runTest {
        val hub = FakeQuickReplyActions(held = listOf(chip("yes")))
        val qr = QuickReplies(FakePrefs(), hub)
        qr.refresh()

        qr.add(QuickReply(label = "", text = "   "))
        qr.add(QuickReply(label = "Yes", text = "yes"))

        assertEquals(0, hub.writes)
        assertEquals(listOf(chip("yes")), qr.chips.value)
    }

    @Test
    fun remove_drops_the_chip_from_the_fleets_list() = runTest {
        val hub = FakeQuickReplyActions(held = listOf(chip("yes"), chip("go on")))
        val qr = QuickReplies(FakePrefs(), hub)
        qr.refresh()

        qr.remove(chip("yes"))

        assertEquals(listOf(chip("go on")), hub.held)
    }

    @Test
    fun an_edit_keeps_the_chips_place_in_the_row() = runTest {
        val hub = FakeQuickReplyActions(held = listOf(chip("yes"), chip("go on"), chip("/clear")))
        val qr = QuickReplies(FakePrefs(), hub)
        qr.refresh()

        qr.replace(chip("go on"), QuickReply(label = "Carry on", text = "carry on"))

        assertEquals(
            listOf(chip("yes"), chip("carry on", label = "Carry on"), chip("/clear")),
            hub.held,
            "an edited chip must not jump to the end of the row",
        )
    }

    @Test
    fun a_failed_write_puts_the_row_back_and_says_so() = runTest {
        val hub = FakeQuickReplyActions(held = listOf(chip("yes")))
        val qr = QuickReplies(FakePrefs(), hub)
        qr.refresh()
        hub.fail = true

        assertFailsWith<IllegalStateException> { qr.remove(chip("yes")) }

        assertEquals(listOf(chip("yes")), qr.chips.value, "the row shows what the fleet still holds")
    }

    @Test
    fun remember_keeps_20_most_recent_first() {
        val qr = QuickReplies(FakePrefs(), FakeQuickReplyActions())

        for (i in 1..25) qr.remember("msg $i")
        val history = qr.history()

        assertEquals(20, history.size)
        assertEquals("msg 25", history.first(), "most recent first")
        assertEquals("msg 6", history.last(), "capped at 20 -- the oldest five fell off")
    }

    @Test
    fun remember_moves_a_repeat_to_the_front_without_duplicating_it() {
        val qr = QuickReplies(FakePrefs(), FakeQuickReplyActions())

        qr.remember("a")
        qr.remember("b")
        qr.remember("c")
        qr.remember("a")

        assertEquals(listOf("a", "c", "b"), qr.history())
    }

    @Test
    fun remember_ignores_a_blank_prompt() {
        val qr = QuickReplies(FakePrefs(), FakeQuickReplyActions())

        qr.remember("   ")

        assertTrue(qr.history().isEmpty())
    }

    @Test
    fun the_history_never_reaches_the_hub() = runTest {
        // It is what this phone typed, not fleet state — see the class doc.
        val hub = FakeQuickReplyActions()
        val qr = QuickReplies(FakePrefs(), hub)

        qr.remember("something private")

        assertEquals(0, hub.writes)
    }
}
