package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.MissionActions
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.MissionCard
import dev.claudefleet.mobile.model.MissionDetail
import dev.claudefleet.mobile.model.MissionPlan
import dev.claudefleet.mobile.model.MissionStep
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.StartOutcome
import dev.claudefleet.mobile.model.StepResult
import dev.claudefleet.mobile.model.dollars
import dev.claudefleet.mobile.model.isQuestion
import dev.claudefleet.mobile.model.key
import dev.claudefleet.mobile.model.line
import dev.claudefleet.mobile.model.options
import dev.claudefleet.mobile.model.summary
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.ui.kit.ListBody
import dev.claudefleet.mobile.ui.kit.listBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MissionsFleet(caps: HubCapabilities) : FleetState {
    override val sessions = MutableStateFlow(emptyList<SessionRow>())
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "pine", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(caps)
    override suspend fun refresh() = Unit
}

private class FakeMissionActions : MissionActions {
    val calls = mutableListOf<String>()
    var list = listOf(Mission(id = 1, name = "Ship", state = "active", total = 3, done = 1, version = 4))
    var detail = MissionDetail(
        mission = list.first(),
        mayChange = true,
        plan = MissionPlan(steps = listOf(MissionStep(kind = "run", itemId = 12, reason = "ready"))),
    )
    var paused = listOf(1L)

    override suspend fun missions(): List<Mission> = list.also { calls += "missions" }

    override suspend fun mission(missionId: Long): MissionDetail = detail.also { calls += "mission $missionId" }

    override suspend fun start(missionId: Long, step: String?): StartOutcome {
        calls += "start $missionId ${step ?: "all"}"
        val s = detail.plan!!.steps.first()
        return StartOutcome(missionId, listOf(StepResult(step = s, ok = true, detail = "task 7")))
    }

    override suspend fun decideCard(cardId: Long, ok: Boolean, note: String?): MissionCard {
        calls += "decide $cardId $ok ${note ?: "-"}"
        return MissionCard(id = cardId, state = if (ok) "applied" else "dismissed")
    }

    override suspend fun setState(missionId: Long, state: String, expectedVersion: Long): Mission {
        calls += "state $missionId $state $expectedVersion"
        return list.first().copy(state = state)
    }

    override suspend fun pauseAll(): List<Long> = paused.also { calls += "pause_all" }
}

/** The Missions sheet: the wire shapes, the desktop's words, and what a press sends. */
class MissionsTest {

    /** Review r13 (P13-9): a failed list read is not "No missions yet", and Retry reads again. */
    @Test
    fun a_failed_list_read_is_not_an_empty_list_and_retry_reads_again() = runTest {
        val actions = object : MissionActions by FakeMissionActions() {
            var fail = true
            override suspend fun missions(): List<Mission> =
                if (fail) throw HubError.Transport(IllegalStateException("x")) else emptyList()
        }
        val vm = MissionsViewModel(MissionsFleet(loop), actions, backgroundScope, canWrite = true)
        vm.refresh().join()
        runCurrent()
        val failed = vm.state.value
        assertTrue(failed.listFailed)
        assertFalse(failed.loaded)
        assertEquals(ListBody.Failed, listBody(failed.loaded, failed.listFailed, empty = failed.missions.isEmpty()))

        actions.fail = false
        vm.refresh().join()
        runCurrent()
        val answered = vm.state.value
        assertFalse(answered.listFailed)
        assertEquals(ListBody.Empty, listBody(answered.loaded, answered.listFailed, empty = answered.missions.isEmpty()))
    }

    private val loop = HubCapabilities(
        tools = setOf(HubCapabilities.WORK, HubCapabilities.WORK_LINK),
        actions = mapOf(
            HubCapabilities.WORK to setOf("missions", "mission"),
            HubCapabilities.WORK_LINK to setOf("mission_start", "card_decide", "mission_state", "missions_pause_all"),
        ),
    )

    @Test
    fun a_mission_reads_in_the_hubs_wire_shape() {
        // `work { action: mission }` as fleet-core serialises `MissionDetail`,
        // with fields the phone does not draw left in to pass by.
        val wire = """
            {"mission":{"id":3,"name":"Payments","goal":"Ship v2","mode":"finite","state":"active",
              "level":2,"plan_version":1,"created_at":1,"updated_at":2,"version":5,"total":4,"done":1,
              "policy":{},"repos":[]},
             "items":[{"id":3,"source":"local","title":"Payments","status_category":"todo","created_at":0,"updated_at":0},
                      {"id":9,"key":"PAY-9","source":"jira","title":"Schema","status_category":"doing","created_at":0,"updated_at":0}],
             "events":[],"phase":"running",
             "graph":{"nodes":[{"item_id":9,"state":"running","wave":1,"depends_on":[]}],"waves":1},
             "may_change":true,
             "plan":{"steps":[{"kind":"review","item_id":9,"role":"review","reason":"implemented","auto":true}],
                     "cards":[{"id":11,"mission_id":3,"decision_id":"p:1","source":"planner","kind":"ask",
                               "payload":{"question":"Which db?","options":["pg","sqlite"]},"state":"open","created_at":5}],
                     "autonomy":{"asked":2,"ceiling":1,"effective":1,"why":"the fleet's ceiling is 1","enabled":true},
                     "cost_micros":1234567,"counts":{"total":1,"open":1}}}
        """.trimIndent()
        val d = json.decodeFromString(MissionDetail.serializer(), wire)
        assertEquals("Payments", d.mission.name)
        assertEquals("1/4 done · active", d.mission.summary())
        assertEquals("PAY-9", d.items[1].key)
        assertEquals("running", d.graph.nodes.single().state)
        val plan = d.plan!!
        assertEquals("review:9", plan.steps.single().key())
        assertEquals("Review: implemented", plan.steps.single().line())
        val card = plan.cards.single()
        assertTrue(card.isQuestion)
        assertEquals("Which db?", card.line())
        assertEquals(listOf("pg", "sqlite"), card.options)
        assertEquals(1, plan.autonomy.effective)
        assertEquals("$1.23", dollars(plan.costMicros))
    }

