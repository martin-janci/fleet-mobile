package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A change an agent proposed for the hub applies only on a person's tap
 * (redesign 14.17). A source scan because a composable cannot be rendered
 * here: the Inbox card's decision is called from a button's `onClick` and
 * nowhere else, and App.kt hands it nothing but that callback.
 */
class ProposedChangeAsksTest {

    private val card by lazy { Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/OrbitOrgsScreen.kt").readLines() }

    @Test
    fun the_card_decides_only_from_a_buttons_click() {
        val calls = card.map { it.trim() }.filter { "onDecide(" in it && !it.startsWith("*") && !it.startsWith("//") }
        assertEquals(listOf("onClick = { onDecide(p.id, apply) },"), calls)
    }

    @Test
    fun neither_button_is_filled_or_pre_selected() {
        val body = card.joinToString("\n")
        val start = body.indexOf("fun ProposedChangeCard(")
        assertTrue(start >= 0, "the scan has gone stale")
        val fn = body.substring(start)
        assertTrue("listOf(\"Reject\" to false, \"Apply\" to true)" in fn, "Reject and Apply are drawn by one loop, alike")
        assertTrue("Button(" !in fn.replace("OutlinedButton(", ""), "a filled button would read as the default")
    }
}
