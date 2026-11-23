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
        assertEquals(listOf(ThreadGroup.NEEDS, ThreadGroup.WORKING, ThreadGroup.DONE, ThreadGroup.EARLIER), groupThreads(threads).map { it.first })
    }

    @Test
    fun a_needs_you_thread_says_why_and_a_lost_one_needs_nobody() {
        val rows = listOf(
            SessionRow(
                id = 2, tmuxName = "task-573", origin = "operator", hostAlias = "mefistos", claudeStatus = "working",
                lastActivityAt = 1_500, attention = Attention(reason = "host_down", state = "blocked"),
            ),
            SessionRow(
                id = 3, tmuxName = "gone", origin = "operator", hostAlias = "htz", claudeStatus = "idle", lostAt = 900,
                lastActivityAt = 900, attention = Attention(reason = "lifecycle", state = "paused"),
            ),
        )
        val by = buildThreads(rows, emptyList(), emptyList(), operatorId = null, since = 1_000).associateBy { it.key }
        assertEquals(ThreadGroup.NEEDS, by.getValue("s:2").group)
        assertEquals("Needs you · blocked on mefistos, which is down", by.getValue("s:2").line)
        assertEquals(ThreadGroup.EARLIER, by.getValue("s:3").group)
        assertEquals("Lost · htz", by.getValue("s:3").line)
    }

    /** Review w04-5: a Needs you thread's line reads as the desktop's strip (`control_threads.ts`, `blockedLine`). */
    @Test
    fun a_needs_you_line_reads_as_the_desktops() {
        fun row(id: Long, reason: String, state: String, f: (SessionRow) -> SessionRow = { it }) = f(
            SessionRow(id = id, tmuxName = "t$id", origin = "operator", hostAlias = "htz", claudeStatus = "idle", attention = Attention(reason, state = state)),
        )
        val rows = listOf(
            row(1, "account_limit", "blocked") { it.copy(accountUuid = "acc") },
            row(2, "ci_failing", "failed"),
            row(3, "stuck", "action_required") { it.copy(stuckKind = "press_enter") },
            row(4, "stop_failed", "failed"),
            row(5, "context_full", "action_required"),
            row(6, "stale_working", "action_required"),
            row(7, "no_credentials", "blocked") { it.copy(claudeProfile = "work") },
            row(8, "no_credentials", "blocked") { it.copy(accountUuid = "acc") },
            row(9, "account_limit", "blocked") { it.copy(accountUuid = "five") },
            row(10, "waiting", "action_required"),
            row(11, "account_limit", "blocked") { it.copy(accountUuid = "five", launchModel = "opus") },
            row(12, "account_limit", "blocked") { it.copy(accountUuid = "five", launchModel = "sonnet") },
        )
        val facts = ThreadFacts(
            accountNames = mapOf("acc" to "tech.silvester"),
            limits = mapOf("five" to AccountLimit(weekly = false, resetsAt = 5_000)),
            // Review w10-5: the Opus window frees later, and holds only the Opus session.
            limitedModels = mapOf("five" to mapOf(ModelFamily.OPUS to AccountLimit(weekly = true, resetsAt = 90_000, model = ModelFamily.OPUS))),
            hosts = listOf(HostRow(alias = "htz", signedOutLogins = listOf(SignedOutLogin(profile = "work")))),
        )
        val line = buildThreads(rows, emptyList(), emptyList(), operatorId = null, since = 0, facts = facts).associate { it.id to it.line }
        assertEquals("Paused · weekly limit on tech.silvester", line[1])
        assertEquals("Failed · checks failing · htz", line[2])
        assertEquals("Needs you · stuck on press Enter · htz", line[3])
        assertEquals("Failed · last turn failed · htz", line[4])
        assertEquals("Needs you · context almost full · htz", line[5])
        assertEquals("Needs you · says working, no news · htz", line[6])
        assertEquals("Needs you · signed out on htz (work)", line[7])
        assertEquals("Needs you · blocked on tech.silvester, which is signed out", line[8])
        assertEquals("Paused · 5-hour limit on five", line[9])
        assertEquals("Needs you · waiting for your answer · htz", line[10])
        assertEquals("Paused · weekly Opus limit on five", line[11])
        assertEquals("Paused · 5-hour limit on five", line[12])
    }

    /**
     * Review w04-1, the desktop's case: a thread Control no longer follows
     * (it went quiet) leaves Needs you for a failure or a block on its
     * account, but not for a question. Followed, the same rows still need you.
     */
    @Test
    fun a_thread_control_stopped_following_leaves_needs_you_for_a_failure_or_a_block_not_for_a_question() {
        val now = 1_000_000L
        val failed = SessionRow(
            id = 1, tmuxName = "pd-1859", origin = "operator", hostAlias = "mefistos", claudeStatus = "failed",
            lastActivityAt = now - 3 * 86_400, attention = Attention(reason = "stop_failed", state = "failed"),
        )
        val limited = SessionRow(
            id = 2, tmuxName = "task-480", origin = "operator", hostAlias = "mefistos", claudeStatus = "idle",
            lastActivityAt = now - 4 * 86_400, attention = Attention(reason = "account_limit", state = "blocked"),
        )
        val asking = SessionRow(
            id = 3, tmuxName = "task-1", origin = "operator", hostAlias = "mefistos", claudeStatus = "blocked",
            lastActivityAt = now - 5 * 86_400, attention = Attention(reason = "waiting", state = "action_required"),
        )
        val quiet = followMarks(listOf(1L, 2L, 3L).map { JournalFollow(kind = "session", id = it, state = "quiet") })
        val since = now - 3_600
        val groups = buildThreads(listOf(failed, limited, asking), emptyList(), emptyList(), operatorId = null, since = since, follows = quiet)
            .associate { it.title to it.group }
        assertEquals(mapOf("pd-1859" to ThreadGroup.EARLIER, "task-480" to ThreadGroup.EARLIER, "task-1" to ThreadGroup.NEEDS), groups)
        // Followed, the same failure and block still need you.
        val followed = buildThreads(listOf(failed, limited), emptyList(), emptyList(), operatorId = null, since = since)
        assertEquals(listOf(ThreadGroup.NEEDS, ThreadGroup.NEEDS), followed.map { it.group })
        // Review w04-1: a muted thread is not followed either, as the desktop's
        // "a muted thread is not followed either" — even one Control still follows.
        val muted = followMarks(listOf(JournalFollow(kind = "session", id = 1, state = "following", muted = true)))
        assertEquals(ThreadGroup.EARLIER, buildThreads(listOf(failed), emptyList(), emptyList(), null, since, muted).single().group)
        // Review w04-2: a row out of Needs you leads with its status, the reason without its head.
        val lines = buildThreads(listOf(failed, limited, asking), emptyList(), emptyList(), operatorId = null, since = since, follows = quiet)
            .associate { it.title to it.line }
        assertEquals("Failed · last turn failed · mefistos", lines["pd-1859"])
        assertEquals("Idle · weekly limit on its account", lines["task-480"])
        assertEquals("Needs you · waiting for your answer · mefistos", lines["task-1"])
        val down = SessionRow(
            id = 4, tmuxName = "task-573", origin = "operator", hostAlias = "mefistos", claudeStatus = "idle",
            lastActivityAt = now - 3 * 86_400, attention = Attention(reason = "host_down", state = "blocked"),
        )
        val merged = SessionRow(
            id = 5, tmuxName = "task-9", origin = "operator", hostAlias = "htz", claudeStatus = "completed",
            lastActivityAt = now - 60, lastStopAt = now - 60, attention = Attention(reason = "stop_failed", state = "failed"),
        )
        val gone = followMarks(listOf(JournalFollow(kind = "session", id = 4, state = "quiet"), JournalFollow(kind = "session", id = 5, state = "merged")))
        val out = buildThreads(listOf(down, merged), emptyList(), emptyList(), operatorId = null, since = since, follows = gone).associateBy { it.id }
        assertEquals(ThreadGroup.EARLIER, out.getValue(4).group)
        assertEquals("Idle · blocked on mefistos, which is down", out.getValue(4).line)
        assertEquals(ThreadGroup.DONE, out.getValue(5).group)
        assertEquals("Done · last turn failed · htz", out.getValue(5).line)
    }

    /**
     * Review w04-3: a mission that ended goes by when it did, as the
     * desktop's `missionGroup`: a failure today needs you, an older one is
     * Earlier, a cancel never needs you, and a wait on a final mission does not count.
     */
    @Test
    fun a_mission_that_ended_goes_by_when_it_did() {
        val since = 10_000L
        val missions = listOf(
            Mission(id = 1, name = "today", state = "failed", updatedAt = 10_500),
            Mission(id = 2, name = "weeks ago", state = "failed", updatedAt = 100),
            Mission(id = 3, name = "cancelled", state = "cancelled", updatedAt = 10_500),
            Mission(id = 4, name = "old done", state = "completed", updatedAt = 100),
            Mission(id = 5, name = "working", state = "active", updatedAt = 100),
        )
        val follows = followMarks(missions.map { JournalFollow(kind = "mission", id = it.id, state = "following") })
        val groups = buildThreads(emptyList(), emptyList(), missions, operatorId = null, since = since, follows = follows)
            .associate { it.id to it.group }
        assertEquals(
            mapOf(1L to ThreadGroup.NEEDS, 2L to ThreadGroup.EARLIER, 3L to ThreadGroup.DONE, 4L to ThreadGroup.EARLIER, 5L to ThreadGroup.WORKING),
            groups,
        )
        // Only a receipt knows it: no finish time, so a failure is Earlier, not Needs you for ever.
        val handed = listOf(ControlHandoff(id = 1, at = 10_500, kind = "mission", missionId = 8, missionState = "failed"))
        assertEquals(ThreadGroup.EARLIER, buildThreads(emptyList(), handed, emptyList(), operatorId = null, since = since).single().group)
    }

    /** The journal's follows reach past the newest receipts: a thread it names joins the list (review w04-1). */
    @Test
    fun the_journals_follows_add_threads_the_receipts_no_longer_name() {
        val rows = listOf(SessionRow(id = 7, tmuxName = "handed", claudeStatus = "idle", lastActivityAt = 500))
        val follows = followMarks(
            listOf(
                JournalFollow(kind = "session", id = 7, state = "following"),
                JournalFollow(kind = "mission", id = 9, state = "following", name = "Ship it"),
                JournalFollow(kind = "session", id = 99, state = "following"),
            ),
        )
        val threads = buildThreads(rows, emptyList(), emptyList(), operatorId = null, since = 1_000, follows = follows)
        assertEquals(setOf("s:7", "m:9"), threads.map { it.key }.toSet(), "a session the fleet does not list is left out")
        assertEquals("Ship it", threads.single { it.mission }.title)
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
    fun the_dial_reads_its_four_settings_and_writes_every_one_of_a_position() {
        val brief = mapOf(KEY_MAX_LEVEL to "2", KEY_AUTO_CONTINUE to "true", KEY_AUTO_CONTINUE_MINUTES to "10", KEY_REACT_TO_EVENTS to "true")
        assertEquals(Autonomy.BRIEF, autonomyOf(brief))
        assertEquals(Autonomy.ASK, autonomyOf(brief + (KEY_MAX_LEVEL to "1") + (KEY_AUTO_CONTINUE to "false") + (KEY_AUTO_CONTINUE_MINUTES to "7")))
        assertNull(autonomyOf(brief + (KEY_AUTO_CONTINUE_MINUTES to "7")))
        assertEquals("Custom", autonomyWord(null))
        // Every key, even one the phone's copy already shows: that copy may be
        // stale (review phone-flows-3).
        assertEquals(
            listOf(KEY_MAX_LEVEL to "3", KEY_AUTO_CONTINUE to "true", KEY_AUTO_CONTINUE_MINUTES to "2", KEY_REACT_TO_EVENTS to "true"),
            autonomyWrites(Autonomy.AUTOPILOT),
        )
        assertEquals(listOf(KEY_MAX_LEVEL to "1", KEY_AUTO_CONTINUE to "false", KEY_REACT_TO_EVENTS to "true"), autonomyWrites(Autonomy.ASK))
    }
}
