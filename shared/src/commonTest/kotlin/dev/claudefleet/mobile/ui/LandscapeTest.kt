package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.net.HubCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Landscape and full screen on the New bar (redesign 14.21, MobileFullscreen). */
class LandscapeTest {

    @Test
    fun a_phone_on_its_side_gets_two_panes_and_upright_never_does() {
        assertTrue(twoPaneWide(800f, 360f))
        assertFalse(twoPaneWide(360f, 800f))
        assertFalse(twoPaneWide(500f, 300f), "too narrow for two halves")
        assertFalse(twoPaneWide(900f, 1200f), "a tablet held upright keeps one column")
    }

    @Test
    fun the_right_pane_offers_every_tab_but_the_conversation() {
        val tabs = sessionTabs(hasWorktree = true, terminals = true)
        assertEquals(
            listOf(SessionTab.Agent, SessionTab.Terminals, SessionTab.Files, SessionTab.Details),
            sideTabs(tabs),
        )
    }

    @Test
    fun turning_the_phone_keeps_the_open_tab_or_shows_the_changes() {
        val withFiles = sessionTabs(hasWorktree = true)
        val without = sessionTabs(hasWorktree = false)
        assertEquals(SessionTab.Details, sideTabFor(SessionTab.Details, withFiles))
        assertEquals(SessionTab.Files, sideTabFor(SessionTab.Conversation, withFiles))
        assertEquals(SessionTab.Agent, sideTabFor(SessionTab.Conversation, without))
        assertEquals(SessionTab.Agent, sideTabFor(SessionTab.Files, without), "a tab this hub has not got is not kept")
    }

    @Test
    fun a_split_diff_sets_removals_against_additions_line_for_line() {
        val unified = """
            diff --git a/HostsScreen.kt b/HostsScreen.kt
            @@ -88,4 +88,6 @@
             @Composable
            -    Text(host.name)
            +    Row(verticalAlignment = Center) {
            +        StatusDot(host.reach)
             private fun HostRow(host: Host) {
            @@ -120,2 +122,1 @@
            -    gone()
            -    alsoGone()
            +    kept()
        """.trimIndent()
        val rows = splitDiffRows(diffLines(unified))

        assertEquals(LineKind.Meta, rows[0].across?.kind)
        assertEquals(LineKind.Hunk, rows[1].across?.kind)
        // An unchanged line on both sides, at its own number on each.
        assertEquals(88, rows[2].old?.old)
        assertEquals(88, rows[2].new?.new)
        // One removal against the first of two additions; the second stands alone.
        assertEquals("-    Text(host.name)", rows[3].old?.text)
        assertEquals("+    Row(verticalAlignment = Center) {", rows[3].new?.text)
        assertNull(rows[4].old)
        assertEquals("+        StatusDot(host.reach)", rows[4].new?.text)
        // The next unchanged line lines up again: 90 on the left, 91 on the right.
        assertEquals(90, rows[5].old?.old)
        assertEquals(91, rows[5].new?.new)
        // Two removals against one addition.
        assertEquals("-    gone()", rows[7].old?.text)
        assertEquals("+    kept()", rows[7].new?.text)
        assertEquals("-    alsoGone()", rows[8].old?.text)
        assertNull(rows[8].new)
        assertEquals(listOf(1, 6), splitHunkStarts(rows))
    }

    @Test
    fun the_key_bar_presses_keys_while_nothing_is_asked() {
        val keys = agentBarKeys(null)
        assertEquals(listOf("Esc", "Tab", "⏎", "⌃C", "1", "2", "3"), keys.map { it.label })
        assertEquals(
            listOf<AgentPress?>(
                AgentPress.Key("Escape"),
                AgentPress.Key("Tab"),
                AgentPress.Key("Enter"),
                AgentPress.Key("C-c"),
                null,
                null,
                null,
            ),
            keys.map { it.press },
        )
    }

    @Test
    fun the_key_bar_answers_a_question_through_its_card_and_only_with_its_answers() {
        val yes = Answer.Option(1, "Yes")
        val no = Answer.Option(2, "No, tell Claude what to do differently")
        val card = BlockedCard(headline = "Bash command", answers = listOf(yes, no, Answer.Enter, Answer.Escape))
        val keys = agentBarKeys(card).associate { it.label to it.press }

        assertEquals(AgentPress.Card(Answer.Escape), keys["Esc"])
        assertEquals(AgentPress.Card(Answer.Enter), keys["⏎"])
        assertEquals(AgentPress.Card(yes), keys["1"])
        assertEquals(AgentPress.Card(no), keys["2"])
        assertNull(keys["3"], "no third answer: nothing to press")
        assertNull(keys["Tab"], "Tab and ⌃C wait while a question is up")
        assertNull(keys["⌃C"])
    }

