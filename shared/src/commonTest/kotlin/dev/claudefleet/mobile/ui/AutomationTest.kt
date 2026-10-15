package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.RoutineActions
import dev.claudefleet.mobile.model.FleetRun
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.HubHealth
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.Routine
import dev.claudefleet.mobile.model.RoutineBudget
import dev.claudefleet.mobile.model.RoutineCheck
import dev.claudefleet.mobile.model.RoutineDetail
import dev.claudefleet.mobile.model.RoutineExplain
import dev.claudefleet.mobile.model.RoutineRun
import dev.claudefleet.mobile.model.RunTrace
import dev.claudefleet.mobile.model.RunTraceStep
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.json
import dev.claudefleet.mobile.ui.kit.StatusWord
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class AutomationFleet(caps: HubCapabilities) : FleetState {
    override val sessions = MutableStateFlow(emptyList<SessionRow>())
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "mercury", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(caps)
    override suspend fun refresh() = Unit
}

private class FakeRoutineActions : RoutineActions {
    val calls = mutableListOf<String>()
    var list = listOf(
        Routine(id = 1, name = "Morning PR sweep", enabled = true, trigger = "cron", cron = "30 7 * * 1-5"),
        Routine(id = 2, name = "Friday release notes", enabled = false, trigger = "cron"),
    )
    var runs = mapOf(
        1L to listOf(RoutineRun(id = 11, routineId = 1, state = "done", startedAt = 100), RoutineRun(id = 12, routineId = 1, state = "failed", startedAt = 300)),
        2L to listOf(RoutineRun(id = 21, routineId = 2, state = "done", startedAt = 200)),
    )
    var paused = false
    var refuseToggle = false

    override suspend fun routines(): List<Routine> = list.also { calls += "list" }

    override suspend fun routine(routineId: Long): RoutineDetail {
        calls += "get $routineId"
        return RoutineDetail(routine = list.first { it.id == routineId }, runs = runs[routineId].orEmpty(), mayChange = true)
    }

    override suspend fun runs(routineId: Long, limit: Int): List<RoutineRun> {
        calls += "runs $routineId $limit"
        return runs[routineId].orEmpty()
    }

    override suspend fun setEnabled(routineId: Long, enabled: Boolean): Routine {
        calls += "set_enabled $routineId $enabled"
        if (refuseToggle) throw HubError.Tool("E_FORBIDDEN", "not yours")
        return list.first { it.id == routineId }.copy(enabled = enabled).also { r -> list = list.map { if (it.id == routineId) r else it } }
    }

    var fleet = listOf(
        FleetRun(id = "task:4", kind = "task", owner = "fix-login", startedAt = 500, outcome = "running", sessionIds = listOf(9)),
        FleetRun(id = "orchestration:7", kind = "mission", owner = "Ship 2.0", startedAt = 400, outcome = "needs_person", missionId = 3),
        FleetRun(id = "aux:2", kind = "morning_brief", owner = "morning_brief", startedAt = 300, outcome = "ok", costMicros = 420_000),
        FleetRun(id = "routine:12", kind = "routine", owner = "Morning PR sweep", startedAt = 200, outcome = "failed", error = "no host", routineId = 1),
    )

    override suspend fun fleetRuns(limit: Int): List<FleetRun> = fleet.also { calls += "fleet_runs $limit" }

    /** Each run's steps as the hub holds them; a read answers those after `after_seq`. */
    var traces = mapOf<String, List<RunTraceStep>>()

    override suspend fun runTrace(runId: String, afterSeq: Long?): RunTrace {
        calls += "trace $runId ${afterSeq ?: "-"}"
        return RunTrace(runId = runId, steps = traces[runId].orEmpty().filter { afterSeq == null || it.seq > afterSeq })
    }

    override suspend fun paused(): Boolean = paused.also { calls += "paused" }

    override suspend fun setPaused(paused: Boolean) {
        calls += "pause $paused"
        this.paused = paused
    }

    /** What `explain` answers; null is a hub without the action, and [explainFails] one that refuses it. */
    var why: RoutineExplain? = null
    var explainFails = false

