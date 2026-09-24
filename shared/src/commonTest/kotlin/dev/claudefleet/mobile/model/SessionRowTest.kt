package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRowTest {
    private val now = 1_790_000_000L

    @Test
    fun a_background_row_is_named_from_friendly_name_then_last_prompt_then_a_short_id() {
        val base = SessionRow(id = 1, tmuxName = "bg:44366faf-ae97-426a-91cd-beaf3c74f1d7")
        assertEquals("Background · 4436", base.displayName)
        assertEquals("fix the tenant header", base.copy(lastPrompt = "fix the tenant header").displayName)
        assertEquals("ADR", base.copy(friendlyName = "ADR", lastPrompt = "x").displayName)
        assertEquals("a".repeat(60), base.copy(lastPrompt = "a".repeat(80)).displayName)
    }

    @Test
    fun a_tmux_row_keeps_its_tmux_name() {
        assertEquals("trust-test", SessionRow(id = 1, tmuxName = "trust-test").displayName)
    }

    /**
     * The age is NOT here. The row's trailing column already draws
     * `relativeTime` from the same `last_activity_at`, so an idle session read
     * `4 min · shell` on the left and `4 min` on the right — one fact, twice, in
     * two different type styles. What is left is what the column cannot say:
     * the activity, or failing that the kind, and nothing at all for the
     * ordinary `work` kind.
     */
    @Test
    fun the_supporting_line_is_the_sanitised_activity_or_the_kind_and_never_the_age() {
        val row = SessionRow(id = 1, tmuxName = "s", lastActivityAt = now - 240, kind = "shell")
        assertEquals("shell", row.supportingLine)
        assertNull(row.copy(kind = "work").supportingLine)
        assertNull(
            row.copy(kind = "work", currentActivity = "⏵⏵ bypass permissions on (shift+tab to cycle)").supportingLine,
        )
        assertEquals("Reading a.kt", row.copy(currentActivity = "Reading a.kt").supportingLine)
        assertEquals("☐ Recreate turanga?", row.copy(currentActivity = "waiting for input: ☐ Recreate turanga?").supportingLine)
    }

    @Test
    fun the_new_fields_parse_and_default() {
        val row = json.decodeFromString(
            SessionRow.serializer(),
            """{"id":7,"tmux_name":"s","last_prompt":"go on","usage_cost_micros":1840000,"usage_model":"sonnet","branch":"feat/x","last_turn_at":10,"started_at":5,"last_stop_at":11,"parent_session_id":3}""",
        )
        assertEquals("go on", row.lastPrompt)
        assertEquals(1_840_000L, row.usageCostMicros)
        assertEquals("sonnet", row.usageModel)
        assertEquals("feat/x", row.branch)
        assertEquals(10L, row.lastTurnAt)
        assertEquals(5L, row.startedAt)
        assertEquals(11L, row.lastStopAt)
        assertEquals(3L, row.parentSessionId)
        val bare = json.decodeFromString(SessionRow.serializer(), """{"id":8}""")
        assertEquals(null, bare.lastPrompt)
        assertEquals(null, bare.usageCostMicros)
    }

    @Test
    fun safe_kill_state_parses_and_is_null_when_absent() {
        val row = json.decodeFromString(SessionRow.serializer(), """{"id":1,"safe_kill_state":"waiting_for_clean"}""")
        assertEquals("waiting_for_clean", row.safeKillState)

        val bare = json.decodeFromString(SessionRow.serializer(), """{"id":9}""")
        assertNull(bare.safeKillState)
    }

    @Test
    fun pending_input_parses_and_is_null_when_absent() {
        val row = json.decodeFromString(
            SessionRow.serializer(),
            """{"id":1,"pending_input":{"kind":"input","options":[{"n":2,"label":"1 day"}]}}""",
        )
        val pending = row.pendingInput!!
        assertEquals("input", pending.kind)
        assertEquals(null, pending.question)
        assertEquals(2, pending.options[0].n)
        assertEquals("1 day", pending.options[0].label)
        assertEquals(false, pending.options[0].selected)

        val bare = json.decodeFromString(SessionRow.serializer(), """{"id":9}""")
        assertNull(bare.pendingInput)
    }

    /**
     * The hub decides who needs a person (`service::attention` in
     * claude-fleet) and stamps its answer on every listed row and every
     * `session:*` frame. That answer wins, reason and all, whatever the
     * row's other columns say.
     */
    @Test
    fun the_hubs_needs_attention_is_the_answer() {
        val row = json.decodeFromString(
            SessionRow.serializer(),
            """{"id":1,"claude_status":"working","needs_attention":{"reason":"lifecycle","since":42}}""",
        )
        assertEquals("lifecycle", row.attentionReason)
        assertEquals(42L, row.attention?.since)
        assertTrue(row.needsAttention)
    }

    /**
     * A hub released before it stamped the field sends none. The fallback is
     * the hub's own rule, ported exactly — in its order, which is the
     * precedence — so an older hub and a newer one agree about every row.
     */
    @Test
    fun without_the_hubs_answer_the_row_applies_the_hubs_rule() {
        val calm = SessionRow(id = 1, status = "running", claudeStatus = "working")
        assertNull(calm.attentionReason)
        assertFalse(calm.needsAttention)
        assertEquals("waiting", calm.copy(claudeStatus = "blocked", stuckKind = "oom").attentionReason)
        assertEquals("stuck", calm.copy(stuckKind = "press_enter").attentionReason)
        assertEquals("failed", calm.copy(claudeStatus = "failed").attentionReason)
        assertEquals("lifecycle", calm.copy(safeKillState = "failed").attentionReason)
        assertEquals("lifecycle", calm.copy(safeKillState = "requested").attentionReason)
        assertNull(calm.copy(safeKillState = "waiting_for_clean").attentionReason)
        assertEquals("lifecycle", calm.copy(status = "ghost").attentionReason)
        assertEquals("lifecycle", calm.copy(lostAt = 5).attentionReason)
        // A Claude running outside fleet is read-only here: never a person's job.
        assertNull(calm.copy(kind = "external", claudeStatus = "blocked").attentionReason)
    }

    /** The phone view from a hub with the work graph: `ok_json_compact` drops what is empty. */
    @Test
    fun work_and_the_suggestion_parse_with_every_optional_field_absent() {
        val row = json.decodeFromString(
            SessionRow.serializer(),
            """{"id":1,"work":{"link_id":4,"key":"PAY-7","title":"","source":"branch"},""" +
                """"work_suggested":{"link_id":5,"item_id":9,"key":"PAY-9","title":"Ledger","source":"prompt",""" +
                """"state":"suggested","status_category":"blocked_by_legal","unavailable":true,"suggestions":3}}""",
        )
        val work = row.work!!
        assertEquals(4L, work.linkId)
        assertEquals("PAY-7", work.label)
        assertNull(work.statusCategory)
        assertEquals("", work.state, "a link older than M4 has no state")
        val guess = row.workSuggested!!
        assertEquals(StatusCategory.Unknown, guess.statusCategory, "a new wire value must not fail the row")
        assertTrue(guess.unavailable)
        assertEquals(3, guess.suggestions)
    }

    @Test
    fun a_row_without_work_has_none() {
        val bare = json.decodeFromString(SessionRow.serializer(), """{"id":9}""")
        assertNull(bare.work)
        assertNull(bare.workSuggested)
    }

    @Test
    fun the_group_key_is_the_key_upper_cased_else_the_title() {
        assertEquals("PAY-7", WorkSummary(key = "pay-7").groupKey)
        assertEquals("BILLING MIGRATION", WorkSummary(title = "billing migration").groupKey)
        assertNull(WorkSummary().groupKey)
    }
}
