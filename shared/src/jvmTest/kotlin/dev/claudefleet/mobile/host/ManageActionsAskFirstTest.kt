package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The session menu's actions that end or replace a session ask first.
 *
 * *Restart* and *Kill now* always went through a dialog; *Safe remove*
 * (`safe_kill_session`) fired on the tap, although it ends the session too —
 * just later, once its work is saved. A source scan because a composable
 * cannot be rendered here: what it pins is that the menu item opens a
 * question and the question's own button is what calls the action.
 */
class ManageActionsAskFirstTest {

    private val screen by lazy { Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionScreen.kt").readText() }

    /** The `onClick = { … }` of the menu item labelled [label]. */
    private fun menuClick(label: String): String {
        val at = screen.indexOf("text = { Text(\"$label\"")
        if (at < 0) fail("no menu item labelled \"$label\" in SessionScreen.kt")
        return ON_CLICK.find(screen, at)?.groupValues?.get(1) ?: fail("no onClick after \"$label\"")
    }

    @Test
    fun every_ending_action_opens_a_question_rather_than_calling() {
        val direct = mapOf(
            "Restart" to "onRestart",
            "Safe remove" to "onSafeKill",
        ).filter { (label, call) -> call in menuClick(label) }

        assertEquals(emptyMap(), direct, "a menu item that ends the session without asking")
        assertTrue("showSafeKillConfirm = true" in menuClick("Safe remove"))
    }

    @Test
    fun the_retire_question_is_what_retires() {
        assertTrue(
            Regex("""if \(showSafeKillConfirm\) \{[\s\S]*?onSafeKill\(\)""").containsMatchIn(screen),
            "the question's confirm button calls onSafeKill",
        )
    }

    private companion object {
        val ON_CLICK = Regex("""onClick = \{([^}]*)\}""")
    }
}
