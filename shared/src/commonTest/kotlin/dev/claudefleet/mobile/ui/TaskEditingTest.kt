package dev.claudefleet.mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskEditingTest {

    @Test
    fun a_due_date_is_blank_or_a_real_day() {
        assertNull(dueDateError(""))
        assertNull(dueDateError(" 2026-10-23 "))
        assertNull(dueDateError("2028-02-29"))
        assertNotNull(dueDateError("2026-02-29"))
        assertNotNull(dueDateError("2026-13-01"))
        assertNotNull(dueDateError("23.10.2026"))
        assertNotNull(dueDateError("soon"))
    }

    @Test
    fun a_title_is_required_and_bounded() {
        assertNotNull(taskTitleError("  "))
        assertNull(taskTitleError("Fix login"))
        assertNotNull(taskTitleError("x".repeat(TASK_TITLE_MAX + 1)))
    }

    @Test
    fun assignees_are_trimmed_and_deduplicated_in_any_case() {
        assertEquals(listOf("Ana", "bo"), parseAssigneesText(" Ana , bo,, ana ,Bo "))
        assertEquals(emptyList(), parseAssigneesText("  "))
    }

    @Test
    fun an_edit_carries_only_what_changed() {
        val before = TaskEditFields(title = "Fix login", notes = "old", assignees = listOf("Ana"), dueAt = "2026-10-16")
        assertTrue(taskEditOf("Fix login", "old ", "Ana", "2026-10-16", before, notesLocked = false).isEmpty)
        assertEquals(
            TaskEdit(title = "Fix the login", notes = "", assignees = emptyList(), dueAt = ""),
            taskEditOf(" Fix the login ", "", "", "", before, notesLocked = false),
        )
        // A delegated job's description is its prompt: never sent.
        assertEquals(TaskEdit(), taskEditOf("Fix login", "changed", "Ana", "2026-10-16", before, notesLocked = true))
    }

    @Test
    fun a_priority_is_sent_when_it_changed_and_none_clears_it() {
        val before = TaskEditFields(title = "Fix login", priority = "high")
        assertTrue(taskEditOf("Fix login", "", "", "", before, notesLocked = false, priority = "high").isEmpty)
        assertEquals(TaskEdit(priority = "urgent"), taskEditOf("Fix login", "", "", "", before, notesLocked = false, priority = "urgent"))
        assertEquals(TaskEdit(priority = ""), taskEditOf("Fix login", "", "", "", before, notesLocked = false, priority = ""))
        assertEquals(listOf("", "urgent", "high", "medium", "low"), TASK_PRIORITIES.map { it.first })
    }

    @Test
    fun labels_are_sent_tidied_when_they_changed_and_empty_clears_them() {
        val before = TaskEditFields(title = "Fix login", labels = listOf("ui", "auth"))
        assertTrue(taskEditOf("Fix login", "", "", "", before, notesLocked = false, labels = "ui, auth").isEmpty)
        assertEquals(TaskEdit(labels = listOf("ui", "api")), taskEditOf("Fix login", "", "", "", before, notesLocked = false, labels = " ui, api,, UI "))
        assertEquals(TaskEdit(labels = emptyList()), taskEditOf("Fix login", "", "", "", before, notesLocked = false, labels = ""))
    }

    /** The desktop's rule: a new size carries its unit, the same size in another unit re-units it, a blank sends 0 alone. */
    @Test
    fun an_estimate_carries_its_unit_and_blank_clears_it() {
        val before = TaskEditFields(title = "Fix login", estimate = 3.0, estimateUnit = "points")
        assertEquals("3", estimateFieldText(3.0))
        assertEquals("2.5", estimateFieldText(2.5))
        assertEquals("", estimateFieldText(0.0))
        assertTrue(taskEditOf("Fix login", "", "", "", before, notesLocked = false, estimate = "3").isEmpty)
        assertEquals(
            TaskEdit(estimate = 5.0, estimateUnit = "points"),
            taskEditOf("Fix login", "", "", "", before, notesLocked = false, estimate = "5"),
        )
        assertEquals(
            TaskEdit(estimateUnit = "hours"),
            taskEditOf("Fix login", "", "", "", before, notesLocked = false, estimate = "3", unit = "hours"),
        )
        assertEquals(TaskEdit(estimate = 0.0), taskEditOf("Fix login", "", "", "", before, notesLocked = false, estimate = " ", unit = "hours"))
        // A first estimate on a task with none, in hours.
        val none = TaskEditFields(title = "Fix login")
        assertEquals(
            TaskEdit(estimate = 4.0, estimateUnit = "hours"),
            taskEditOf("Fix login", "", "", "", none, notesLocked = false, estimate = "4", unit = "hours"),
        )
        // Switching the unit with no estimate sends nothing.
        assertTrue(taskEditOf("Fix login", "", "", "", none, notesLocked = false, unit = "hours").isEmpty)
    }

    @Test
    fun labels_and_estimates_past_the_hubs_limits_are_named_before_saving() {
        assertNull(taskSizeError("ui, auth", "3.5"))
        assertNull(taskSizeError("", ""))
        assertEquals("At most 20 labels.", taskSizeError((1..21).joinToString(",") { "l$it" }, ""))
        assertEquals("A label is at most 40 characters.", taskSizeError("x".repeat(41), ""))
        assertEquals("An estimate is a number from 0 to 1000.", taskSizeError("", "lots"))
        assertEquals("An estimate is a number from 0 to 1000.", taskSizeError("", "1001"))
        assertEquals("An estimate is a number from 0 to 1000.", taskSizeError("", "-1"))
    }
}
