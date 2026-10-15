package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone's take on Control as a Thread room (claude-fleet #913, #914, #921): its pure rules. */
class ControlRoomModelTest {

    @Test
    fun a_digest_folds_into_its_events_and_a_person_quoting_one_keeps_their_words() {
        val digest = "[fleet events] What changed since the last note. Nothing here asks for a reply.\n" +
            "- pine (#12) finished a turn\n- host htz is low on memory (mem 94%)\n- … and 3 more."
        val note = parseFleetPrompt(digest)!!
        assertTrue(note.digest)
        assertEquals(listOf(12L, null), note.events.map { it.sessionId })
        assertEquals(3, note.more)
        assertEquals("Fleet · 5 events", note.line)
        assertTrue(note.header.startsWith("What changed"))

        val restart = parseFleetPrompt("[fleet] You were restarted after your process stopped.")!!
        assertFalse(restart.digest)
        assertEquals("Fleet · 1 event", restart.line)

        assertNull(parseFleetPrompt("I saw [fleet events] earlier"))
        assertNull(parseFleetPrompt(null))
    }

    @Test
    fun threads_group_what_control_handed_out_and_a_missions_worker_rides_with_its_mission() {
        val since = 1_000L
        val rows = listOf(
            SessionRow(id = 1, tmuxName = "control", origin = "operator"),
            SessionRow(id = 2, tmuxName = "pine", origin = "operator", claudeStatus = "working", lastActivityAt = 1_500),
            SessionRow(id = 3, tmuxName = "oak", claudeStatus = "blocked", lastActivityAt = 1_400),
            SessionRow(id = 4, tmuxName = "worker", origin = "mission", originRef = "9", claudeStatus = "working"),
            SessionRow(id = 5, tmuxName = "elm", origin = "operator", claudeStatus = "completed", lastStopAt = 1_200),
            SessionRow(id = 6, tmuxName = "old", origin = "operator", claudeStatus = "idle", lastStopAt = 10),
        )
        val handoffs = listOf(
            ControlHandoff(id = 1, at = 1_300, kind = "session", sessionId = 3),
            ControlHandoff(id = 2, at = 1_350, kind = "mission", missionId = 9, missionName = "Ship it"),
            ControlHandoff(id = 3, at = 1_360, kind = "session", sessionId = 4),
        )
        val missions = listOf(Mission(id = 9, name = "Ship it", state = "active", total = 4, done = 1, updatedAt = 1_600))
        val threads = buildThreads(rows, handoffs, missions, operatorId = 1, since = since)
        assertEquals(listOf("m:9", "s:2", "s:3", "s:5", "s:6"), threads.map { it.key })
        val by = threads.associateBy { it.key }
        assertEquals(ThreadGroup.WORKING, by.getValue("m:9").group)
        assertEquals("Mission · 1 of 4 done", by.getValue("m:9").line)
        assertEquals(ThreadGroup.NEEDS, by.getValue("s:3").group)
        assertEquals(ThreadGroup.DONE, by.getValue("s:5").group)
        assertEquals(ThreadGroup.EARLIER, by.getValue("s:6").group)
        assertEquals("1 needs you · 2 working · 1 done today", threadsSummary(threads))
        assertEquals(listOf(ThreadGroup.NEEDS, ThreadGroup.WORKING, ThreadGroup.DONE, ThreadGroup.EARLIER), groupThreads(threads).map { it.first })
        assertNull(threadsSummary(emptyList()))
    }

    @Test
    fun a_missions_graph_lays_its_steps_out_by_wave_and_marks_where_it_is() {
        val d = MissionDetail(
            mission = Mission(id = 9, name = "Ship it"),
            items = listOf(MissionItem(1, title = "Plan"), MissionItem(2, title = "Build"), MissionItem(3, title = "Test"), MissionItem(4, title = "Drop")),
            graph = MissionGraph(
                listOf(
                    MissionNode(1, "done", 1),
                    MissionNode(2, "running", 2),
                    MissionNode(3, "proposed", 2),
                    MissionNode(4, "rejected", 3),
                ),
            ),
        )
        val g = graphFromMission(d)!!
        assertEquals(listOf(listOf("Plan"), listOf("Build", "Test")), g.waves.map { w -> w.map { it.title } })
        assertEquals("1 of 3 done", g.progress)
        assertEquals("Test", g.waves.flatten().single { it.current }.title, "the step that needs you is where it is")
        assertNull(graphFromMission(d.copy(graph = MissionGraph())))
    }

    @Test
    fun a_proposed_tree_takes_its_waves_from_depends_on_and_survives_a_cycle() {
        val h = ControlHandoff(
            id = 1,
            kind = "tree",
            item = HandoffItem(10, title = "Refactor"),
            items = listOf(
                HandoffItem(1, title = "A", status = "done"),
                HandoffItem(2, title = "B", status = "in_progress", dependsOn = listOf(1)),
                HandoffItem(3, title = "C", proposalState = "proposed", dependsOn = listOf(2, 4)),
                HandoffItem(4, title = "D", dependsOn = listOf(3)),
            ),
        )
        val g = graphFromTree(h)!!
        assertEquals("Refactor", g.title)
        assertEquals(listOf("A"), g.waves.first().map { it.title })
        assertEquals(4, g.waves.flatten().size)
        assertEquals("B", g.waves.flatten().single { it.current }.title)
        assertNull(graphFromTree(h.copy(items = emptyList())))
    }

    @Test
    fun the_dial_reads_its_four_settings_and_writes_only_what_differs() {
        val brief = mapOf(KEY_MAX_LEVEL to "2", KEY_AUTO_CONTINUE to "true", KEY_AUTO_CONTINUE_MINUTES to "10", KEY_REACT_TO_EVENTS to "true")
        assertEquals(Autonomy.BRIEF, autonomyOf(brief))
        assertEquals(Autonomy.ASK, autonomyOf(brief + (KEY_MAX_LEVEL to "1") + (KEY_AUTO_CONTINUE to "false") + (KEY_AUTO_CONTINUE_MINUTES to "7")))
        assertNull(autonomyOf(brief + (KEY_AUTO_CONTINUE_MINUTES to "7")))
        assertEquals("Custom", autonomyWord(null))
        assertEquals(listOf(KEY_MAX_LEVEL to "3", KEY_AUTO_CONTINUE_MINUTES to "2"), autonomyWrites(Autonomy.AUTOPILOT, brief))
        assertEquals(listOf(KEY_MAX_LEVEL to "1", KEY_AUTO_CONTINUE to "false"), autonomyWrites(Autonomy.ASK, brief))
    }
}
