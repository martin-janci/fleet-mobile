package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Step 10.8: a session's chat form, as the hub's row and `ask` carry it. */
class ChatFormsTest {
    @Test
    fun a_row_carries_its_waiting_form_and_needs_a_person() {
        val row = json.decodeFromString(
            SessionRow.serializer(),
            """{"id":1,"claude_status":"working","pending_form":{"form_id":"f_a","title":"Deploy"}}""",
        )
        assertEquals(PendingForm("f_a", "Deploy"), row.pendingForm)
        assertEquals("waiting", row.attentionReason)
        assertNull(json.decodeFromString(SessionRow.serializer(), """{"id":2}""").pendingForm)
    }

    private val view = """{"form_id":"f_a","session_id":1,"host_alias":"mercury","title":"Deploy",
        "spec":{"spec":"fleet.form/1","title":"Deploy","steps":[{"title":"Where","fields":[
          {"name":"env","type":"select","label":"Env","options":[["stg","Staging"],["prod","Production"]],"value":"stg","required":true},
          {"name":"token","type":"secret","label":"Token","required":true,"when":{"field":"env","eq":"prod"}}]}]},
        "why":"I need a target","state":"pending","created_at":1800000000}"""

    @Test
    fun an_ask_form_may_hold_a_secret_a_reply_form_may_not() {
        val v = json.decodeFromString(FormView.serializer(), view)
        val form = assertNotNull(readAskForm(v.spec))
        assertEquals(listOf("select", "secret"), form.steps.single().fields.map { it.type })
        val inReply = """{"spec":"fleet.ui/1","kind":"form","form":${Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), v.spec!!)}}"""
        assertEquals(
            listOf("form › step 1 › field 2: a secret field is only for `ask`: its answer would land in the transcript"),
            (checkUiBlock(inReply) as UiCheck.Bad).problems,
        )
    }

    @Test
    fun answers_are_the_shown_fields_only() {
        val form = readAskForm(json.decodeFromString(FormView.serializer(), view).spec)!!
        val staging = mapOf("env" to JsonPrimitive("stg"), "token" to JsonPrimitive("left over"))
        assertEquals(mapOf("env" to JsonPrimitive("stg")), formAnswers(form, staging))
        val prod = mapOf("env" to JsonPrimitive("prod"), "token" to JsonPrimitive("s3cret"))
        assertEquals(prod, formAnswers(form, prod))
    }

    @Test
    fun how_a_form_ended_reads_as_on_the_desktop() {
        val v = json.decodeFromString(FormView.serializer(), view)
        assertEquals("Deploy: answered by Martin", formOutcome(v.copy(state = "answered", answeredBy = "Martin")))
        assertEquals("Deploy: withdrawn by the agent", formOutcome(v.copy(state = "cancelled", answeredBy = "Martin")))
        assertEquals("Deploy: expired unanswered", formOutcome(v.copy(state = "expired")))
    }

    @Test
    fun a_refusal_names_each_fields_problem() {
        val details = Json.parseToJsonElement("""{"problems":[{"field":"env","problem":"pick one"},{"bad":1}]}""")
        assertEquals(listOf(FieldProblem("env", "pick one")), fieldProblems(details))
        assertEquals(emptyList(), fieldProblems(null))
    }
}
