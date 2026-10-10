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
}
