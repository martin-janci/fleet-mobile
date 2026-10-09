package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionExtrasActions
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val PARENT = SessionRow(id = 7, tmuxName = "hosts-polish", hostAlias = "mercury", projectId = 3, worktreeId = 11, kind = "work")

private fun shell(id: Long, name: String, host: String = "mercury", worktree: Long? = 11) =
    SessionRow(id = id, tmuxName = name, hostAlias = host, projectId = 3, worktreeId = worktree, kind = "shell")

private class ExtrasFleet(rows: List<SessionRow>, tools: Set<String>) : FleetState {
    override val sessions = MutableStateFlow(rows)
    override val hosts = MutableStateFlow(listOf(HostRow("mercury", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(HubCapabilities(tools = tools))
    override suspend fun refresh() = Unit
}

private class Extras(private val fleet: ExtrasFleet) : SessionExtrasActions {
    val calls = mutableListOf<String>()
    var archiveFails = false
    var screen = "$ "
    override suspend fun newShell(hostAlias: String, projectId: Long, worktreeId: Long?, name: String): SessionRow {
        calls += "new $hostAlias $projectId $worktreeId $name"
        val row = shell(100L + calls.size, name)
        fleet.sessions.update { it + row }
        return row
    }
    override suspend fun capture(sessionId: Long, maxLines: Int): String {
        calls += "capture $sessionId"
        return screen
    }
    override suspend fun type(sessionId: Long, text: String) {
        calls += "type $sessionId $text"
    }
    override suspend fun press(sessionId: Long, key: String) {
        calls += "press $sessionId $key"
    }
    override suspend fun archive(sessionId: Long) {
        if (archiveFails) throw HubError.Tool("E_NOTFOUND", "session 7 has no linked work")
        calls += "archive $sessionId"
    }
}

private val SHELLS = setOf(HubCapabilities.NEW_SHELL_SESSION, HubCapabilities.WORK_LINK)

/** A session's extras on the New bar (redesign 14.14, MobileSessionExtras). */
class SessionExtrasTest {

    // --- terminals ---

    @Test
    fun a_sessions_terminals_are_the_shells_in_its_worktree_oldest_first() {
        val rows = listOf(
            PARENT,
            shell(12, "hosts-polish-sh2"),
            shell(9, "hosts-polish-sh1"),
            shell(13, "elsewhere", host = "nas"),
            shell(14, "main-checkout", worktree = null),
            shell(15, "gone").copy(lostAt = 5),
            SessionRow(id = 16, tmuxName = "agent-2", hostAlias = "mercury", projectId = 3, worktreeId = 11, kind = "work"),
        )
        assertEquals(listOf(9L, 12L), terminalsOf(PARENT, rows).map { it.id })
        assertEquals(emptyList(), terminalsOf(null, rows))
    }

    @Test
    fun a_new_shell_takes_the_first_free_number() {
        assertEquals("hosts-polish-sh1", nextShellName(PARENT, emptyList()))
        assertEquals("hosts-polish-sh2", nextShellName(PARENT, listOf(shell(9, "hosts-polish-sh1"), shell(10, "hosts-polish-sh3"))))
    }

    @Test
    fun the_tab_counts_its_terminals() {
        assertEquals("Terminals", terminalsTabLabel(0))
        assertEquals("Terminals 2", terminalsTabLabel(2))
        assertEquals("shell · 1", terminalLabel(0))
    }

    @Test
    fun new_starts_a_shell_beside_the_session_and_selects_it() = runTest {
        val fleet = ExtrasFleet(listOf(PARENT), SHELLS)
        val extras = Extras(fleet)
        val vm = SessionExtrasViewModel(7, fleet, extras, backgroundScope, canWrite = true)
        runCurrent()
        assertTrue(vm.state.value.canCreate)
        assertNull(vm.state.value.selected)

        vm.newTerminal().join()
        runCurrent()

        assertEquals("new mercury 3 11 hosts-polish-sh1", extras.calls.first())
        assertEquals(1, vm.state.value.terminals.size)
        assertEquals(vm.state.value.terminals.single().id, vm.state.value.selected)
        assertEquals("$ ", vm.state.value.screen)
    }

    @Test
    fun a_readonly_pairing_or_an_older_hub_cannot_start_one() = runTest {
        val readonly = SessionExtrasViewModel(7, ExtrasFleet(listOf(PARENT), SHELLS), Extras(ExtrasFleet(emptyList(), SHELLS)), backgroundScope, canWrite = false)
        val older = SessionExtrasViewModel(7, ExtrasFleet(listOf(PARENT), emptySet()), Extras(ExtrasFleet(emptyList(), SHELLS)), backgroundScope, canWrite = true)
        runCurrent()
        assertFalse(readonly.state.value.canCreate)
        assertFalse(readonly.state.value.canType)
        assertFalse(older.state.value.canCreate)
    }

    @Test
    fun the_line_goes_to_the_shell_and_up_brings_it_back() = runTest {
        val fleet = ExtrasFleet(listOf(PARENT, shell(9, "hosts-polish-sh1")), SHELLS)
        val extras = Extras(fleet)
        val vm = SessionExtrasViewModel(7, fleet, extras, backgroundScope, canWrite = true)
        runCurrent()

        vm.setInput("git status -sb")
        // Joined, not `advanceUntilIdle`: that stops once only `backgroundScope` work is left.
        vm.submit().join()
        runCurrent()
        assertTrue("type 9 git status -sb" in extras.calls)
        assertEquals("", vm.state.value.input)

        vm.press(TerminalKey.Up).join()
        runCurrent()
        assertEquals("git status -sb", vm.state.value.input)
        vm.press(TerminalKey.Down).join()
        runCurrent()
        assertEquals("", vm.state.value.input)

        // An empty line is a bare Enter.
        vm.submit().join()
        assertTrue("press 9 Enter" in extras.calls)
    }

    @Test
    fun the_key_bar_sends_keys_to_the_pane_and_symbols_to_the_line() = runTest {
        val fleet = ExtrasFleet(listOf(PARENT, shell(9, "hosts-polish-sh1")), SHELLS)
        val extras = Extras(fleet)
        val vm = SessionExtrasViewModel(7, fleet, extras, backgroundScope, canWrite = true)
        runCurrent()

        for (key in listOf(TerminalKey.Esc, TerminalKey.Tab, TerminalKey.CtrlC, TerminalKey.Pipe, TerminalKey.Tilde)) vm.press(key).join()
        runCurrent()

        assertEquals(listOf("press 9 Escape", "press 9 Tab", "press 9 C-c"), extras.calls.filter { it.startsWith("press") })
        assertEquals("|~", vm.state.value.input)
        // Only the keys the hub's `send_prompt { keys }` takes.
        val accepted = setOf("Enter", "Escape", "Tab", "C-c")
        assertTrue(TerminalKey.entries.mapNotNull { it.key }.all { it in accepted })
    }

    @Test
    fun the_shell_is_read_while_shown_and_not_after() = runTest {
        val fleet = ExtrasFleet(listOf(PARENT, shell(9, "hosts-polish-sh1")), SHELLS)
        val extras = Extras(fleet)
        val vm = SessionExtrasViewModel(7, fleet, extras, backgroundScope, canWrite = true, pollMs = 1_000)
        runCurrent()

        vm.show()
        advanceTimeBy(2_500)
        val whileShown = extras.calls.count { it == "capture 9" }
        assertEquals(3, whileShown)

        vm.hide()
        advanceTimeBy(5_000)
        assertEquals(whileShown, extras.calls.count { it == "capture 9" })
        // What was read and typed stays for when the tab comes back.
        assertEquals("$ ", vm.state.value.screen)
    }

    // --- archive ---

    @Test
    fun archive_leaves_the_session_once_the_hub_took_it() = runTest {
        val fleet = ExtrasFleet(listOf(PARENT), SHELLS)
        val extras = Extras(fleet)
        val vm = SessionExtrasViewModel(7, fleet, extras, backgroundScope, canWrite = true)
        var left = 0
        vm.archive { left++ }.join()
        assertEquals(listOf("archive 7"), extras.calls)
        assertEquals(1, left)

        extras.archiveFails = true
        vm.archive { left++ }.join()
        runCurrent()
        assertEquals(1, left, "a refused archive stays on the session")
        assertNotNull(vm.state.value.archiveError)
    }

    // --- find ---

    private val findRows = newestFirst(
        listOf(
            ConvTurn(prompt = "Show the lastPing on each row.", at = "t1", items = listOf(ConvItem.Text("lastPingAt is only for SSH hosts."))),
            ConvTurn(prompt = "run the tests", at = "t2", items = listOf(ConvItem.Tool("Edit …/ui/HostsViewModel.kt lastPing"), ConvItem.Tool("Run gradle test", error = true))),
            ConvTurn(prompt = "ship it", at = "t3", items = listOf(ConvItem.Text("done"))),
        ),
    )

    @Test
    fun each_find_scope_looks_where_it_says() {
        // Newest first: t3 is row 0, t2 row 1, t1 row 2.
        assertEquals(listOf(2), findTurns(findRows, "lastping"), "Everything: prompts and replies, as before")
        assertEquals(listOf(2), findTurns(findRows, "lastping", FindScope.Mine))
        assertEquals(emptyList(), findTurns(findRows, "SSH", FindScope.Mine), "My messages: not the replies")
        assertEquals(listOf(1), findTurns(findRows, "lastping", FindScope.Tools))
        assertEquals(listOf(1), findTurns(findRows, "gradle", FindScope.Errors))
        assertEquals(emptyList(), findTurns(findRows, "hostsviewmodel", FindScope.Errors), "Errors: only what failed")
        assertEquals(listOf(1), findTurns(findRows, "", FindScope.Errors), "no query: every turn where something failed")
        assertEquals(emptyList(), findTurns(findRows, "", FindScope.Tools))
    }

    // --- the ⋮ menu ---

    @Test
    fun the_menu_writes_out_every_item_with_kill_last() {
        val items = orbitMenuItems(
            OrbitMenuFacts(
                manage = true, hostAlias = "mercury", tmuxName = "hosts-polish", ticketKey = "FLEET-142", ticket = true,
                move = true, repair = true, recreate = true, restart = true, steer = true, review = true,
                archive = true, kill = true, worktree = true,
            ),
        )
        val labels = items.map { it.label }
        assertEquals("Rename", labels.first())
        assertEquals("Kill session…", labels.last())
        assertTrue(items.last().danger)
        assertEquals("asks first", items.last().detail)
        for (label in listOf("Ticket and tasks", "Move to host…", "Repair workspace", "Recreate", "Copy tmux attach command", "Details", "Archive")) {
            assertTrue(label in labels, label)
        }
        assertEquals("FLEET-142", items.first { it.id == OrbitItem.Ticket }.detail)
        assertEquals("now on mercury", items.first { it.id == OrbitItem.Move }.detail)
        assertEquals("tmux attach -t hosts-polish", items.first { it.id == OrbitItem.CopyAttach }.detail)
        // Recovery sits between the session's naming and its ending.
        assertTrue(labels.indexOf("Repair workspace") in (labels.indexOf("Rename") + 1) until labels.indexOf("Archive"))
        assertTrue(items.all { it.label == "Details" || it.detail != null || it.id == OrbitItem.Rename })
    }

    @Test
    fun the_menu_offers_only_what_this_session_can_do() {
        val items = orbitMenuItems(OrbitMenuFacts(tmuxName = "s")).map { it.id }
        assertEquals(listOf(OrbitItem.CopyAttach, OrbitItem.Details), items)
    }
}

/** Two shells side by side in landscape (redesign 14.21, MobileFullscreen). */
class SplitTerminalsTest {

    @Test
    fun the_shell_beside_is_the_one_picked_before_or_the_first_other() {
        val shells = listOf(shell(9, "a"), shell(10, "b"), shell(11, "c"))
        assertEquals(10L, pairedOf(shells, 9, null))
        assertEquals(11L, pairedOf(shells, 9, 11))
        assertEquals(10L, pairedOf(shells, 9, 9), "never the selected one twice")
        assertEquals(9L, pairedOf(shells, 10, 42), "one that is gone is replaced")
        assertNull(pairedOf(shells.take(1), 9, null))
    }

    @Test
    fun a_split_reads_both_shells() = runTest {
        val fleet = ExtrasFleet(listOf(PARENT, shell(9, "hosts-polish-sh1"), shell(10, "hosts-polish-sh2")), SHELLS)
        val extras = Extras(fleet)
        val vm = SessionExtrasViewModel(7, fleet, extras, backgroundScope, canWrite = true)
        runCurrent()
        assertNull(vm.state.value.paired, "one column until the pane says it is wide")

        vm.setSplit(true)
        runCurrent()

        assertEquals(9L, vm.state.value.selected)
        assertEquals(10L, vm.state.value.paired)
        assertTrue("capture 9" in extras.calls && "capture 10" in extras.calls)
        assertEquals("$ ", vm.state.value.pairedScreen)

        vm.setSplit(false)
        runCurrent()
        assertNull(vm.state.value.paired)
    }

    @Test
    fun tapping_the_other_pane_types_there_and_keeps_both_on_screen() = runTest {
        val fleet = ExtrasFleet(
            listOf(PARENT, shell(9, "hosts-polish-sh1"), shell(10, "hosts-polish-sh2"), shell(11, "hosts-polish-sh3")),
            SHELLS,
        )
        val extras = Extras(fleet)
        val vm = SessionExtrasViewModel(7, fleet, extras, backgroundScope, canWrite = true)
        vm.setSplit(true)
        runCurrent()

        vm.select(10)
        runCurrent()
        assertEquals(10L, vm.state.value.selected)
        assertEquals(9L, vm.state.value.paired)

        vm.setInput("ls")
        vm.submit().join()
        assertTrue("type 10 ls" in extras.calls)
    }
}