    @Test
    fun a_question_without_raw_keys_offers_no_enter_or_esc() {
        val card = BlockedCard(headline = "Trust this folder?", answers = listOf(Answer.Option(1, "Yes")))
        val keys = agentBarKeys(card).associate { it.label to it.press }
        assertNull(keys["Esc"])
        assertNull(keys["⏎"])
    }

    @Test
    fun a_hub_that_lists_the_arrows_and_ctrl_keys_gets_their_caps() {
        val listed = HubCapabilities.BASE_PANE_KEYS + setOf("BTab", "Left", "Up", "Down", "Right", "C-r", "C-u")
        val keys = agentBarKeys(null, listed)
        assertEquals(
            listOf("Esc", "Tab", "⏎", "⌃C", "1", "2", "3", "⇧Tab", "←", "↑", "↓", "→", "⌃"),
            keys.map { it.label },
        )
        val byLabel = keys.associate { it.label to it.press }
        assertEquals(AgentPress.Key("BTab"), byLabel["⇧Tab"])
        assertEquals(AgentPress.Key("Up"), byLabel["↑"])
        assertNull(byLabel["⌃"], "the ⌃ cap opens the letters' row; it presses nothing itself")
        assertEquals(listOf("⌃R", "⌃U"), ctrlBarKeys(null, listed).map { it.label })
        assertEquals(listOf(AgentPress.Key("C-r"), AgentPress.Key("C-u")), ctrlBarKeys(null, listed).map { it.press })
        assertEquals("Control R", ctrlBarKeys(null, listed).first().spoken)
    }

    @Test
    fun the_extra_keys_wait_while_a_question_is_up() {
        val listed = HubCapabilities.BASE_PANE_KEYS + setOf("BTab", "Up", "C-r")
        val card = BlockedCard(headline = "Bash command", answers = listOf(Answer.Option(1, "Yes"), Answer.Enter))
        val keys = agentBarKeys(card, listed).associate { it.label to it.press }
        assertNull(keys["⇧Tab"])
        assertNull(keys["↑"])
        assertTrue(ctrlBarKeys(card, listed).isEmpty(), "a Ctrl key drives the session; it answers nothing")
    }

    @Test
    fun an_older_hub_gets_only_the_four_keys() {
        assertEquals(listOf("Esc", "Tab", "⏎", "⌃C", "1", "2", "3"), agentBarKeys(null, HubCapabilities.BASE_PANE_KEYS).map { it.label })
        assertTrue(ctrlBarKeys(null, HubCapabilities.BASE_PANE_KEYS).isEmpty())
    }

    /** G7.16: ⌥ and its row of Alt chords, only on a hub whose `keys` enum names one (G7.3). */
    @Test
    fun a_hub_that_lists_alt_chords_gets_the_alt_cap_and_row() {
        val listed = HubCapabilities.BASE_PANE_KEYS + setOf("C-r", "M-b", "M-f", "M-Enter", "M-BSpace", "M-z")
        val keys = agentBarKeys(null, listed)
        assertEquals(listOf("⌃", "⌥"), keys.map { it.label }.takeLast(2))
        assertNull(keys.last().press, "the ⌥ cap opens the chords' row; it presses nothing itself")
        val row = altBarKeys(null, listed)
        assertEquals(listOf("⌥B", "⌥F", "⌥⌫", "⌥⏎"), row.map { it.label }, "in the bar's order; a chord the bar has no cap for is left out")
        assertEquals(AgentPress.Key("M-Enter"), row.last().press)
        assertEquals(listOf("Alt B", "Alt Backspace", "Alt Enter"), listOf(row[0], row[2], row[3]).map { it.spoken })
        assertEquals(row, modifierRowKeys("⌥", null, listed))
        assertEquals(ctrlBarKeys(null, listed), modifierRowKeys("⌃", null, listed))
        assertTrue(modifierRowKeys(null, null, listed).isEmpty())
        val card = BlockedCard(headline = "Bash command", answers = listOf(Answer.Enter))
        assertTrue(altBarKeys(card, listed).isEmpty(), "an Alt chord drives the session; it answers nothing")
        assertTrue(altBarKeys(null, HubCapabilities.BASE_PANE_KEYS).isEmpty(), "an older hub lists no Meta chord")
    }
}
