package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.repairProgress
import dev.claudefleet.mobile.data.startProgress
import dev.claudefleet.mobile.model.DecideWeek
import dev.claudefleet.mobile.model.HubHealth
import dev.claudefleet.mobile.model.NO_REPAIR_STEPS
import dev.claudefleet.mobile.model.REPAIR_STEPS
import dev.claudefleet.mobile.model.StartStepState
import dev.claudefleet.mobile.model.WizardState
import dev.claudefleet.mobile.model.folding
import dev.claudefleet.mobile.model.line
import dev.claudefleet.mobile.model.playbookActivity
import dev.claudefleet.mobile.model.startedWhere
import dev.claudefleet.mobile.net.HubEvent
import dev.claudefleet.mobile.net.json
import dev.claudefleet.mobile.ui.kit.StepState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone's side of the hub data gap plan G7.3 serves (hub contract 17):
 * repair progress, the week's playbook runs and Jev's answers, and Add a
 * project resuming on the person's other device (G7.2).
 */
class HubDataRowsTest {

    // ── Repair ticking ──

    @Test
    fun a_repair_frame_decodes_like_a_start_frame_and_only_as_itself() {
        val payload = json.parseToJsonElement("""{"token":"st-1","step":"fix","index":2,"total":4,"state":"started"}""").jsonObject
        val repair = HubEvent.Row("repair:progress", payload)
        assertEquals("fix", repair.repairProgress()?.step)
        assertNull(repair.startProgress(), "a repair frame is not a start's")
        assertNull(HubEvent.Row("start:progress", payload).repairProgress())
    }

    @Test
    fun repair_steps_fold_forward_in_the_hubs_order() {
        assertEquals(listOf("check", "fix", "verify", "pane"), REPAIR_STEPS)
        val steps = NO_REPAIR_STEPS.folding("verify", "started", REPAIR_STEPS)
        assertEquals(
            listOf(StartStepState.DONE, StartStepState.DONE, StartStepState.STARTED, StartStepState.PENDING),
            REPAIR_STEPS.map { steps[it] },
        )
        assertEquals(steps, steps.folding("worktree", "done", REPAIR_STEPS), "a start's step changes nothing here")
    }

    @Test
    fun the_loader_ticks_what_the_hub_reported() {
        val before = repairReportedSteps(NO_REPAIR_STEPS)
        assertEquals(StepState.Running, before.first().state, "the call is on its way before the first frame")
        assertTrue(before.drop(1).all { it.state == StepState.Pending })

        val midway = repairReportedSteps(NO_REPAIR_STEPS.folding("fix", "started", REPAIR_STEPS))
        assertEquals(listOf(StepState.Done, StepState.Running, StepState.Pending, StepState.Pending), midway.map { it.state })

        val failed = repairReportedSteps(NO_REPAIR_STEPS.folding("verify", "failed", REPAIR_STEPS))
        assertEquals("failed", failed[2].detail)
    }

    // ── Playbooks and Jev this week ──

    @Test
    fun fleet_health_reads_the_weeks_playbook_runs_and_jevs_answers() {
        val h = json.decodeFromString(
            HubHealth.serializer(),
            """{"db_ready":true,"version":"0.6.3","playbooks_week":{"press_enter":3,"oom":1},
               "decide":{"enabled":true,"week":{"proposals":7,"kept":6,"changed":4,"rejected":2,"ignored":0}},
               "devices":{"paired":2,"last_seen_at":10,"last_sync_at":20}}""",
        )
        assertEquals(mapOf("press_enter" to 3, "oom" to 1), h.playbooksWeek)
        assertEquals(DecideWeek(proposals = 7, kept = 6, changed = 4, rejected = 2), h.decide?.week)
        val old = json.decodeFromString(HubHealth.serializer(), """{"db_ready":true}""")
        assertEquals(emptyMap(), old.playbooksWeek)
        assertNull(old.decide)
    }

    @Test
    fun a_playbook_setting_says_how_often_it_ran_this_week() {
        val week = mapOf("press_enter" to 3, "oom" to 1)
        assertEquals("Ran 3 times this week.", playbookActivity("playbooks.press_enter", week))
        assertEquals("Ran once this week.", playbookActivity("playbooks.oom_recreate", week))
        assertNull(playbookActivity("playbooks.oom_max_attempts", week), "not a playbook switch")
        assertNull(playbookActivity("playbooks.press_enter", emptyMap()), "an older hub and a quiet week read the same")
    }

    @Test
    fun jevs_week_is_one_line() {
        assertEquals("This week: 7 proposals · 6 kept · 4 changed · 2 turned down", DecideWeek(7, 6, 4, 2, 0).line())
        assertEquals("This week: 1 proposal · 1 not answered", DecideWeek(proposals = 1, ignored = 1).line())
        assertEquals("This week: no proposals", DecideWeek().line())
    }

    // ── Add a project resumes on another device ──

    @Test
    fun a_draft_keeps_the_desktops_answer_shape() {
        val gh = AddProjectDraft(source = ProjectSource.Github("acme/api"), host = "pine").answers()!!
        assertEquals("github", gh["mode"]!!.jsonPrimitive.content)
        assertEquals("pine", gh["host"]!!.jsonPrimitive.content)
        assertEquals("acme/api", gh["gh_selected"]!!.jsonArray.single().jsonPrimitive.content)

        val new = AddProjectDraft(source = ProjectSource.New("acme", "web", true), owner = "acme", repo = "web", onGithub = true)
        val a = new.answers()!!
        assertEquals("new", a["mode"]!!.jsonPrimitive.content)
        assertEquals("true", a["create_remote"]!!.jsonPrimitive.content)
        assertEquals("acme/web", new.label())

        assertNull(AddProjectDraft().answers(), "nothing picked is nothing to resume")
        assertNull(AddProjectDraft(source = ProjectSource.Url("  ")).answers(), "nor is a blank URL")
    }

    @Test
    fun a_draft_reads_back_whichever_device_wrote_it() {
        val draft = AddProjectDraft(source = ProjectSource.Url("https://github.com/acme/api.git"), url = "https://github.com/acme/api.git", host = "pine")
        assertEquals(draft, addProjectDraftOf(draft.answers()!!))
        assertEquals("acme/api", draft.label())

        // The desktop's own save: two repositories ticked, an org picked.
        val desktop = json.parseToJsonElement(
            """{"mode":"github","host":"local","gh_selected":["acme/api","acme/web"],"org_id":3}""",
        ).jsonObject
        val read = addProjectDraftOf(desktop)
        assertEquals(ProjectSource.Github("acme/api"), read.source)
        assertEquals("local", read.host)
        assertNull(addProjectDraftOf(JsonObject(mapOf())).source)
        assertNull(addProjectDraftOf(json.parseToJsonElement("""{"mode":"later"}""").jsonObject).source)
    }

    @Test
    fun the_resume_line_names_what_and_where() {
        val w = WizardState(kind = "add_project", label = "acme/api", device = "Ada's Mac", updatedAt = 1_000)
        assertEquals("on Ada's Mac 5 min ago", startedWhere(w, 1_300))
        assertEquals("You started adding acme/api on Ada's Mac 5 min ago.", addProjectResumeLine(w, 1_300))
        assertEquals("You started adding a project on the desktop just now.", addProjectResumeLine(WizardState(updatedAt = 1_000), 1_010))
    }
}
