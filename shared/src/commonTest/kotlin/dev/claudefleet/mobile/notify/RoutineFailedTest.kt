package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.model.FailingRoutine
import dev.claudefleet.mobile.model.Routine
import dev.claudefleet.mobile.model.RoutineRun
import dev.claudefleet.mobile.store.FakePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The matrix's Routine failed row on the phone (review r19, R19-5). */
class RoutineFailedTest {
    private fun failing(runId: Long, routineId: Long = 7, reason: String? = "exit 1") =
        FailingRoutine(Routine(routineId, "Nightly deps", hostAlias = "pine"), RoutineRun(runId, routineId, state = "failed", reason = reason))

    @Test
    fun the_first_look_is_a_baseline_and_says_nothing() {
        val (alerts, seen) = routineFailedAlerts(null, listOf(failing(1)))
        assertEquals(emptyList(), alerts)
        assertEquals(setOf(1L), seen)
    }

    @Test
    fun a_new_failed_run_is_news_once() {
        val (alerts, seen) = routineFailedAlerts(emptySet(), listOf(failing(2)))
        assertEquals(listOf(RoutineFailedAlert(2, 7, "Routine Nightly deps failed", "exit 1")), alerts)
        assertEquals(emptyList(), routineFailedAlerts(seen, listOf(failing(2))).first, "still failing is not news")
    }

    @Test
    fun a_retry_that_fails_again_is_news_and_a_cleared_run_is_forgotten() {
        assertEquals(listOf(3L), routineFailedAlerts(setOf(2L), listOf(failing(3))).first.map { it.runId })
        assertEquals(emptySet(), routineFailedAlerts(setOf(2L), emptyList()).second)
    }

    @Test
    fun a_run_with_no_reason_says_its_host() {
        assertEquals("pine", routineFailedAlerts(emptySet(), listOf(failing(4, reason = " "))).first.single().text)
    }

    @Test
    fun the_phone_column_and_the_failed_switch_decide() {
        assertEquals("routine_failed", notifyStateOf(ROUTINE_FAILED_REASON))
        assertEquals(NotifyKind.FAILED, notifyKindOf(ROUTINE_FAILED_REASON))
        assertTrue(FleetNotify(setOf("needs_you", "routine_failed")).allows(ROUTINE_FAILED_REASON, 600))
        assertFalse(FleetNotify(setOf("needs_you", "failed")).allows(ROUTINE_FAILED_REASON, 600))
        val prefs = FakePrefs()
        prefs.writeNotifyKinds(prefs.notifyKinds().with(NotifyKind.FAILED, on = false))
        assertFalse(prefs.notifyAllows(ROUTINE_FAILED_REASON, 600))
    }

    @Test
    fun the_seen_runs_are_kept_and_none_is_not_unread() {
        val prefs = FakePrefs()
        assertNull(prefs.readRoutineSeen())
        prefs.writeRoutineSeen(emptySet())
        assertEquals(emptySet(), prefs.readRoutineSeen())
        prefs.writeRoutineSeen(setOf(5L, 6L))
        assertEquals(setOf(5L, 6L), prefs.readRoutineSeen())
    }
}
