package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tutorials never act for the person (redesign 14.22), and the practice
 * fleet never reaches the hub.
 *
 * A source scan, because what it guards is what the help code *can* reach:
 * everything that talks to the hub lives in `net/` and `data/`, so help that
 * imports neither cannot answer a permission, send a prompt or start a
 * session, whatever a later edit makes its buttons do. A guide changes real
 * settings, as on the desktop, only through the `onSet` the settings screen
 * already uses, which is handed in from `App.kt`.
 */
class HelpNeverActsTest {

    private val help by lazy {
        Repo.shipped.filter { "/ui/help/" in it.path.replace('\\', '/') }
    }

    @Test
    fun the_help_code_imports_nothing_that_reaches_the_hub() {
        assertTrue(help.map { it.name }.containsAll(listOf("Help.kt", "Practice.kt", "HelpScreens.kt")), "the scan has gone stale")
        val offenders = help.filter { f ->
            f.readLines().any { it.startsWith("import dev.claudefleet.mobile.net.") || it.startsWith("import dev.claudefleet.mobile.data.") }
        }.map { it.name }
        assertEquals(emptyList(), offenders, "help code that can reach the hub could answer for the person")
    }

    /** The practice fleet's state changes only on a tap: its answer is called from a click and nowhere else. */
    @Test
    fun the_practice_answer_is_only_ever_a_tap() {
        val callers = Repo.shipped.flatMap { f -> f.readLines().filter { "practice::answer" in it || "practice.answer(" in it }.map { f.name to it.trim() } }
        assertEquals(listOf("HelpScreens.kt" to "PracticeQuestion(state.answered, onAnswer = practice::answer)"), callers)
    }

    /** A lesson step has no action: it has a place, a sentence and words to suggest, nothing to run. */
    @Test
    fun a_lesson_step_is_a_place_and_a_sentence() {
        val help = Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/help/Help.kt").readText()
        assertTrue("data class LessonStep(val place: LessonPlace, val text: String, val prompts: List<String> = emptyList())" in help)
    }

    /**
     * A Control lesson's prompt only fills the coordinator's composer (14.22):
     * the one place that hands one on is `App.kt`'s `onPrompt`, and it goes to
     * the agent's `open(fill = …)`, which writes the draft and never sends.
     */
    @Test
    fun a_lesson_prompt_only_fills_the_composer() {
        val callers = Repo.shipped.flatMap { f -> f.readLines().filter { "onPrompt = " in it }.map { f.name to it.trim() } }
        assertEquals(1, callers.size, "one place hands a lesson's prompt on: $callers")
        assertEquals("App.kt", callers.single().first)
        assertTrue("agent.open(fill = prompt)" in callers.single().second)

        val agent = Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/AgentViewModel.kt").readText()
        assertTrue("drafts?.fill(row.id, fill)" in agent)
        assertTrue("send" !in agent.lowercase().substringAfter("fun open(").substringBefore("fun dismissError"), "opening the agent never sends")
    }
}
