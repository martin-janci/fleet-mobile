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
}
