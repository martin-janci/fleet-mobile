package dev.claudefleet.mobile.ui.components

import dev.claudefleet.mobile.model.ConvContext
import dev.claudefleet.mobile.model.SessionRow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [statusStripText] is the pure text builder behind [StatusStrip] — the
 * composable only draws whatever this returns, so every case that matters
 * lives here rather than behind a device screenshot.
 */
class StatusStripTest {

    private fun row(
        status: String? = "working",
        lastTurnAt: Long? = null,
        lastStopAt: Long? = null,
        startedAt: Long? = null,
        contextPct: Double? = null,
        usageCostMicros: Long? = null,
        usageModel: String? = null,
    ) = SessionRow(
        id = 1,
        tmuxName = "s",
        claudeStatus = status,
        lastTurnAt = lastTurnAt,
        lastStopAt = lastStopAt,
        startedAt = startedAt,
        contextPct = contextPct,
        usageCostMicros = usageCostMicros,
        usageModel = usageModel,
    )

    @Test
    fun the_strip_reads_working_with_elapsed_ctx_cost_and_model() {
        val row = SessionRow(
            id = 1,
            tmuxName = "s",
            claudeStatus = "working",
            lastTurnAt = 1_000,
            usageCostMicros = 1_840_000,
            usageModel = "sonnet",
        )
        assertEquals(
            "● working 2 min · ctx 62 % · $1.84 · sonnet",
            statusStripText(row, ConvContext(pct = 62.4), nowSeconds = 1_134),
        )
        assertEquals(
            "idle since 2 h",
            statusStripText(row.copy(claudeStatus = "idle", lastStopAt = 1_000), null, nowSeconds = 8_200),
        )
    }

    @Test
    fun a_null_row_reads_as_an_empty_strip() {
        assertEquals("", statusStripText(null, null, nowSeconds = 100))
    }

    @Test
    fun working_falls_back_to_started_at_when_there_is_no_turn_yet() {
        val r = row(status = "working", lastTurnAt = null, startedAt = 1_000)
        assertEquals("● working 2 min", statusStripText(r, null, nowSeconds = 1_134))
    }

    @Test
    fun ctx_falls_back_to_the_rows_own_context_pct_when_the_conversation_has_none() {
        val r = row(status = "working", lastTurnAt = 1_000, contextPct = 40.0)
        assertEquals("● working 2 min · ctx 40 %", statusStripText(r, null, nowSeconds = 1_134))
    }

    @Test
    fun a_stale_context_says_so() {
        val r = row(status = "working", lastTurnAt = 1_000)
        assertEquals(
            "● working 2 min · ctx 62 % (stale)",
            statusStripText(r, ConvContext(pct = 62.4, stale = true), nowSeconds = 1_134),
        )
    }

    @Test
    fun idle_alone_with_no_stop_time_still_reads() {
        val r = row(status = "idle", lastStopAt = null)
        assertEquals("idle", statusStripText(r, null, nowSeconds = 100))
    }

    @Test
    fun a_plain_status_with_no_special_case_shows_the_word_alone() {
        val r = row(status = "blocked")
        assertEquals("blocked", statusStripText(r, null, nowSeconds = 100))
    }

    @Test
    fun no_claude_status_reads_as_an_empty_strip() {
        val r = row(status = null)
        assertEquals("", statusStripText(r, null, nowSeconds = 100))
    }
}
