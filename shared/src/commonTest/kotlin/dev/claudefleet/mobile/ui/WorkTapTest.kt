package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.LinkState
import dev.claudefleet.mobile.model.WorkTask
import dev.claudefleet.mobile.model.WorkTaskLink
import dev.claudefleet.mobile.store.FakePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a tap on a Work row opens (This phone): the ticket by default; under
 * Session, the task's live session, and the ticket when none is live.
 */
class WorkTapTest {

    private fun task(vararg links: WorkTaskLink) = WorkTask(taskId = "item:5", key = "FLEET-5", sessions = links.toList())

    private fun link(id: Long, state: LinkState = LinkState.Active, session: Long? = id, primary: Boolean = false) =
        WorkTaskLink(linkId = id, state = state, sessionId = session, primary = primary)

    @Test
    fun the_live_session_is_the_primary_one_first() {
        assertEquals(12L, liveSessionOf(task(link(11), link(12, primary = true))))
        assertEquals(11L, liveSessionOf(task(link(11), link(12))))
        assertEquals(13L, liveSessionOf(task(link(11, state = LinkState.Ended, primary = true), link(13))))
        assertNull(liveSessionOf(task(link(11, state = LinkState.Ended), link(12, state = LinkState.Suggested))))
        assertNull(liveSessionOf(task(link(11, session = null))), "an active link with no session is not live")
        assertNull(liveSessionOf(task()))
    }

    @Test
    fun ticket_always_opens_the_ticket() {
        assertEquals(WorkTapTarget.Ticket("item:5"), workTapTarget(task(link(11)), WorkTapChoice.TICKET))
        assertEquals(WorkTapTarget.Ticket("item:5"), workTapTarget(task(), WorkTapChoice.TICKET))
    }

    @Test
    fun session_opens_the_session_and_falls_back_to_the_ticket() {
        assertEquals(WorkTapTarget.Session(11), workTapTarget(task(link(11)), WorkTapChoice.SESSION))
        assertEquals(WorkTapTarget.Ticket("item:5"), workTapTarget(task(link(11, state = LinkState.Ended)), WorkTapChoice.SESSION))
        assertEquals(WorkTapTarget.Ticket("item:5"), workTapTarget(task(), WorkTapChoice.SESSION))
    }

    @Test
    fun a_row_tap_goes_through_the_screens_handlers() {
        val opened = mutableListOf<String>()
        val handlers = MyWorkHandlers(onOpenTask = { opened += "task $it" }, onOpenSession = { opened += "session $it" })
        handlers.openRow(task(link(11)), WorkTapChoice.TICKET)
        handlers.openRow(task(link(11)), WorkTapChoice.SESSION)
        handlers.openRow(task(), WorkTapChoice.SESSION)
        assertEquals(listOf("task item:5", "session 11", "task item:5"), opened)
    }

    @Test
    fun the_preference_is_ticket_until_changed_and_is_kept() {
        val prefs = FakePrefs()
        assertEquals(WorkTapChoice.TICKET, PhoneSettings(prefs).workTap.value)
        PhoneSettings(prefs).setWorkTap(WorkTapChoice.SESSION)
        assertEquals(WorkTapChoice.SESSION, PhoneSettings(prefs).workTap.value, "read back by a new instance")
        prefs.putStringList("phone.work_tap", listOf("something-newer"))
        assertEquals(WorkTapChoice.TICKET, PhoneSettings(prefs).workTap.value, "an unknown value is the default")
    }
}
