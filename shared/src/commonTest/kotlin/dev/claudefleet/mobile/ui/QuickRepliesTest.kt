package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.store.FakePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuickRepliesTest {

    @Test
    fun defaults_are_present_on_first_run() {
        val qr = QuickReplies(FakePrefs())

        assertEquals(
            listOf("go on", "yes", "run the tests", "commit and push", "/clear", "/compact"),
            qr.chips.value,
        )
    }

    @Test
    fun add_persists_across_a_new_instance_over_the_same_fake_prefs() {
        val prefs = FakePrefs()
        val first = QuickReplies(prefs)
        first.add("ship it")

        val second = QuickReplies(prefs)

        assertEquals(QuickReplies.DEFAULT_CHIPS + "ship it", second.chips.value)
    }

    @Test
    fun remove_also_persists_across_a_new_instance() {
        val prefs = FakePrefs()
        val first = QuickReplies(prefs)
        first.remove("yes")

        val second = QuickReplies(prefs)

        assertEquals(QuickReplies.DEFAULT_CHIPS - "yes", second.chips.value)
    }

    @Test
    fun add_ignores_a_blank_or_duplicate_chip() {
        val qr = QuickReplies(FakePrefs())
        val before = qr.chips.value

        qr.add("   ")
        qr.add("yes")

        assertEquals(before, qr.chips.value)
    }

    @Test
    fun remember_keeps_20_most_recent_first() {
        val qr = QuickReplies(FakePrefs())

        for (i in 1..25) qr.remember("msg $i")
        val history = qr.history()

        assertEquals(20, history.size)
        assertEquals("msg 25", history.first(), "most recent first")
        assertEquals("msg 6", history.last(), "capped at 20 -- the oldest five fell off")
    }

    @Test
    fun remember_moves_a_repeat_to_the_front_without_duplicating_it() {
        val qr = QuickReplies(FakePrefs())

        qr.remember("a")
        qr.remember("b")
        qr.remember("c")
        qr.remember("a")

        assertEquals(listOf("a", "c", "b"), qr.history())
    }

    @Test
    fun remember_ignores_a_blank_prompt() {
        val qr = QuickReplies(FakePrefs())

        qr.remember("   ")

        assertTrue(qr.history().isEmpty())
    }
}
