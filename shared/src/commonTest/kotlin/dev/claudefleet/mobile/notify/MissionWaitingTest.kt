package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.MissionWait
import dev.claudefleet.mobile.store.FakePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A mission that waits on a person, as a notification (gap plan G5.7). */
class MissionWaitingTest {
    private fun waiting(id: Long, reason: String = "sign_grant", since: Long = 100) =
        Mission(id = id, name = "Ship 1.4", state = "active", waitingOn = MissionWait(reason, since = since))

    @Test
    fun the_first_look_is_a_baseline_and_says_nothing() {
        val (alerts, seen) = missionWaitAlerts(null, listOf(waiting(1)))
        assertEquals(emptyList(), alerts)
        assertEquals(mapOf(1L to "sign_grant:100"), seen)
    }

    @Test
    fun a_new_wait_is_news_once_and_a_later_wait_on_the_same_mission_again() {
        val (alerts, seen) = missionWaitAlerts(emptyMap(), listOf(waiting(1)))
        assertEquals(listOf(MissionWaitAlert(1, "Ship 1.4", "Mission waits for you to sign the autonomy grant", "sign_grant")), alerts)
        assertEquals(emptyList(), missionWaitAlerts(seen, listOf(waiting(1))).first, "still waiting is not news")
        val again = missionWaitAlerts(seen, listOf(waiting(1, reason = "question", since = 500))).first
        assertEquals(listOf("Mission waits for you to answer its question"), again.map { it.text })
    }

    @Test
    fun a_paused_or_answered_mission_is_forgotten_and_says_nothing() {
        val paused = waiting(2).copy(state = "paused")
        val answered = Mission(id = 3, name = "", state = "active")
        val (alerts, seen) = missionWaitAlerts(emptyMap(), listOf(paused, answered))
        assertEquals(emptyList(), alerts)
        assertEquals(emptyMap(), seen)
        assertEquals("Mission 4", missionWaitAlerts(emptyMap(), listOf(waiting(4).copy(name = " "))).first.single().title)
    }

    @Test
    fun the_seen_waits_survive_in_prefs_and_nothing_stored_is_no_look_yet() {
        val prefs = FakePrefs()
        assertNull(prefs.readMissionSeen())
        prefs.writeMissionSeen(emptyMap())
        assertEquals(emptyMap(), prefs.readMissionSeen(), "a look that saw no waits is still a look")
        prefs.writeMissionSeen(mapOf(1L to "confirm:300", 2L to "question:5"))
        assertEquals(mapOf(1L to "confirm:300", 2L to "question:5"), prefs.readMissionSeen())
    }

    @Test
    fun a_waiting_mission_is_a_needs_you_notification() {
        assertEquals(NotifyKind.NEEDS_YOU, notifyKindOf(MISSION_WAITING_REASON))
        assertEquals("needs_you", notifyStateOf(MISSION_WAITING_REASON))
    }

    /** MobileControl: "Hub federation v2 · Mission waits for you to sign the autonomy grant · Review grant". */
    @Test
    fun the_button_is_named_for_what_the_mission_waits_on_and_only_opens() {
        val grant = missionWaitAlerts(emptyMap(), listOf(waiting(1))).first.single()
        assertEquals(listOf("Review grant"), missionWaitActions(grant).map { it.label })
        assertEquals(listOf(NotifyActionKind.Open), missionWaitActions(grant).map { it.kind })
        assertEquals(listOf(MISSION_ACTION_REVIEW), missionWaitActions(grant).map { it.id })
        val question = missionWaitAlerts(emptyMap(), listOf(waiting(2, reason = "question"))).first.single()
        assertEquals(listOf("Answer"), missionWaitActions(question).map { it.label })
        val confirm = missionWaitAlerts(emptyMap(), listOf(waiting(3, reason = "confirm"))).first.single()
        assertEquals(listOf("Review"), missionWaitActions(confirm).map { it.label })
        // iOS fixes a button's word per category: one category per word.
        assertEquals(3, missionCategories().size)
        assertEquals(listOf("Review grant"), missionCategories().getValue(missionCategory("sign_grant")).map { it.label })
        assertEquals(listOf("Answer"), missionCategories().getValue(missionCategory("question")).map { it.label })
    }
}
