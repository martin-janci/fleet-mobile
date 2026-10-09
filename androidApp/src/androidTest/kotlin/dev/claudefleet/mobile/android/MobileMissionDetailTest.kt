package dev.claudefleet.mobile.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.MissionAutonomy
import dev.claudefleet.mobile.model.MissionDetail
import dev.claudefleet.mobile.model.MissionGraph
import dev.claudefleet.mobile.model.MissionItem
import dev.claudefleet.mobile.model.MissionNode
import dev.claudefleet.mobile.model.MissionPlan
import dev.claudefleet.mobile.model.MissionStep
import dev.claudefleet.mobile.ui.MISSION_GRANT_NOT_NOW_TAG
import dev.claudefleet.mobile.ui.MISSION_GRANT_TAG
import dev.claudefleet.mobile.ui.MISSION_PLAN_TAG
import dev.claudefleet.mobile.ui.MISSION_RUNNING_TAG
import dev.claudefleet.mobile.ui.MissionsHandlers
import dev.claudefleet.mobile.ui.MissionsUiState
import dev.claudefleet.mobile.ui.OrbitMissionDetail
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The MobileControl board's mission panel: one mission in the New layout. */
class MobileMissionDetailTest {

    @get:Rule
    val compose = createComposeRule()

    private val detail = MissionDetail(
        mission = Mission(id = 7, name = "Hub federation v2", state = "active", total = 14, done = 5),
        items = listOf(MissionItem(1, title = "Contract bump"), MissionItem(2, title = "Probe pairing"), MissionItem(3, title = "Release notes")),
        graph = MissionGraph(listOf(MissionNode(1, "done"), MissionNode(2, "running", wave = 1), MissionNode(3, "ready", wave = 2))),
        mayChange = true,
        plan = MissionPlan(
            steps = listOf(MissionStep(kind = "run", itemId = 3, reason = "ready")),
            autonomy = MissionAutonomy(asked = 3, ceiling = 3, effective = 1),
        ),
    )

    @Test
    fun the_panel_shows_the_grant_what_runs_and_the_plan() {
        val started = mutableListOf<MissionStep?>()
        compose.setContent {
            FleetTheme {
                OrbitMissionDetail(
                    detail,
                    MissionsUiState(available = true, canStart = true, open = true, detail = detail),
                    MissionsHandlers(onStart = { started += it }),
                )
            }
        }
        compose.onNodeWithText("Hub federation v2").assertExists()
        compose.onNodeWithText("Mission · 5 of 14 steps").assertExists()
        compose.onNodeWithTag(MISSION_GRANT_TAG).assertExists()
        compose.onNodeWithText("Running · 1 task").assertExists()
        compose.onNodeWithTag(MISSION_RUNNING_TAG + 2).assertExists()
        compose.onNodeWithTag(MISSION_PLAN_TAG + 1).assertExists()
        compose.onNodeWithTag(MISSION_PLAN_TAG + 3).assertExists()
        compose.onNodeWithText("Go").performClick()
        assertEquals(listOf(detail.plan!!.steps[0]), started)
        compose.onNodeWithTag(MISSION_GRANT_NOT_NOW_TAG).performClick()
        compose.onNodeWithTag(MISSION_GRANT_TAG).assertDoesNotExist()
    }
}
