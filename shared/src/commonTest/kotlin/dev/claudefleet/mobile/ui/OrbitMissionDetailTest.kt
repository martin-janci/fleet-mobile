package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.MissionAutonomy
import dev.claudefleet.mobile.model.MissionCard
import dev.claudefleet.mobile.model.MissionDetail
import dev.claudefleet.mobile.model.MissionGrant
import dev.claudefleet.mobile.model.MissionGraph
import dev.claudefleet.mobile.model.MissionItem
import dev.claudefleet.mobile.model.MissionNode
import dev.claudefleet.mobile.model.MissionPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** One mission in the New layout (board MobileControl, the mission panel). */
class OrbitMissionDetailTest {

    private val mission = Mission(id = 7, name = "Hub federation v2", state = "active", total = 14, done = 5)

    private fun detail(
        nodes: List<MissionNode> = emptyList(),
        items: List<MissionItem> = emptyList(),
        plan: MissionPlan? = MissionPlan(),
        phase: String? = null,
    ) = MissionDetail(mission = mission, items = items, graph = MissionGraph(nodes), plan = plan, phase = phase)

    @Test
    fun the_head_says_how_far_the_mission_got() {
        assertEquals("Mission · 5 of 14 steps", missionHeadLine(mission, "running"))
        assertEquals("Mission · 5 of 14 steps · paused", missionHeadLine(mission.copy(state = "paused"), null))
        assertEquals("Mission · 5 of 14 steps · blocked", missionHeadLine(mission, "blocked"))
        assertEquals("Mission · no steps yet · draft", missionHeadLine(mission.copy(state = "draft", total = 0, done = 0), null))
    }

    @Test
    fun the_plan_runs_by_wave_and_marks_each_task_as_the_desktop_does() {
        val items = listOf(
            MissionItem(1, title = "Federation docs"),
            MissionItem(2, title = "Probe pairing"),
            MissionItem(3, key = "FL-3"),
            MissionItem(4, title = "Release notes"),
            MissionItem(5, title = "Contract bump"),
        )
        val rows = planRows(
            detail(
                nodes = listOf(
                    MissionNode(4, "ready", wave = 2),
                    MissionNode(2, "running", wave = 1),
                    MissionNode(1, "done", wave = 0),
                    MissionNode(3, "proposed", wave = 1),
                    MissionNode(5, "blocked", wave = 1),
                ),
                items = items,
            ),
        )
        assertEquals(listOf(1L, 2L, 3L, 5L, 4L), rows.map { it.itemId })
        assertEquals(
            listOf(PlanMark.DONE, PlanMark.RUNNING, PlanMark.NEEDS_YOU, PlanMark.FAILED, PlanMark.TODO),
            rows.map { it.mark },
        )
        assertEquals("FL-3", rows[2].title, "a task without a title shows its key")
        assertEquals("needs you", rows[2].note)
        assertEquals("blocked", rows[3].note)
        assertNull(rows[4].note)
        assertEquals("Running · 1 task", runningLine(rows))
    }

    @Test
    fun a_task_an_open_card_asks_about_needs_you() {
        val plan = MissionPlan(
            cards = listOf(
                MissionCard(id = 1, workItemId = 2, kind = "run"),
                MissionCard(id = 2, workItemId = 4, kind = "run", state = "dismissed"),
            ),
        )
        val rows = planRows(
            detail(
                nodes = listOf(MissionNode(2, "ready"), MissionNode(4, "ready"), MissionNode(1, "done")),
                plan = plan,
            ),
        )
        assertEquals(PlanMark.NEEDS_YOU, rows.first { it.itemId == 2L }.mark)
        assertEquals(PlanMark.TODO, rows.first { it.itemId == 4L }.mark, "a dismissed card asks nothing")
        assertEquals("Task 4", rows.first { it.itemId == 4L }.title)
        assertNull(runningLine(rows))
    }

    @Test
    fun a_mission_without_a_graph_lists_its_tasks_by_their_own_status() {
        val rows = planRows(
            detail(items = listOf(MissionItem(1, title = "a", statusCategory = "done"), MissionItem(2, title = "b", statusCategory = "todo"))),
        )
        assertEquals(listOf(PlanMark.DONE, PlanMark.TODO), rows.map { it.mark })
    }

    @Test
    fun a_grant_waits_only_when_the_mission_asks_more_than_it_gets_and_could_have_it() {
        fun plan(a: MissionAutonomy) = MissionPlan(autonomy = a)
        val ask = grantAsk(plan(MissionAutonomy(asked = 2, ceiling = 3, effective = 1, why = "no grant")))
        assertEquals(2, ask?.level)
        assertEquals("Level 2: Runs ready work under a grant.", ask?.line)
        assertEquals("no grant", ask?.why)
        assertNull(grantAsk(null), "a draft has no plan")
        assertNull(grantAsk(plan(MissionAutonomy(asked = 1, ceiling = 3, effective = 0))), "levels 0 and 1 need no grant")
        assertNull(grantAsk(plan(MissionAutonomy(asked = 3, ceiling = 2, effective = 2))), "the fleet's ceiling caps it; a signature would not help")
        assertNull(
            grantAsk(plan(MissionAutonomy(asked = 3, ceiling = 3, effective = 2, grant = MissionGrant(level = 2)))),
            "someone already signed",
        )
        assertNull(grantAsk(plan(MissionAutonomy(asked = 3, ceiling = 3, effective = 1, enabled = false))), "the loop is off")
        assertNull(grantAsk(plan(MissionAutonomy(asked = 2, ceiling = 3, effective = 2))))
    }
}
