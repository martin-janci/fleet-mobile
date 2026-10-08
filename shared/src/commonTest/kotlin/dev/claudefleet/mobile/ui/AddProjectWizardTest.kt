package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.GithubRepo
import dev.claudefleet.mobile.ui.kit.RAIN
import dev.claudefleet.mobile.ui.kit.rainEdge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Add a project as the New layout's wizard (redesign 14.20, MobileWizards). */
class AddProjectWizardTest {

    @Test
    fun the_steps_count_the_clone_as_the_third() {
        assertEquals("Step 1 of 3 · Source", addStepHeading(AddProjectStep.Source))
        assertEquals("Step 2 of 3 · Where", addStepHeading(AddProjectStep.Where))
    }

    @Test
    fun next_says_what_is_missing_from_the_source() {
        assertEquals("Pick where the project comes from.", sourceBlocker(null))
        assertNull(sourceBlocker(ProjectSource.Github("acme/app")))
        assertEquals("Paste the repository's URL.", sourceBlocker(ProjectSource.Url("  ")))
        assertNull(sourceBlocker(ProjectSource.Url("https://github.com/acme/app")))
        assertEquals("Name the owner.", sourceBlocker(ProjectSource.New("", "app", false)))
        assertEquals("Name the repository.", sourceBlocker(ProjectSource.New("acme", " ", false)))
        assertNull(sourceBlocker(ProjectSource.New("acme", "app", true)))
    }

    @Test
    fun the_last_button_names_the_action() {
        assertEquals("Clone", addActionLabel(ProjectSource.Github("acme/app")))
        assertEquals("Clone", addActionLabel(ProjectSource.Url("https://github.com/acme/app")))
        assertEquals("Create", addActionLabel(ProjectSource.New("acme", "app", onGithub = false)))
        assertEquals("Create on GitHub", addActionLabel(ProjectSource.New("acme", "app", onGithub = true)))
    }

    @Test
    fun a_repository_already_added_stays_listed_and_says_so() {
        val pos = GithubRepo("martin-janci/papaya-pos", description = "Till", isPrivate = true)
        assertTrue(alreadyAdded(pos, listOf("Martin-Janci/Papaya-POS")))
        assertFalse(alreadyAdded(pos, listOf("martin-janci/papaya-receipts")))
        assertEquals("already a project · private · Till", repoLine(pos, added = true))
        assertNull(repoLine(GithubRepo("acme/app"), added = false))
    }

    @Test
    fun the_where_step_says_what_is_about_to_happen() {
        assertEquals("Clone acme/app", sourceSummary(ProjectSource.Github("acme/app")))
        assertEquals("A new repository acme/app, on GitHub too", sourceSummary(ProjectSource.New(" acme", "app ", onGithub = true)))
        assertEquals("Nothing picked yet.", sourceSummary(null))
    }

    @Test
    fun the_clone_lists_what_the_hub_runs_without_ticking_any_off() {
        assertEquals(listOf("Clone onto the host", "Add to Projects"), addSteps(creating = false, onGithub = false))
        assertEquals(
            listOf("Create the repository on GitHub", "Create the repository on the host", "Add to Projects"),
            addSteps(creating = true, onGithub = true),
        )
        assertEquals("Cloning papaya-pos", addingTitle("papaya-pos", creating = false))
        assertEquals("Creating the project", addingTitle(null, creating = true))
    }

    @Test
    fun the_data_rain_is_the_manuals_sixteen_streaks_fading_at_both_ends() {
        assertEquals(16, RAIN.size)
        assertTrue(RAIN.all { it.laps in 2..4 && it.start in 0f..1f && it.alpha in 0f..1f })
        assertEquals(0f, rainEdge(0f))
        assertEquals(1f, rainEdge(0.5f))
        assertEquals(0f, rainEdge(1f))
        assertEquals(0.5f, rainEdge(0.125f), 0.001f)
    }
}
