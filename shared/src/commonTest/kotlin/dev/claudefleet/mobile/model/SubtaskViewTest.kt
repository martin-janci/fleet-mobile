package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlin.test.Test
import kotlin.test.assertEquals

/** A task page's subtasks, as the hub's `work { task }` sends them (claude-fleet `SubtaskView`). */
class SubtaskViewTest {
    /** 2026-11-04. */
    private val today = 20_761L

    @Test
    fun the_subtasks_decode_and_an_older_hub_sends_none() {
        val d = json.decodeFromString(
            TaskDetail.serializer(),
            """{"task":{"task_id":"item:1","title":"Ship v1"},"subtasks":[
              {"task_id":"item:2","item_id":2,"key":"FLT-2","title":"Changelog","origin":"manual","status":"todo",
               "live_sessions":1,"job_state":null,"due_at":"2026-10-30","priority":"urgent","future":true},
              {"task_id":"item:3","item_id":3,"title":"Tag","origin":"agent","live_sessions":0}
            ]}""",
        )
        assertEquals(listOf("item:2", "item:3"), d.subtasks.map { it.taskId })
        assertEquals(SubtaskView("item:2", 2, "FLT-2", "Changelog", "manual", "todo", 1, "2026-10-30", "urgent"), d.subtasks[0])
        assertEquals(emptyList(), json.decodeFromString(TaskDetail.serializer(), """{"task":{"task_id":"item:1"}}""").subtasks)
    }

    @Test
    fun a_subtask_line_says_key_priority_due_and_live_sessions() {
        val s = SubtaskView("item:2", 2, "FLT-2", "Changelog", "manual", "todo", 1, "2026-10-30", "urgent")
        assertEquals("FLT-2 · Urgent · Overdue Oct 30 · 1 live", subtaskLine(s, today))
        assertEquals("High · Due Fri", subtaskLine(s.copy(key = null, priority = "high", dueAt = "2026-11-06", liveSessions = 0), today))
        // Medium is the usual case; a done subtask's date is history.
        assertEquals("FLT-2", subtaskLine(s.copy(priority = "medium", status = "done", liveSessions = 0), today))
        assertEquals("2 live", subtaskLine(s.copy(key = null, priority = null, dueAt = null, liveSessions = 2), today))
        assertEquals(listOf("✓", "◐", "○", "○"), listOf("done", "in_progress", "todo", null).map(::subtaskMark))
    }
}
