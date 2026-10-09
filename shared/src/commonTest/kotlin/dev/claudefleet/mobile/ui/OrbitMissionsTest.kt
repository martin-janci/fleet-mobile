package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.ui.kit.StatusWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_790_000_000L

private fun mission(id: Long, state: String, done: Int = 1, total: Int = 5, ago: Long = 60) =
    Mission(id = id, name = "m$id", state = state, done = done, total = total, updatedAt = NOW - ago)

/** Missions as a screen in the New layout (redesign 14.16, MobileMissions). */
class OrbitMissionsTest {

    @Test
    fun missions_group_by_state_newest_first_and_done_only_this_week() {
        val g = missionGroups(
            listOf(
                mission(1, "active", ago = 3 * 3600),
                mission(2, "active", ago = 60),
                mission(3, "paused"),
                mission(4, "draft"),
                mission(5, "completed", ago = 2 * 86_400),
                mission(6, "failed", ago = 8 * 86_400),
            ),
            NOW,
        )
        assertEquals(listOf(2L, 1L), g.running.map { it.id })
        assertEquals(listOf(3L), g.paused.map { it.id })
        assertEquals(listOf(4L), g.drafts.map { it.id })
        assertEquals(listOf(5L), g.doneThisWeek.map { it.id }, "a mission finished eight days ago is not this week's")
        assertEquals("2 running · 1 paused · 1 draft", missionsHeadline(g))
        assertEquals("No missions running", missionsHeadline(missionGroups(emptyList(), NOW)))
        assertTrue(missionGroups(emptyList(), NOW).isEmpty)
    }

    @Test
    fun a_row_says_the_step_it_is_on_with_its_word() {
        val running = mission(1, "active", done = 1, total = 5)
        assertEquals("step 2 of 5", missionLine(running))
        assertEquals(StatusWord.WORKING, missionWord(running))
        assertEquals("step 5 of 5", missionLine(mission(2, "active", done = 5, total = 5)), "never past the last step")
        assertEquals("no steps yet", missionLine(mission(3, "active", done = 0, total = 0)))
        assertEquals("5 of 5 steps", missionLine(mission(4, "completed", done = 5, total = 5)))
        assertEquals("at step 3 of 4", missionLine(mission(5, "failed", done = 2, total = 4)))
        assertEquals(StatusWord.PAUSED, missionWord(mission(6, "paused")))
        assertNull(missionWord(mission(7, "draft")))
    }

    @Test
    fun pause_all_says_what_stops_and_what_finishes_before_it_stops_anything() {
        assertEquals(
            "3 missions stop starting new steps. Sessions already working finish their current turn, then wait.",
            pauseAllMeta(3),
        )
        assertTrue(pauseAllMeta(1).startsWith("1 mission stops"))
        assertEquals("Pause 3 missions", pauseAllLabel(3))
        assertEquals("Pause 1 mission", pauseAllLabel(1))
    }

    @Test
    fun missions_open_over_control_and_back_returns_there() {
        val nav = Navigator(PhoneLayout.New)
        nav.select(Tab.Control)
        nav.openMissions()
        assertEquals(Screen.Missions, nav.screen.value)
        assertTrue(nav.isPushed(Screen.Missions))
        nav.back()
        assertEquals(Screen.Control, nav.screen.value)
    }
}
