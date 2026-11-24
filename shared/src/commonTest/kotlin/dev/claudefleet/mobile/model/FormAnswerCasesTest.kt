package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Review w19-6: the phone's port of the form `when` rule runs the cases the
 * hub and the desktop both run (docs/form-examples/answers.json, copied as
 * [FORM_ANSWER_EXAMPLES]). The phone has no full answer check: it decides
 * which fields are shown and which required ones are missing, so those are
 * what it is held to here.
 */
class FormAnswerCasesTest {
    private val doc = json.parseToJsonElement(FORM_ANSWER_EXAMPLES).jsonObject
    private val form = assertNotNull(readAskForm(doc["spec"]), "the phone reads the shared spec")

    private fun JsonElement.names(): Set<String> = (this as? JsonObject)?.keys.orEmpty()

    @Test
    fun every_answered_case_shows_exactly_the_fields_the_hub_keeps() {
        for (c in doc["cases"]!!.jsonArray.map { it.jsonObject }) {
            val answers = c["answers"] ?: continue
            val name = c["name"]!!.jsonPrimitive.content
            val values = c["values"]!!.jsonObject.toMap()
            val secrets = c["secrets"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val shown = answerable(form, values).map { it.name }.filter { it in values }.toSet()
            assertEquals(answers.names() + secrets, shown, name)
            assertTrue(answerable(form, values).none { fieldMissing(it, values[it.name]) }, "$name: nothing required is missing")
        }
    }

    @Test
    fun every_case_refused_only_for_required_fields_is_missing_those_fields() {
        for (c in doc["cases"]!!.jsonArray.map { it.jsonObject }) {
            val problems = (c["problems"] as? JsonArray)?.map { it.jsonObject } ?: continue
            if (problems.any { it["problem"]!!.jsonPrimitive.content != "is required" }) continue
            val values = c["values"]!!.jsonObject.toMap()
            val missing = answerable(form, values).filter { fieldMissing(it, values[it.name]) }.map { it.name }
            assertEquals(problems.map { it["field"]!!.jsonPrimitive.content }, missing, c["name"]!!.jsonPrimitive.content)
        }
    }

    /** The two divergences the review found: a number compares by value, and `truthy` wants a real bool. */
    @Test
    fun a_number_matches_by_value_and_truthy_wants_a_bool() {
        val eq = json.parseToJsonElement("""{"field":"port","eq":8080.0}""")
        assertTrue(fieldHolds(eq, mapOf("port" to JsonPrimitive(8080))))
        assertTrue(!fieldHolds(eq, mapOf("port" to JsonPrimitive("8080"))), "text is not a number")
        val truthy = json.parseToJsonElement("""{"field":"db","truthy":true}""")
        assertTrue(fieldHolds(truthy, mapOf("db" to JsonPrimitive(true))))
        assertTrue(!fieldHolds(truthy, mapOf("db" to JsonPrimitive("true"))), "the text \"true\" is not on")
    }
}
