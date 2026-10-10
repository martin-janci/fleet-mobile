package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ChatFormActions
import dev.claudefleet.mobile.model.FormPick
import dev.claudefleet.mobile.model.FormProposal
import dev.claudefleet.mobile.model.FormView
import dev.claudefleet.mobile.model.PendingForm
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.answerLines
import dev.claudefleet.mobile.model.answerSummary
import dev.claudefleet.mobile.model.formPicks
import dev.claudefleet.mobile.model.orderedOptions
import dev.claudefleet.mobile.model.pickLine
import dev.claudefleet.mobile.model.readAskForm
import dev.claudefleet.mobile.model.startedByForm
import dev.claudefleet.mobile.model.SuggestedHost
import dev.claudefleet.mobile.model.hostPlacementAsk
import dev.claudefleet.mobile.model.startedWorkLine
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gap plan G5.2 (the MobileChatForms board): a proposed choice with who and
 * why and Change, the answered line with its summary and View, the whole
 * screen's back word, and other sessions' forms in Control's chat.
 */
class ChatFormsPhoneTest {

    private val spec = """{
        "spec": "fleet.form/1", "title": "Deploy",
        "steps": [
          { "title": "Where", "fields": [
            { "name": "host", "type": "select", "label": "Host", "options": [
              ["mercury", "mercury"],
              { "value": "venus", "label": "venus", "detail": "2 idle", "proposed": { "by": "jev", "reason": "you used it for the last three deploys" } },
              ["mars", "mars"] ] },
            { "name": "env", "type": "select", "label": "Env", "options": [
              ["stg", "Staging"],
              { "value": "prod", "label": "Production", "proposed": { "by": "rule", "reason": "main is green" } } ] },
            { "name": "tickets", "type": "multiselect", "label": "Tickets", "options": [["a", "PD-1"], ["b", "PD-2"], ["c", "PD-3"]] },
            { "name": "notify", "type": "bool", "label": "Notify", "value": false },
            { "name": "token", "type": "secret", "label": "Token" }
          ] }
        ]
      }"""

    private val form = assertNotNull(readAskForm(json.parseToJsonElement(spec)))

    @Test
    fun the_spec_proposal_is_read_and_a_risky_one_is_dropped() {
        val host = form.steps[0].fields.first { it.name == "host" }
        assertEquals("venus", host.proposed?.value)
        assertEquals("jev", host.proposed?.by)

        val picks = formPicks(form, proposal = null)
        assertEquals(FormPick("host", "venus", "jev", "you used it for the last three deploys"), picks["host"])
        // "Production" is a choice AI never makes for a person, whoever proposed it.
        assertNull(picks["env"])
        assertEquals("Proposed by Jev · you used it for the last three deploys", pickLine(picks.getValue("host")))
        assertEquals(listOf("venus", "mercury", "mars"), orderedOptions(host, picks["host"]).map { it.first })
    }

    @Test
    fun jevs_quick_answer_wins_over_the_spec_and_a_dismissed_field_has_none() {
        val picks = formPicks(form, FormProposal(field = "host", value = "mars", source = "jev"))
        assertEquals("mars", picks["host"]?.value)
        assertEquals("Proposed by Jev", pickLine(picks.getValue("host")))
        // A value the field does not offer is no proposal.
        assertEquals("venus", formPicks(form, FormProposal("host", "pluto"))["host"]?.value)
        assertTrue(formPicks(form, null, dismissed = setOf("host")).isEmpty())
    }

    private fun answered(answers: String, secrets: String? = null): FormView = json.decodeFromString(
        FormView.serializer(),
        """{"form_id":"f_a","title":"Deploy","state":"answered","answered_by":"Martin",
            "answers":$answers${secrets?.let { ",\"secrets\":$it" }.orEmpty()}}""",
    )

    @Test
    fun an_answered_form_folds_to_a_few_words_and_view_lists_every_answer() {
        val view = answered(
            """{"host":"venus","env":"stg","tickets":["a","c"],"notify":false}""",
            """{"token":"~/.cache/claude-fleet/forms/f_a/token"}""",
        )
        assertEquals("venus · Staging · 2 tickets · +1 more", answerSummary(form, view))
        assertEquals(
            listOf("Host" to "venus", "Env" to "Staging", "Tickets" to "PD-1, PD-3", "Notify" to "No", "Token" to "Saved on the host"),
            answerLines(form, view),
        )
        assertEquals("venus · token saved", answerSummary(form, answered("""{"host":"venus"}""", """{"token":"x"}""")))
        assertNull(answerSummary(form, answered("{}")))
    }

    @Test
    fun the_whole_screen_goes_back_to_the_chat() {
        assertEquals("Chat", firstBackLabel(0, full = true))
        assertEquals("Close", firstBackLabel(0, full = false))
        assertEquals("Back", firstBackLabel(2, full = true))
    }

    @Test
    fun control_lists_every_other_sessions_waiting_form() {
        val rows = listOf(
            SessionRow(id = 1, pendingForm = PendingForm("f_control", "Plan")),
            SessionRow(id = 2, pendingForm = PendingForm("f_api", "Deploy")),
            SessionRow(id = 3),
        )
        assertEquals(listOf(2L), controlForms(rows, controlSessionId = 1).map { it.id })
        assertEquals(listOf(1L, 2L), controlForms(rows, controlSessionId = null).map { it.id })
    }