    override suspend fun explain(routineId: Long): RoutineExplain? {
        calls += "explain $routineId"
        if (explainFails) throw HubError.Tool("E_INVALID", "action must be list | get")
        return why
    }

    /** Today's spend, micro-USD; null answers as a hub without `budget`. */
    var spent: Long? = null

    override suspend fun budget(): RoutineBudget? = spent?.let { calls += "budget"; RoutineBudget(spentMicros = it) }
}

private val WITH_ROUTINES = HubCapabilities(tools = setOf("routines", "set_setting", "fleet_health"))

/**
 * Automation on the phone (redesign 8.9): routines with their switch, the
 * runs they made, and Pause all, read only where the hub serves `routines`.
 */
class AutomationTest {

    private fun TestScope.automation(
        caps: HubCapabilities = WITH_ROUTINES,
        actions: FakeRoutineActions = FakeRoutineActions(),
        canWrite: Boolean = true,
    ) = AutomationViewModel(AutomationFleet(caps), actions, backgroundScope, canWrite)

    @Test
    fun a_hub_without_routines_is_never_asked() = runTest {
        val actions = FakeRoutineActions()
        val vm = automation(caps = HubCapabilities(tools = setOf("fleet_health")), actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        assertFalse(vm.state.value.available)
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun opening_reads_the_routines_their_runs_newest_first_and_the_pause() = runTest {
        val actions = FakeRoutineActions().apply { paused = true }
        val vm = automation(actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        val s = vm.state.value
        assertEquals(listOf(1L, 2L), s.routines.map { it.id })
        assertEquals(listOf(12L, 21L, 11L), s.runs.map { it.run.id })
        assertEquals("Friday release notes", s.runs[1].routine)
        assertEquals(true, s.paused)
        assertTrue("runs 1 10" in actions.calls)
    }

    @Test
    fun a_hub_with_runs_lists_every_kind_of_run_in_one_list() = runTest {
        // claude-fleet 8.3: tasks, missions, Jev, claude -p and routine fires
        // in one union; the per-routine reads are not made.
        val actions = FakeRoutineActions()
        val vm = automation(caps = HubCapabilities(tools = setOf("routines", "runs", "set_setting", "fleet_health")), actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        val runs = assertNotNull(vm.state.value.fleetRuns)
        assertEquals(listOf("task:4", "orchestration:7", "aux:2", "routine:12"), runs.map { it.id })
        assertTrue(actions.calls.none { it.startsWith("runs ") }, "the union replaces each routine's own runs")
        assertTrue(vm.state.value.runs.isEmpty())
        assertEquals(listOf(StatusWord.WORKING, StatusWord.NEEDS_YOU, StatusWord.DONE, StatusWord.FAILED), runs.map(::fleetRunWord))
        assertEquals("Morning brief", fleetRunTitle(runs[2]), "a run whose owner is its kind is titled by the kind, in words")
        assertEquals("2 min ago · \$0.42", fleetRunLine(runs[2], nowSeconds = 420))
        assertEquals("Routine · failed: no host · 3 min ago", fleetRunLine(runs[3], nowSeconds = 380))
    }

    @Test
    fun an_open_routine_says_why_it_does_not_fire_and_an_older_hub_says_nothing() = runTest {
        val actions = FakeRoutineActions()
        actions.why = RoutineExplain(
            summary = "Does not fire: Pause all is on.",
            state = "block",
            checks = listOf(
                RoutineCheck("enabled", "ok", "it is on"),
                RoutineCheck("host", "warn", "the last probe could not reach mac"),
                RoutineCheck("automation", "block", "Pause all is on"),
            ),
        )
        val vm = automation(actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        vm.select(1)
        runCurrent()
        val why = vm.state.value.explain
        assertEquals("Does not fire: Pause all is on.", why?.summary)
        assertEquals(listOf("Stops it: Pause all is on", "Note: the last probe could not reach mac"), whyLines(why!!))
        assertTrue("explain 1" in actions.calls)
        // An older hub refuses the action: the routine still opens, with no why.
        actions.explainFails = true
        vm.back()
        vm.select(1)
        runCurrent()
        assertNull(vm.state.value.explain)
        assertEquals(1L, vm.state.value.detail?.routine?.id)
        assertNull(vm.state.value.error)
    }

    @Test
    fun an_older_hub_keeps_the_routines_own_runs() = runTest {
        val vm = automation()
        runCurrent()
        vm.open()
        runCurrent()
        assertNull(vm.state.value.fleetRuns)
        assertEquals(3, vm.state.value.runs.size)
    }

    @Test
    fun a_routine_switch_moves_and_takes_the_hubs_answer() = runTest {
        val actions = FakeRoutineActions()
        val vm = automation(actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        vm.toggle(vm.state.value.routines[1])
        runCurrent()
        assertTrue("set_enabled 2 true" in actions.calls)
        assertTrue(vm.state.value.routines[1].enabled)
        assertNull(vm.state.value.busy)
    }

    @Test
    fun a_refused_switch_goes_back_and_says_why() = runTest {
        val actions = FakeRoutineActions().apply { refuseToggle = true }
        val vm = automation(actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        vm.toggle(vm.state.value.routines[0])
        runCurrent()
        assertTrue(vm.state.value.routines[0].enabled, "the switch is back where the hub has it")
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun a_readonly_phone_neither_switches_nor_pauses() = runTest {
        val actions = FakeRoutineActions()
        val vm = automation(actions = actions, canWrite = false)
        runCurrent()
        vm.open()
        runCurrent()
        assertFalse(vm.state.value.canToggle)
        assertFalse(vm.state.value.canPause)
        vm.toggle(vm.state.value.routines[0])
        vm.setPaused(true)
        runCurrent()
        assertTrue(actions.calls.none { it.startsWith("set_enabled") || it.startsWith("pause ") })
    }

    @Test
    fun pause_all_writes_the_setting_and_resume_undoes_it() = runTest {
        val actions = FakeRoutineActions()
        val vm = automation(actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        assertEquals(false, vm.state.value.paused)
        vm.setPaused(true)
        runCurrent()
        assertEquals(true, vm.state.value.paused)
        assertEquals("Paused all automation.", vm.state.value.notice)
        vm.setPaused(false)
        runCurrent()
        assertEquals(listOf("pause true", "pause false"), actions.calls.filter { it.startsWith("pause ") })
        assertEquals(false, vm.state.value.paused)
    }

    @Test
    fun a_hub_that_cannot_write_settings_offers_no_pause() = runTest {
        val vm = automation(caps = HubCapabilities(tools = setOf("routines")))
        runCurrent()
        assertTrue(vm.state.value.canToggle)
        assertFalse(vm.state.value.canPause)
    }

    @Test
    fun a_routine_opens_to_its_own_runs() = runTest {
        val actions = FakeRoutineActions()
        val vm = automation(actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        vm.select(1)
        runCurrent()
        assertEquals(listOf(11L, 12L), vm.state.value.detail?.runs?.map { it.id })
        vm.back()
        runCurrent()
        assertNull(vm.state.value.detail)
    }

    @Test
    fun the_more_row_says_how_many_are_on_and_whether_all_is_paused() {
        // MobileNav: "3 active · $4.10 today"; Pause all is the row's own button, not words in the line.
        val list = listOf(Routine(id = 1, enabled = true), Routine(id = 2), Routine(id = 3, enabled = true))
        assertEquals("2 active", automationLine(list, false))
        assertEquals("2 active · \$4.10 today", automationLine(list, false, 4_100_000))
        assertEquals("Paused · 2 active · \$0.00 today", automationLine(list, true, 0))
        assertEquals("No routines yet", automationLine(emptyList(), null))
    }

    @Test
    fun the_more_row_button_pauses_or_resumes_only_where_it_may() {
        val s = AutomationUiState(available = true, canPause = true, paused = false)
        assertEquals("Pause all", automationRowAction(s))
        assertEquals("Resume", automationRowAction(s.copy(paused = true)))
        assertNull(automationRowAction(s.copy(paused = null)), "not read yet: no button")
        assertNull(automationRowAction(s.copy(canPause = false)), "a device that may not write the setting")
        assertNull(automationRowAction(s.copy(busy = "pause")), "a change on the wire")
    }

    @Test
    fun the_spend_today_is_read_with_the_routines() = runTest {
        val actions = FakeRoutineActions().apply { spent = 4_100_000 }
        val vm = automation(actions = actions)
        runCurrent()
        vm.refresh()
        runCurrent()
        assertEquals(4_100_000L, vm.state.value.spentToday)
        assertTrue("budget" in actions.calls)

        // A hub that refused `budget` once is not asked again, and the line names no spend.
        val older = FakeRoutineActions().apply { spent = 1 }
        val vm2 = automation(caps = WITH_ROUTINES.forgetting("routines", "budget"), actions = older)
        runCurrent()
        vm2.refresh()
        runCurrent()
        assertNull(vm2.state.value.spentToday)
        assertFalse("budget" in older.calls)
    }

    @Test
    fun a_routine_says_when_it_runs_or_why_it_does_not() {
        val now = 1_000_000L
        assertEquals(
            "30 7 * * 1-5 · next in 2 h · mercury",
            routineLine(Routine(id = 1, enabled = true, trigger = "cron", cron = "30 7 * * 1-5", nextRunAt = now + 7_200, hostAlias = "mercury"), now),
        )
        assertEquals("When a turn ends", routineLine(Routine(id = 2, enabled = true, trigger = "event", event = "turn_done"), now))
        assertEquals("Off", routineLine(Routine(id = 3, trigger = "cron"), now))
        assertEquals("Off · turned off: day budget spent", routineLine(Routine(id = 4, pausedReason = "day budget spent"), now))
        assertEquals("Run now only · skips the next run", routineLine(Routine(id = 5, enabled = true, trigger = "manual", skipNext = true), now))
    }

    @Test
    fun a_run_says_what_it_came_to_when_how_long_and_what_it_cost() {
        val now = 10_000L
        val done = RoutineRun(id = 1, routineId = 1, state = "done", reason = "Reviewed 4 PRs", startedAt = now - 7_200, finishedAt = now - 6_960, costMicros = 420_000)
        assertEquals("Reviewed 4 PRs", runTitle(done))
        assertEquals("2 h ago · 4 min · $0.42", runLine(done, now))
        assertEquals(StatusWord.DONE, runWord(done))
        val failed = RoutineRun(id = 2, routineId = 1, state = "failed", trigger = "run_now", startedAt = now - 60, finishedAt = now - 50)
        assertEquals("Failed", runTitle(failed))
        assertEquals("1 min ago · under a minute · run now", runLine(failed, now))
        assertEquals(StatusWord.FAILED, runWord(failed))
        assertEquals(StatusWord.WORKING, runWord(RoutineRun(id = 3, routineId = 1, state = "running")))
        assertNull(runWord(RoutineRun(id = 4, routineId = 1, state = "skipped")))
    }

    @Test
    fun the_hubs_rows_and_its_pause_flag_parse() {
        val r = json.decodeFromString(
            Routine.serializer(),
            """{"id":3,"name":"Nightly audit","enabled":true,"trigger":"cron","cron":"0 2 * * *","utc_offset_min":120,"host_alias":"mercury","project_id":4,"prompt":"audit","overlap":"skip","next_run_at":1700000000,"created_at":1,"updated_at":2}""",
        )
        assertEquals(1_700_000_000L, r.nextRunAt)
        assertEquals("mercury", r.hostAlias)
        val d = json.decodeFromString(
            RoutineDetail.serializer(),
            """{"routine":{"id":3,"name":"n","enabled":false,"trigger":"manual","host_alias":"h","project_id":1,"prompt":"p","overlap":"skip","paused_reason":"budget","created_at":1,"updated_at":2},"runs":[{"id":9,"routine_id":3,"trigger":"run_now","state":"running","session_id":44,"cost_micros":0,"started_at":5}],"may_change":true}""",
        )
        assertEquals(44L, d.runs.single().sessionId)
        assertEquals("budget", d.routine.pausedReason)
        assertTrue(d.mayChange)
        assertTrue(json.decodeFromString(HubHealth.serializer(), """{"db_ready":true,"automation_paused":true}""").automationPaused)
        assertFalse(json.decodeFromString(HubHealth.serializer(), """{"db_ready":true}""").automationPaused)
    }
    @Test
    fun a_routine_fire_opens_to_its_steps_and_reads_on_from_the_last() = runTest {
        // claude-fleet migration 194: `runs { trace }`, offered where `runs` takes a run_id.
        val actions = FakeRoutineActions()
        actions.traces = mapOf(
            "routine:12" to listOf(
                RunTraceStep(seq = 1, phase = "trigger", kind = "trigger", actor = "scheduler", summary = "Fired by its schedule"),
                RunTraceStep(seq = 2, phase = "spawn", kind = "error", actor = "hub", summary = "The start on mercury failed: no host"),
            ),
        )
        val caps = HubCapabilities(tools = setOf("routines", "runs", "set_setting", "fleet_health"), params = mapOf("runs" to setOf("action", "run_id")))
        val vm = automation(caps = caps, actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        assertTrue(vm.state.value.canTrace)
        val fire = vm.state.value.fleetRuns!!.first { it.id == "routine:12" }
        vm.openRun(fire)
        runCurrent()
        val d = assertNotNull(vm.state.value.runDetail)
        assertEquals(listOf(1L, 2L), d.steps.map { it.seq })
        assertTrue("trace routine:12 -" in actions.calls)
        actions.traces = mapOf("routine:12" to actions.traces.getValue("routine:12") + RunTraceStep(seq = 3, phase = "settle", kind = "error", summary = "It started no session"))
        vm.refreshRun()
        runCurrent()
        assertTrue("trace routine:12 2" in actions.calls)
        assertEquals(listOf(1L, 2L, 3L), vm.state.value.runDetail!!.steps.map { it.seq })
        vm.closeRun()
        assertNull(vm.state.value.runDetail)
    }

    @Test
    fun a_hub_without_traces_keeps_runs_opening_their_session() = runTest {
        val vm = automation(caps = HubCapabilities(tools = setOf("routines", "runs", "set_setting", "fleet_health")))
        runCurrent()
        assertFalse(vm.state.value.canTrace)
    }

    @Test
    fun the_phase_rail_marks_reached_failed_and_live_phases() {
        val steps = listOf(
            RunTraceStep(seq = 1, phase = "trigger", kind = "trigger"),
            RunTraceStep(seq = 2, phase = "checks", kind = "check"),
            RunTraceStep(seq = 3, phase = "spawn", kind = "spawn", durationMs = 4200),
            RunTraceStep(seq = 4, phase = "prompt", kind = "prompt"),
            RunTraceStep(seq = 5, phase = "working", kind = "tool_call", summary = "Bash: pnpm test"),
        )
        val live = dev.claudefleet.mobile.model.phaseRail(steps, "running")
        assertEquals(
            listOf("Done", "Done", "Todo", "Done", "Done", "Live", "Todo", "Todo"),
            live.map { it.state.name },
        )
        assertEquals("1 step so far", live[5].meta)
        val failed = dev.claudefleet.mobile.model.phaseRail(listOf(RunTraceStep(seq = 1, phase = "spawn", kind = "error")), "failed")
        assertEquals("Failed", failed.first { it.phase == "spawn" }.state.name)
        val skipped = dev.claudefleet.mobile.model.phaseRail(listOf(RunTraceStep(seq = 1, phase = "checks", kind = "check")), "nothing_to_do", skipped = true)
        assertEquals("Skipped", skipped.first { it.phase == "checks" }.state.name)
        assertEquals("2m 48s", dev.claudefleet.mobile.model.tookWords(168_000))
        assertEquals("410 ms", dev.claudefleet.mobile.model.tookWords(410))
    }
}
