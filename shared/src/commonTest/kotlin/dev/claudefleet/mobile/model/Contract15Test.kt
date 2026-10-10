package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import dev.claudefleet.mobile.notify.needsYouAlerts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hub contract 15 (claude-fleet gap plan G1.1 and G1.6): every new wire
 * shape decodes, and Jev's "probably waiting" is shown but never counted.
 */
class Contract15Test {

    // ---- G1.6: needs_attention gains probably_waiting / proposed ----

    private val proposedRow = """{"id":7,"tmux_name":"api","kind":"work","claude_status":"idle",
        "needs_attention":{"reason":"probably_waiting","since":60,"state":"proposed"}}"""

    @Test
    fun a_probably_waiting_row_decodes_and_is_proposed() {
        val row = json.decodeFromString(SessionRow.serializer(), proposedRow)
        assertEquals(Attention(reason = "probably_waiting", since = 60, state = "proposed"), row.attention)
        assertTrue(row.isProposed)
        assertEquals("probably_waiting", row.attentionReason)
        assertEquals("Probably waiting", reasonLabel("probably_waiting"))
    }

    @Test
    fun a_proposed_row_never_counts_toward_needs_you() {
        val row = json.decodeFromString(SessionRow.serializer(), proposedRow)
        assertFalse(row.needsAttention, "a proposal is never in Needs you")
        assertEquals(TriageBucket.PROBABLY_WAITING, row.triageBucket())
        assertFalse(TriageBucket.PROBABLY_WAITING.needsYou, "the Needs you filter leaves it out, as the desktop's does")
        // Nor is it announced.
        val (alerts, seen) = needsYouAlerts(emptyMap(), listOf(row))
        assertTrue(alerts.isEmpty())
        assertNull(seen[7L])
    }

    /** The state alone marks a proposal, whatever reason a later hub files it under. */
    @Test
    fun the_proposed_state_alone_keeps_a_row_out_of_the_count() {
        val row = SessionRow(id = 1, claudeStatus = "idle", attention = Attention("some_new_guess", state = "proposed"))
        assertTrue(row.isProposed)
        assertFalse(row.needsAttention)
    }

    @Test
    fun a_counted_state_still_counts() {
        val row = json.decodeFromString(
            SessionRow.serializer(),
            """{"id":1,"claude_status":"blocked","needs_attention":{"reason":"waiting","since":5,"state":"action_required"}}""",
        )
        assertFalse(row.isProposed)
        assertTrue(row.needsAttention)
        assertEquals(TriageBucket.WAITING, row.triageBucket())
    }

    /** It ranks after every reason a person must act on and before Done · unread, as `attention_states.json` has it. */
    @Test
    fun probably_waiting_ranks_after_ci_failing_and_before_done_unread() {
        val ordinal = TriageBucket.PROBABLY_WAITING.ordinal
        assertEquals(TriageBucket.CI_FAILING.ordinal + 1, ordinal)
        assertEquals(TriageBucket.DONE_UNREAD.ordinal - 1, ordinal)
    }

    // ---- G1.6: missions waiting on a person; Today's missions and proposed ----

    @Test
    fun a_mission_row_carries_what_it_waits_on() {
        val m = json.decodeFromString(
            Mission.serializer(),
            """{"id":7,"name":"Hub federation v2","state":"active","level":2,"cost_micros":10,
                "waiting_on":{"reason":"sign_grant","since":40,"open_cards":0}}""",
        )
        assertEquals(MissionWait(reason = "sign_grant", since = 40, openCards = 0), m.waitingOn)
        assertEquals("Waits on you · a grant to sign", missionWaitLabel(m.waitingOn!!))
        assertNull(json.decodeFromString(Mission.serializer(), """{"id":8}""").waitingOn)
    }

    @Test
    fun a_mission_wait_reads_in_words() {
        assertEquals("Waits on you · a question", missionWaitLabel(MissionWait("question", 70, 2)))
        assertEquals("Waits on you · 1 command to confirm", missionWaitLabel(MissionWait("confirm", 50, 1)))
        assertEquals("Waits on you · 3 commands to confirm", missionWaitLabel(MissionWait("confirm", 50, 3)))
        assertEquals("Waits on you · something new", missionWaitLabel(MissionWait("something_new", 1, 0)))
    }