    private class Forms(var view: FormView) : ChatFormActions {
        override suspend fun get(formId: String): FormView = view
        override suspend fun answer(formId: String, values: Map<String, JsonElement>): FormView = view
        override suspend fun decline(formId: String, note: String?): FormView = view
    }

    @Test
    fun a_proposal_is_chosen_while_the_field_is_empty_and_change_gives_it_back() = runTest {
        val view = FormView(formId = "f_a", title = "Deploy", spec = json.parseToJsonElement(spec), state = "pending")
        val m = AskFormModel(Forms(view), "f_a", this)
        m.load().join()
        assertEquals(JsonPrimitive("venus"), m.state.value.values["host"])
        assertEquals("venus", m.state.value.picks["host"]?.value)

        m.changePick("host")
        assertNull(m.state.value.values["host"], "Change hands the choice back to the person")
        assertNull(m.state.value.picks["host"])
    }

    @Test
    fun a_proposal_never_overrides_what_the_person_picked() = runTest {
        val view = FormView(formId = "f_a", title = "Deploy", spec = json.parseToJsonElement(spec), state = "pending")
        val m = AskFormModel(Forms(view), "f_a", this)
        m.load().join()
        m.set("host", JsonPrimitive("mars"))
        m.changePick("host")
        assertEquals(JsonPrimitive("mars"), m.state.value.values["host"])
    }

    // The work a form started follows its answered line (MobileChatForms).
    private val answered = FormView(formId = "f_s", sessionId = 5, title = "Start Papaya receipts", state = "answered", decidedAt = 1_000)

    @Test
    fun the_session_an_answer_started_is_the_askers_first_start_after_the_answer() {
        val before = SessionRow(id = 10, origin = "operator", originRef = "5", startedAt = 900)
        val other = SessionRow(id = 11, origin = "operator", originRef = "6", startedAt = 1_010)
        val person = SessionRow(id = 12, origin = "person", originRef = "5", startedAt = 1_010)
        val second = SessionRow(id = 14, origin = "token", originRef = "5", startedAt = 1_200)
        val first = SessionRow(id = 13, origin = "operator", originRef = "5", startedAt = 1_005, hostAlias = "mercury")
        assertEquals(13L, startedByForm(answered, listOf(before, other, person, second, first))?.id)
        assertNull(startedByForm(answered, listOf(before, other, person)), "nothing started yet: no row")
        assertNull(startedByForm(answered.copy(state = "declined"), listOf(first)), "only an answer starts work")
        assertNull(startedByForm(answered.copy(decidedAt = null), listOf(first)))
    }

    @Test
    fun the_started_row_says_starting_until_its_agent_speaks() {
        assertEquals("starting on mercury", startedWorkLine(SessionRow(id = 1, hostAlias = "mercury")))
        assertEquals("on mercury", startedWorkLine(SessionRow(id = 1, hostAlias = "mercury", claudeStatus = "working")))
    }

    // MobileControl's plan: "Proposed by Jev · mercury … · Change" on a host choice for a project.
    private val placement = """{
        "spec": "fleet.form/1", "title": "New session",
        "steps": [
          { "title": "Where", "fields": [
            { "name": "project_id", "type": "text", "label": "Project", "value": "12" },
            { "name": "host", "type": "select", "label": "Host", "options": [["mercury", "mercury"], ["mac", "mac"], ["oci-arm", "oci-arm"]] }
          ] }
        ]
      }"""

    @Test
    fun a_host_choice_for_a_project_asks_jev_once_and_never_overrides_the_person() = runTest {
        val view = FormView(formId = "f_h", title = "New session", spec = json.parseToJsonElement(placement), state = "pending")
        val asked = mutableListOf<Long>()
        val m = AskFormModel(Forms(view), "f_h", this, proposeHost = { id -> asked += id; SuggestedHost("mercury", confidencePct = 82) })
        m.load().join()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(12L), asked)
        assertEquals(JsonPrimitive("mercury"), m.state.value.values["host"])
        assertEquals("Proposed by Jev · 82% sure", pickLine(assertNotNull(m.state.value.picks["host"])))

        // Change hands it back, and the same project is not asked again.
        m.changePick("host")
        testScheduler.advanceUntilIdle()
        assertNull(m.state.value.values["host"])
        assertEquals(listOf(12L), asked)
    }

    @Test
    fun no_proposal_for_a_host_the_form_does_not_offer_or_on_refusal() = runTest {
        val view = FormView(formId = "f_h", title = "New session", spec = json.parseToJsonElement(placement), state = "pending")
        val elsewhere = AskFormModel(Forms(view), "f_h", this, proposeHost = { SuggestedHost("venus") })
        elsewhere.load().join()
        testScheduler.advanceUntilIdle()
        assertNull(elsewhere.state.value.values["host"])
        val refused = AskFormModel(Forms(view), "f_h", this, proposeHost = { throw IllegalStateException("E_FORBIDDEN") })
        refused.load().join()
        testScheduler.advanceUntilIdle()
        assertNull(refused.state.value.picks["host"], "a refusal shows nothing")
        // A form without a project names nothing to ask about.
        val spec = assertNotNull(readAskForm(json.parseToJsonElement(placement.replace("project_id", "note"))))
        assertNull(hostPlacementAsk(spec, emptyMap(), emptyMap()))
    }
}
