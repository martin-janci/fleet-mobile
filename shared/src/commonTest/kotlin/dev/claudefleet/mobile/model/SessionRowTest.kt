package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