    @Test
    fun today_carries_waiting_missions_and_a_sessions_proposal() {
        val today = json.decodeFromString(
            Today.serializer(),
            """{"since":0,"now":100,
                "groups":[{"bucket":"in_progress","title":"","sessions":[
                  {"id":3,"name":"api","host_alias":"mercury","last_activity_at":90,"proposed":"probably_waiting","claude_status":"idle"}]}],
                "shipped":[],
                "missions":[{"id":7,"name":"Hub federation v2","org_id":2,"waiting_on":{"reason":"question","since":70,"open_cards":2}}]}""",
        )
        val s = today.groups.single().sessions.single()
        assertEquals("probably_waiting", s.proposed)
        assertNull(s.attention)
        // A proposal never puts its group in Waiting.
        assertEquals(TodayBucket.InProgress, bucketOf(listOf(s)))
        val m = today.missions.single()
        assertEquals(TodayMission(id = 7, name = "Hub federation v2", orgId = 2, waitingOn = MissionWait("question", 70, 2)), m)
        // An older hub's answer has neither.
        val old = json.decodeFromString(Today.serializer(), """{"since":0,"now":1,"groups":[],"shipped":[]}""")
        assertTrue(old.missions.isEmpty())
    }

    // ---- G1.1: the wider fleet.form/1 spec ----

    /** docs/form-examples/specs.json's "the newer keys" example, verbatim. */
    private val wideSpec = """{
        "spec": "fleet.form/1", "title": "Deploy", "save_later": true,
        "steps": [
          { "title": "Where it runs", "name": "Where", "fields": [
            { "name": "host", "type": "select", "label": "Host", "other": true, "options": [
              ["mercury", "mercury"],
              { "value": "venus", "label": "venus", "detail": "2 idle", "proposed": { "by": "jev", "reason": "you used it for the last three deploys" } },
              { "value": "mars", "label": "mars", "detail": "next free on main" } ] },
            { "name": "tier", "type": "select", "label": "Tier", "value": "small", "disabled_reason": "Larger tiers need an org admin", "options": [["small", "Small"], ["large", "Large"]] },
            { "name": "summary", "type": "textarea", "label": "Summary", "value": "Ship the hub fix.", "drafted": { "by": "haiku on mercury", "from": "the Jira epic PD-3012" } },
            { "name": "token", "type": "secret", "label": "Token", "secret_note": "Written to a 0600 file on mercury, never shown to the agent" }
          ] },
          { "title": "Check it", "name": "Review", "kind": "review" }
        ]
      }"""

    private fun spec(raw: String): JsonElement = json.parseToJsonElement(raw)

    @Test
    fun the_wider_spec_is_read_with_every_new_key() {
        val form = assertNotNull(readAskForm(spec(wideSpec)), "the phone must draw a contract-15 form")
        assertTrue(form.saveLater)
        val (where, review) = form.steps
        assertEquals("Where", where.name)
        assertFalse(where.review)
        assertTrue(review.review)
        assertEquals("Review", review.name)
        assertTrue(review.fields.isEmpty())

        val host = where.fields[0]
        assertEquals(listOf("mercury" to "mercury", "venus" to "venus", "mars" to "mars"), host.options)
        assertEquals(mapOf("venus" to "2 idle", "mars" to "next free on main"), host.optionDetails)
        assertTrue(host.other)

        val tier = where.fields[1]
        assertEquals("Larger tiers need an org admin", tier.disabledReason)

        val summary = where.fields[2]
        assertEquals("haiku on mercury", summary.draftedBy)
        assertEquals("the Jira epic PD-3012", summary.draftedFrom)

        assertEquals("Written to a 0600 file on mercury, never shown to the agent", where.fields[3].secretNote)
    }

    @Test
    fun a_disabled_field_is_never_required_never_answered_and_never_defaulted() {
        val form = assertNotNull(readAskForm(spec(wideSpec)))
        val tier = form.steps[0].fields[1]
        val defaults = formDefaults(form)
        assertNull(defaults["tier"], "a disabled field's value is not a default")
        assertFalse(fieldMissing(tier.copy(required = true), null))
        val values = defaults + ("tier" to JsonPrimitive("large")) + ("host" to JsonPrimitive("venus"))
        val answers = formAnswers(form, values)
        assertFalse("tier" in answers)
        assertEquals(JsonPrimitive("venus"), answers["host"])
    }

