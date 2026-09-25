package dev.claudefleet.mobile.ui.components

import dev.claudefleet.mobile.model.ConvContext
import dev.claudefleet.mobile.model.SessionRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
            "working 2m · ctx 62% · $1.84 · sonnet",
            statusStripText(row, ConvContext(pct = 62.4), nowSeconds = 1_134),
        )
        assertEquals(
            "idle since 2h",
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
        assertEquals("working 2m", statusStripText(r, null, nowSeconds = 1_134))
    }

    @Test
    fun ctx_falls_back_to_the_rows_own_context_pct_when_the_conversation_has_none() {
        val r = row(status = "working", lastTurnAt = 1_000, contextPct = 40.0)
        assertEquals("working 2m · ctx 40%", statusStripText(r, null, nowSeconds = 1_134))
    }

    @Test
    fun a_stale_context_says_so() {
        val r = row(status = "working", lastTurnAt = 1_000)
        assertEquals(
            "working 2m · ctx 62% (stale)",
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

    // ─── fitting a phone ──────────────────────────────────────────────────────
    // The strip is one line on a screen that may be 320 dp wide. These are the
    // rules that keep the whole turn — how long, how full, how much — on it.

    @Test
    fun the_model_keeps_only_the_part_that_tells_two_models_apart() {
        // The hub reports `claude-opus-5`; the prefix is on every model there
        // has ever been, so it is seven characters that distinguish nothing.
        // The desktop header drops it the same way.
        val r = row(status = "working", lastTurnAt = 1_000, usageModel = "claude-opus-5")
        assertEquals("working 2m · opus-5", statusStripText(r, null, nowSeconds = 1_134))
    }

    @Test
    fun elapsed_is_compact_at_every_scale() {
        fun at(seconds: Long) = statusStripText(row(lastTurnAt = 0), null, nowSeconds = seconds)
        assertEquals("working now", at(30))
        assertEquals("working 2m", at(120))
        assertEquals("working 3h", at(3 * 3600L))
        assertEquals("working 4d", at(4 * 86_400L))
    }

    @Test
    fun the_model_is_dropped_whole_rather_than_clipped_on_a_narrow_screen() {
        // Least valuable of the four, so it is the one that goes — the same
        // order the desktop header sheds facts in. Everything else stays.
        val r = row(
            status = "working",
            lastTurnAt = 1_000,
            contextPct = 91.0,
            usageCostMicros = 13_220_000,
            usageModel = "claude-opus-5",
        )
        val wide = statusStripText(r, null, nowSeconds = 1_134, includeModel = true)
        val narrow = statusStripText(r, null, nowSeconds = 1_134, includeModel = false)
        assertEquals("working 2m · ctx 91% · $13.22 · opus-5", wide)
        assertEquals("working 2m · ctx 91% · $13.22", narrow)
        // The cost is what the reader asked to see; it survives the squeeze.
        assertTrue(narrow.contains("$13.22"))
        assertFalse(narrow.contains("opus"))
    }

    @Test
    fun the_model_is_kept_only_where_the_whole_line_has_room() {
        // Below the threshold the four-fact line does not fit a 320 dp screen
        // once its 32 dp of padding and the status dot are out.
        assertTrue(stripFitsModel(360.0 - 32))
        assertFalse(stripFitsModel(320.0 - 32))
    }

    @Test
    fun a_costless_turn_says_nothing_about_cost() {
        // `usage.enabled` is off on the hub, or the transcript carried no
        // usage yet: no "$0.00", which reads as a fact rather than a gap.
        val r = row(status = "working", lastTurnAt = 1_000, contextPct = 40.0)
        assertEquals("working 2m · ctx 40%", statusStripText(r, null, nowSeconds = 1_134))
    }
}
