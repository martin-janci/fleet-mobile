package dev.claudefleet.mobile.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The fold of `start:progress` frames, the desktop's `foldStartProgress` (`src/lib/start_steps.ts`). */
class StartProgressTest {

    private fun Map<String, StartStepState>.states() = START_STEPS.map { this[it] }

    @Test
    fun the_steps_are_the_hubs_three_in_order() {
        assertEquals(listOf("worktree", "tmux", "agent"), START_STEPS)
        assertEquals(List(3) { StartStepState.PENDING }, NO_START_STEPS.states())
    }

    @Test
    fun a_step_that_starts_closes_the_ones_before_it() {
        val s = NO_START_STEPS.folding("agent", "started")
        assertEquals(listOf(StartStepState.DONE, StartStepState.DONE, StartStepState.STARTED), s.states())
    }

    @Test
    fun a_step_only_moves_forward_and_warned_reads_as_done() {
        val done = NO_START_STEPS.folding("worktree", "warned")
        assertEquals(StartStepState.DONE, done["worktree"])
        assertSame(done, done.folding("worktree", "started"), "a late started never undoes its done")
    }

    @Test
    fun a_failed_step_stays_failed_and_unknowns_change_nothing() {
        val failed = NO_START_STEPS.folding("tmux", "failed")
        assertEquals(listOf(StartStepState.DONE, StartStepState.FAILED, StartStepState.PENDING), failed.states())
        assertSame(failed, failed.folding("tmux", "done"))
        assertSame(failed, failed.folding("ssh", "done"))
        assertSame(failed, failed.folding("agent", "paused"))
    }

    @Test
    fun a_token_has_the_shape_the_hub_accepts() {
        val token = newStartToken(1_000L, Random(7))
        assertTrue(token.startsWith("st-rs-"), token)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{1,64}")), token)
        assertTrue(newStartToken(1_000L, Random(7)) == token, "deterministic for a seeded random")
        assertTrue(newStartToken(1_000L, Random(8)) != token, "and different per start")
    }
}