    @Test
    fun another_is_any_text_beside_the_options() {
        val form = assertNotNull(readAskForm(spec(wideSpec)))
        val values = mapOf<String, JsonElement>("host" to JsonPrimitive("jupiter"))
        assertEquals(JsonPrimitive("jupiter"), formAnswers(form, values)["host"])
    }

    @Test
    fun a_review_step_shows_the_answers_so_far() {
        val form = assertNotNull(readAskForm(spec(wideSpec)))
        val values = mapOf<String, JsonElement>(
            "host" to JsonPrimitive("venus"),
            "tier" to JsonPrimitive("large"),
            "summary" to JsonPrimitive("Ship the hub fix."),
            "token" to JsonPrimitive("hunter2"),
        )
        assertEquals(
            listOf("Host" to "venus", "Summary" to "Ship the hub fix.", "Token" to "••••••"),
            reviewLines(form, values, form.steps[1]),
        )
    }

    @Test
    fun a_review_lists_option_labels_and_bools_in_words() {
        val raw = """{"spec":"fleet.form/1","title":"T","steps":[
            {"title":"A","fields":[
              {"name":"env","type":"select","label":"Env","options":[{"value":"stg","label":"Staging"}]},
              {"name":"feat","type":"multiselect","label":"Features","options":[["a","Alpha"],{"value":"b","label":"Beta"}]},
              {"name":"ok","type":"bool","label":"Ok"}]},
            {"title":"Review","kind":"review","fields":[]}]}"""
        val form = assertNotNull(readAskForm(spec(raw)))
        val values = mapOf(
            "env" to JsonPrimitive("stg"),
            "feat" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
            "ok" to JsonPrimitive(true),
        )
        assertEquals(
            listOf("Env" to "Staging", "Features" to "Alpha, Beta", "Ok" to "Yes"),
            reviewLines(form, values, form.steps[1]),
        )
    }

    @Test
    fun a_review_step_is_a_page_of_a_paged_form() {
        val form = assertNotNull(readAskForm(spec(wideSpec)))
        assertEquals(2, dev.claudefleet.mobile.ui.formPages(form, formDefaults(form)).size)
    }

    @Test
    fun a_reply_form_takes_option_objects_and_a_review_step_too() {
        val raw = """{"spec":"fleet.ui/1","kind":"form","form":{"spec":"fleet.form/1","title":"T","steps":[
            {"title":"A","fields":[{"name":"env","type":"select","label":"Env","options":[{"value":"stg","label":"Staging","detail":"cheap"}]}]},
            {"title":"Review","kind":"review"}]}}"""
        val block = assertIs<UiCheck.Ok>(checkUiBlock(raw)).block
        val form = assertIs<UiBlock.Form>(block).form
        assertEquals(mapOf("stg" to "cheap"), form.steps[0].fields[0].optionDetails)
        assertTrue(form.steps[1].review)
    }

    @Test
    fun a_step_kind_this_build_does_not_know_and_a_review_with_fields_are_refused() {
        val unknown = """{"spec":"fleet.form/1","title":"T","steps":[{"title":"A","kind":"table","fields":[{"name":"a","type":"text","label":"A"}]}]}"""
        assertNull(readAskForm(spec(unknown)))
        val reviewWithFields = """{"spec":"fleet.form/1","title":"T","steps":[
            {"title":"A","fields":[{"name":"a","type":"text","label":"A"}]},
            {"title":"R","kind":"review","fields":[{"name":"b","type":"text","label":"B"}]}]}"""
        assertNull(readAskForm(spec(reviewWithFields)))
        // A plain step still needs a field.
        val empty = """{"spec":"fleet.form/1","title":"T","steps":[{"title":"A","fields":[]}]}"""
        assertNull(readAskForm(spec(empty)))
    }

    @Test
    fun an_older_spec_reads_as_before() {
        val raw = """{"spec":"fleet.form/1","title":"T","steps":[{"title":"A","fields":[
            {"name":"env","type":"select","label":"Env","options":[["stg","Staging"]]}]}]}"""
        val form = assertNotNull(readAskForm(spec(raw)))
        assertFalse(form.saveLater)
        val f = form.steps.single().fields.single()
        assertEquals(listOf("stg" to "Staging"), f.options)
        assertTrue(f.optionDetails.isEmpty())
        assertFalse(f.other)
        assertNull(f.disabledReason)
        assertFalse(form.steps.single().review)
    }
}
