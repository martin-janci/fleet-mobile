package dev.claudefleet.mobile.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cases of claude-fleet's `src/lib/rich_blocks.test.ts`, by name, so the
 * phone and the desktop read a reply the same way and give the same reasons.
 */
class RichBlocksTest {
    private val fence = "```"

    private fun ui(body: String) = "${fence}fleet-ui\n{\"spec\": \"fleet.ui/1\", $body}\n$fence"

    private val report = """{
  "summary": "Wrote docs/missions.md.",
  "outcome": "done",
  "tests_run": ["link check"],
  "warnings": ["Ticket brief was ambiguous"],
  "blockers": [],
  "followups": ["Push the branch and open a PR"],
  "confidence": "medium"
}"""

    @Test
    fun reportFromValue_normalises_as_the_backend_does() {
        val v = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"summary": " Added the queue table ", "outcome": "DONE", "tests_run": ["cargo test queue"], "warnings": [], "followups": "index the status column", "confidence": 0.8}""",
        )
        assertEquals(
            TaskReport(
                summary = "Added the queue table",
                outcome = "done",
                testsRun = listOf("cargo test queue"),
                warnings = emptyList(),
                blockers = emptyList(),
                followups = listOf("index the status column"),
                confidence = "0.8",
            ),
            reportFromValue(v),
        )
    }

    @Test
    fun an_unknown_outcome_is_partial_and_the_lists_are_capped() {
        val v = buildJsonObject {
            put("outcome", "shipped")
            put("warnings", buildJsonArray { repeat(30) { add(JsonPrimitive("w$it")) } })
            put("summary", "x".repeat(9000))
        }
        val r = reportFromValue(v)!!
        assertEquals("partial", r.outcome)
        assertEquals(20, r.warnings.size)
        assertEquals(4000, r.summary.length)
        assertNull(reportFromValue(JsonPrimitive("done")))
    }

    @Test
    fun the_marker_stands_alone_on_its_line_chrome_and_emphasis_aside() {
        assertEquals("FLEET_TASK_DONE_9d2404fd", markerOf("FLEET_TASK_DONE_9d2404fd"))
        assertEquals("FLEET_TASK_DONE_ab12", markerOf("⏺ **FLEET_TASK_DONE_ab12**"))
        assertNull(markerOf("print exactly FLEET_TASK_DONE_ab12 on its own line"))
    }

    @Test
    fun text_without_blocks_is_one_markdown_run() {
        assertEquals(listOf<RichSegment>(RichSegment.Md("Hello **there**")), splitRich("Hello **there**"))
    }

    @Test
    fun a_marker_and_its_fenced_json_become_a_report_keeping_the_prose_around_it() {
        val src = "All done.\n\nFLEET_TASK_DONE_9d2404fdfabee30f\n${fence}json\n$report\n$fence\n\nThanks."
        val segs = splitRich(src)
        assertEquals(listOf("Md", "Report", "Md"), segs.map { it::class.simpleName })
        val r = assertIs<RichSegment.Report>(segs[1])
        assertEquals("FLEET_TASK_DONE_9d2404fdfabee30f", r.marker)
        assertEquals("done", r.report.outcome)
        assertEquals(listOf("Push the branch and open a PR"), r.report.followups)
    }

    @Test
    fun a_bare_object_after_the_marker_is_read() {
        val segs = splitRich("FLEET_TASK_DONE_n1\n{\"summary\": \"ok\",\n \"outcome\": \"blocked\", \"blockers\": [\"no db\"]}\nthanks")
        assertEquals(listOf("Report", "Md"), segs.map { it::class.simpleName })
        assertEquals(listOf("no db"), assertIs<RichSegment.Report>(segs[0]).report.blockers)
    }

    @Test
    fun a_marker_with_no_json_or_json_still_being_written_stays_text() {
        assertEquals(listOf("Md"), splitRich("FLEET_TASK_DONE_n1\nA paragraph.").map { it::class.simpleName })
        assertEquals(listOf("Md"), splitRich("FLEET_TASK_DONE_n1\n${fence}json\n{\"summary\": \"half").map { it::class.simpleName })
    }

    @Test
    fun a_marker_or_fleet_ui_fence_quoted_inside_another_code_block_stays_code() {
        val src = "````markdown\nFLEET_TASK_DONE_n1\n${fence}json\n{\"outcome\":\"done\"}\n$fence\n" +
            ui("\"kind\": \"callout\", \"body\": \"x\"") + "\n````"
        assertEquals(listOf<RichSegment>(RichSegment.Md(src)), splitRich(src))
    }

    @Test
    fun a_fleet_ui_fence_is_a_card_and_a_json_fence_only_when_it_says_so() {
        val segs = splitRich("Before\n\n${ui("\"kind\": \"callout\", \"tone\": \"warning\", \"body\": \"Careful\"")}\n\nAfter")
        assertEquals(listOf("Md", "Ui", "Md"), segs.map { it::class.simpleName })
        val tagged = splitRich("${fence}json\n{\"spec\": \"fleet.ui/1\", \"kind\": \"facts\", \"items\": [[\"Host\", \"mercury\"]]}\n$fence")
        assertEquals(listOf("Ui"), tagged.map { it::class.simpleName })
        val plain = "${fence}json\n{\"kind\": \"facts\"}\n$fence"
        assertEquals(listOf<RichSegment>(RichSegment.Md(plain)), splitRich(plain))
    }

    @Test
    fun an_unclosed_fleet_ui_fence_stays_markdown_while_it_streams() {
        assertEquals(listOf("Md"), splitRich("${fence}fleet-ui\n{\"spec\": \"fleet.ui/1\",").map { it::class.simpleName })
    }

    @Test
    fun a_broken_block_shows_as_code_with_what_is_wrong() {
        val seg = assertIs<RichSegment.Invalid>(splitRich(ui("\"kind\": \"steps\", \"title\": \"T\", \"steps\": []")).single())
        assertEquals(listOf("`steps` needs at least 1 entry"), seg.problems)
    }

    private fun check(body: String) = checkUiBlock("{\"spec\": \"fleet.ui/1\", $body}")

    @Test
    fun every_kind_in_its_documented_shape_is_accepted() {
        val ok = listOf(
            "report" to "\"kind\": \"report\", \"title\": \"Run 3\", " + report.trim().removePrefix("{").removeSuffix("}"),
            "steps" to """"kind": "steps", "title": "Set up", "steps": [{"title": "Install", "code": "pnpm i", "lang": "sh"}, {"title": "Run", "body": "Then **run**."}]""",
            "guide" to """"kind": "guide", "title": "Missions", "sections": [{"title": "What", "body": "A mission is…"}]""",
            "callout" to """"kind": "callout", "tone": "tip", "title": "Tip", "body": "Use `verify.sh`."""",
            "facts" to """"kind": "facts", "items": [["Branch", "main"], ["Commits", 3]]""",
            "choices" to """"kind": "choices", "question": "Next?", "options": [{"label": "Open a PR", "prompt": "Open a draft PR", "hint": "recommended"}]""",
            "form" to """"kind": "form", "form": {"spec": "fleet.form/1", "title": "Deploy", "steps": [{"title": "Target", "fields": [{"name": "env", "type": "select", "label": "Env", "options": [["stg", "Staging"]]}]}]}""",
        )
        for ((kind, body) in ok) assertIs<UiCheck.Ok>(check(body), kind)
    }

    @Test
    fun what_is_wrong_is_named_and_where() {
        assertEquals(UiCheck.Bad(listOf("`kind` must be one of report, steps, guide, callout, facts, choices, form")), check("\"kind\": \"chart\""))
        assertEquals(UiCheck.Bad(listOf("`spec` must be \"fleet.ui/1\"")), checkUiBlock("{\"spec\": \"fleet.ui/2\", \"kind\": \"callout\", \"body\": \"x\"}"))
        assertEquals(UiCheck.Bad(listOf("`tone` must be one of info, tip, success, warning, danger")), check("\"kind\": \"callout\", \"tone\": \"loud\", \"body\": \"x\""))
        assertEquals(UiCheck.Bad(listOf("option 1: `prompt` is required")), check("\"kind\": \"choices\", \"options\": [{\"label\": \"A\"}]"))
        assertEquals(UiCheck.Bad(listOf("is not valid JSON")), checkUiBlock("{nope"))
        assertEquals(UiCheck.Bad(listOf("is larger than 32 KiB")), checkUiBlock("\"x\"".padEnd(UI_MAX_BYTES + 1, ' ')))
    }

    @Test
    fun a_reply_form_refuses_a_secret_field() {
        val r = assertIs<UiCheck.Bad>(
            check(""""kind": "form", "form": {"spec": "fleet.form/1", "title": "Login", "steps": [{"title": "S", "fields": [{"name": "pw", "type": "secret", "label": "Password"}]}]}"""),
        )
        assertTrue(r.problems[0].startsWith("form › step 1 › field 1: a secret field is only for `ask`"), r.problems[0])
    }

    @Test
    fun a_form_field_that_could_not_be_drawn_is_refused() {
        val r = check(""""kind": "form", "form": {"spec": "fleet.form/1", "title": "X", "steps": [{"title": "S", "fields": [{"name": "Bad Name", "type": "select", "label": "L", "options": [["a"]]}]}]}""")
        assertEquals(
            UiCheck.Bad(
                listOf(
                    "form › step 1 › field 1: name \"Bad Name\" must be lowercase letters, digits and _",
                    "form › step 1 › field 1 › option 1: must be [value, label]",
                ),
            ),
            r,
        )
    }

    @Test
    fun an_over_nested_block_is_refused_before_the_parser_sees_it() {
        assertEquals(UiCheck.Bad(listOf("is not valid JSON")), checkUiBlock("[".repeat(100) + "]".repeat(100)))
    }

    @Test
    fun the_answer_prompt_carries_the_title_and_the_shown_answers_as_one_json_block() {
        val form = assertIs<UiBlock.Form>(
            assertIs<UiCheck.Ok>(
                check(
                    """"kind": "form", "form": {"spec": "fleet.form/1", "title": "Deploy", "steps": [
                      {"title": "Target", "fields": [{"name": "env", "type": "select", "label": "Env", "options": [["stg", "S"], ["prod", "P"]], "value": "stg"},
                                                     {"name": "why", "type": "text", "label": "Why", "when": {"field": "env", "eq": "prod"}}]}]}""",
                ),
            ).block,
        ).form
        val values = formDefaults(form) + ("why" to JsonPrimitive("hidden, so dropped"))
        assertEquals(listOf("env"), visibleFields(form, values).single().second.map { it.name })
        assertEquals("Answers to the form \"Deploy\":\n\n```json\n{\n  \"env\": \"stg\"\n}\n```", formAnswerPrompt(form, values))
    }

    @Test
    fun fenced_picks_a_fence_no_backtick_run_inside_can_close() {
        assertEquals("`````json\na ```` b\n`````", fenced("json", "a ```` b"))
    }
}