    @Test
    fun cards_read_as_the_desktops_card_line() {
        fun card(kind: String, item: Long? = null, vararg p: Pair<String, String>) = MissionCard(
            id = 1,
            kind = kind,
            workItemId = item,
            payload = buildJsonObject { p.forEach { (k, v) -> put(k, v) } },
        )
        val create = MissionCard(
            id = 1,
            kind = "create",
            payload = buildJsonObject {
                put("tree", buildJsonArray { add(buildJsonObject { put("title", "schema") }); add(buildJsonObject { put("title", "api") }) })
            },
        )
        assertEquals("Create 2 tasks: schema, api", create.line())
        assertEquals("Make task 4 wait for 7", MissionCard(id = 1, kind = "add_dep", workItemId = 4, payload = buildJsonObject { put("depends_on", 7) }).line())
        assertEquals("Run task 4 (test)", card("run", 4, "role" to "test").line())
        assertEquals("Retry task 4: flaky", card("retry", 4, "note" to "flaky").line())
        assertEquals("Complete the mission", card("complete").line())
        assertEquals("hold task 4", card("hold", 4).line())
        assertEquals("A question", card("ask").line())
    }

    @Test
    fun dollars_round_to_cents() {
        assertEquals("$0.00", dollars(0))
        assertEquals("$0.01", dollars(5_000))
        assertEquals("$12.50", dollars(12_500_000))
    }

    @Test
    fun a_hub_without_missions_or_a_readonly_pairing_is_offered_nothing_it_would_refuse() = runTest {
        val old = MissionsViewModel(MissionsFleet(HubCapabilities(tools = setOf(HubCapabilities.WORK))), FakeMissionActions(), backgroundScope, canWrite = true)
        runCurrent()
        assertFalse(old.state.value.available)

        val readonly = MissionsViewModel(MissionsFleet(loop), FakeMissionActions(), backgroundScope, canWrite = false)
        runCurrent()
        val s = readonly.state.value
        assertTrue(s.available, "a readonly pairing still sees its missions")
        assertFalse(s.canStart || s.canDecide || s.canPause || s.canPauseAll)
    }

    @Test
    fun go_sends_the_steps_key_and_reads_the_mission_again() = runTest {
        val actions = FakeMissionActions()
        val vm = MissionsViewModel(MissionsFleet(loop), actions, backgroundScope, canWrite = true)
        vm.open().join()
        vm.select(1).join()
        runCurrent()
        val step = vm.state.value.detail!!.plan!!.steps.single()

        vm.start(step).join()
        runCurrent()
        assertTrue("start 1 run:12" in actions.calls, "${actions.calls}")
        assertEquals(2, actions.calls.count { it == "mission 1" }, "read again after the press")
        assertTrue(vm.state.value.results!!.single().ok)
        assertNull(vm.state.value.busy)
    }

    @Test
    fun a_question_is_answered_with_its_words_and_pause_sends_the_version() = runTest {
        val actions = FakeMissionActions()
        val vm = MissionsViewModel(MissionsFleet(loop), actions, backgroundScope, canWrite = true)
        vm.open().join()
        vm.select(1).join()
        runCurrent()

        vm.decide(MissionCard(id = 11, kind = "ask", payload = buildJsonObject { put("question", JsonPrimitive("Which db?")) }), ok = true, note = "pg").join()
        runCurrent()
        assertTrue("decide 11 true pg" in actions.calls, "${actions.calls}")

        vm.togglePause().join()
        runCurrent()
        assertTrue("state 1 paused 4" in actions.calls, "${actions.calls}")
        assertEquals("Paused.", vm.state.value.notice)
    }

    @Test
    fun pause_all_says_how_many_and_reads_the_list_again() = runTest {
        val actions = FakeMissionActions()
        actions.paused = listOf(1L, 2L)
        val vm = MissionsViewModel(MissionsFleet(loop), actions, backgroundScope, canWrite = true)
        vm.open().join()
        runCurrent()
        assertEquals(1, vm.state.value.running)

        vm.pauseAll().join()
        runCurrent()
        assertTrue("pause_all" in actions.calls)
        assertEquals(2, actions.calls.count { it == "missions" })
        assertEquals("Paused 2 missions.", vm.state.value.notice)
    }

    @Test
    fun a_tap_on_the_missions_screen_opens_the_sheet_on_that_mission() = runTest {
        val actions = FakeMissionActions()
        val vm = MissionsViewModel(MissionsFleet(loop), actions, backgroundScope, canWrite = true)
        vm.openOne(1).join()
        runCurrent()
        assertTrue(vm.state.value.open)
        assertEquals(1L, vm.state.value.detail?.mission?.id)
        assertEquals(listOf("mission 1"), actions.calls, "the screen has the list already; only the mission is read")
    }
}
