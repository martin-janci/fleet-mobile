package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A chat form while its agent still writes it (`ask { draft }`, claude-fleet PR #779). */
class FormDraftTest {
    private val whole = """{"spec":"fleet.form/1","title":"Start Papaya receipts","steps":[""" +
        """{"title":"Project","fields":[""" +
        """{"name":"project","type":"text","label":"Project name","required":true},""" +
        """{"name":"host","type":"select","label":"Host","options":[["mercury","mercury"],["hetzner","hetzner-1"]]},""" +
        """{"name":"token","type":"secret","label":"Jira token"}]},""" +
        """{"title":"Links","fields":[{"name":"epic","type":"bool","label":"Link the epic"}]}]}"""

    @Test
    fun a_row_carries_the_draft_until_the_form_opens_or_it_goes_stale() {
        val row = json.decodeFromString(
            SessionRow.serializer(),
            """{"id":1,"form_draft":{"draft":"{\"title\":\"Pick","why":"the Jira epic PD-3100","updated_at":1000}}""",
        )
        assertEquals(FormDraft("{\"title\":\"Pick", "the Jira epic PD-3100", 1000), row.formDraft)
        assertEquals(row.formDraft, liveFormDraft(row, 1000 + FORM_DRAFT_TTL_SECS))
        // Abandoned: the hub's tick drops it; the phone stops drawing it first.
        assertNull(liveFormDraft(row, 1001 + FORM_DRAFT_TTL_SECS))
        // The open form replaces it.
        assertNull(liveFormDraft(row.copy(pendingForm = PendingForm("f_1", "Pick")), 1000))
        assertNull(liveFormDraft(row.copy(formDraft = FormDraft("", null, 1000)), 1000))
        // An older hub, or none.
        assertNull(json.decodeFromString(SessionRow.serializer(), """{"id":2}""").formDraft)
        assertNull(liveFormDraft(null, 1000))
    }

    @Test
    fun draws_the_title_and_each_whole_field_in_as_the_desktop_does() {
        // The desktop's test: cut just before the select's options.
        val text = whole.substring(0, whole.indexOf("\"options\""))
        val p = partialForm(text)
        assertEquals("Start Papaya receipts", p.title)
        assertFalse(p.complete)
        assertEquals(listOf("Project"), p.steps.map { it.title })
        assertEquals(listOf("project", "host"), p.steps.single().fields.map { it.name })
        assertEquals("Host", p.steps.single().fields[1].label)
    }

    @Test
    fun the_first_fields_are_fillable_while_the_last_one_still_arrives() {
        // Cut inside the select's options: the text field is closed, the select is not.
        val text = whole.substring(0, whole.indexOf("[\"hetzner\"") + 5)
        val p = partialForm(text)
        val (project, host) = p.steps.single().fields
        val f = assertNotNull(project.field)
        assertEquals("text", f.type)
        assertTrue(f.required)
        // Its options may be cut short: a skeleton until something follows it.
        assertNull(host.field)
        assertEquals(listOf("project"), p.fillable.map { it.name })

        // Once the next field begins, the select is whole and fillable with every option.
        val more = partialForm(whole.substring(0, whole.indexOf("\"name\":\"token\"")))
        val select = assertNotNull(more.steps.single().fields.first { it.name == "host" }.field)
        assertEquals(listOf("mercury", "hetzner"), select.options.map { it.first })
    }

    @Test
    fun a_secret_is_never_filled_in_a_draft_and_a_whole_text_settles_the_rest() {
        val p = partialForm(whole)
        assertTrue(p.complete)
        assertEquals(listOf("Project", "Links"), p.steps.map { it.title })
        val first = p.steps.first().fields
        assertEquals(listOf("project", "host"), first.mapNotNull { it.field?.name })
        assertEquals("secret", first.last().type)
        assertNull(first.last().field)
        // Only the first step is offered while it is written.
        assertEquals(listOf("project", "host"), p.fillable.map { it.name })
        assertNotNull(p.steps[1].fields.single().field)
    }

    @Test
    fun a_step_shows_once_it_has_a_title_and_a_field_once_it_has_name_type_and_label() {
        val p = partialForm("""{"spec":"fleet.form/1","title":"T","steps":[{"title":"A","fields":[{"name":"x","type":"text"}]},{"fie""")
        assertEquals(listOf("A"), p.steps.map { it.title })
        assertTrue(p.steps.single().fields.isEmpty())
        // An unknown type is not drawn either.
        val q = partialForm("""{"title":"T","steps":[{"title":"A","fields":[{"name":"x","type":"colour","label":"X"},{"name":"y""")
        assertTrue(q.steps.single().fields.isEmpty())
    }

    @Test
    fun nothing_that_parses_draws_nothing() {
        assertEquals(PartialForm.EMPTY, partialForm(""))
        assertEquals(PartialForm.EMPTY, partialForm("not json"))
        assertNull(partialForm("{\"tit").title)
        // Escapes inside a string do not end it.
        assertEquals("a \"b\" c", partialForm("""{"title":"a \"b\" c","steps":[""").title)
        // Nesting past the wire's limit is refused before the parser sees it.
        assertEquals(PartialForm.EMPTY, partialForm("[".repeat(500)))
    }

    @Test
    fun answers_filled_in_meanwhile_go_to_the_open_form_where_they_still_fit() {
        val form = assertNotNull(readAskForm(json.parseToJsonElement(whole)))
        val typed = mapOf(
            "project" to JsonPrimitive("papaya-receipts"),
            "host" to JsonPrimitive("gone-host"),
            "epic" to JsonPrimitive(true),
            "token" to JsonPrimitive("s3cret"),
            "dropped" to JsonPrimitive("x"),
        )
        assertEquals(
            mapOf("project" to JsonPrimitive("papaya-receipts"), "epic" to JsonPrimitive(true)),
            carryDraftAnswers(form, typed),
        )
        assertEquals(mapOf("host" to JsonPrimitive("mercury")), carryDraftAnswers(form, mapOf("host" to JsonPrimitive("mercury"))))
        // A type that changed meanwhile is not carried.
        assertEquals(emptyMap<String, JsonElement>(), carryDraftAnswers(form, mapOf("project" to JsonPrimitive(3), "epic" to JsonArray(emptyList()))))
    }
}
