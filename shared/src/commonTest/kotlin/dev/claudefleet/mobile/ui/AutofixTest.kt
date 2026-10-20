package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.AutofixActions
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.AutofixFinding
import dev.claudefleet.mobile.model.AutofixFindings
import dev.claudefleet.mobile.model.AutofixFix
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class AutofixFleet(caps: HubCapabilities) : FleetState {
    override val sessions = MutableStateFlow(emptyList<SessionRow>())
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "mercury", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(caps)
    override suspend fun refresh() = Unit
}

private val HOOKS = AutofixFinding(
    id = 1, kind = "host.agent_behind", subject = "host:mercury", hostAlias = "mercury", severity = "degraded", state = "open",
    firstSeenAt = 100, lastSeenAt = 200, title = "The agent on mercury is behind",
    explanation = "mercury runs agent 0.9.1; the hub is 0.9.3.", evidence = listOf("agent 0.9.1"),
    fix = AutofixFix(summary = "Update the agent on mercury", changes = listOf("fleet-agent on mercury"), safety = "needs_approval"),
)
private val LOGIN = AutofixFinding(
    id = 2, kind = "stuck.auth_menu", subject = "session:12", severity = "blocking", state = "dismissed", firstSeenAt = 150, title = "Session #12 waits for a login",
)
private val DONE = AutofixFinding(
    id = 3, kind = "host.hooks_silent", subject = "host:venus", state = "cleared", attempts = 1, lastAttemptAt = 300,
    title = "Hooks on venus were silent", fix = AutofixFix(summary = "Reinstall the hooks on venus", safety = "safe", auto = true),
)

private class FakeAutofixActions : AutofixActions {
    val calls = mutableListOf<String>()
    var canApply = true
    var answer: (AutofixFinding) -> AutofixFinding = { it.copy(state = "cleared", attempts = it.attempts + 1) }
    var refuse = false

    private fun answerList() = AutofixFindings(listOf(HOOKS, LOGIN), listOf(DONE), canApply)

    override suspend fun list(): AutofixFindings = answerList().also { calls += "list" }

    override suspend fun scan(): AutofixFindings = answerList().also { calls += "scan" }

    override suspend fun apply(id: Long): AutofixFinding {
        calls += "apply $id"
        if (refuse) throw HubError.Tool("E_FORBIDDEN", "only a trusted device with full access can apply or dismiss a fix")
        return answer(listOf(HOOKS, LOGIN).first { it.id == id })
    }

    override suspend fun dismiss(id: Long): AutofixFinding {
        calls += "dismiss $id"
        return HOOKS.copy(state = "dismissed", dismissedUntil = 999)
    }
}

private val WITH_AUTOFIX = HubCapabilities(tools = setOf("autofix"))

/** Problems & fixes on the phone: the hub's `autofix` findings, read and decided only where it serves them. */
class AutofixTest {
    private fun TestScope.vm(
        caps: HubCapabilities = WITH_AUTOFIX,
        actions: FakeAutofixActions = FakeAutofixActions(),
        canWrite: Boolean = true,
    ) = AutofixViewModel(AutofixFleet(caps), actions, backgroundScope, canWrite)

    @Test
    fun a_safe_fix_says_when_fleet_tries_again_or_that_it_stopped() {
        val safe = AutofixFix(summary = "s", safety = "safe", auto = true)
        val open = DONE.copy(state = "open", attempts = 0, fix = safe)
        assertEquals(null, autoWords(HOOKS, 1000))
        assertEquals("Safe: fleet runs this fix by itself unless automatic fixes are off.", autoWords(open, 1000))
        assertEquals("Fleet runs this fix by itself on its next check.", autoWords(open.copy(autoRuns = true, autoNextAt = 1000), 1000))
        assertEquals(
            "Fleet tries this fix again by itself in ~10 min.",
            autoWords(open.copy(state = "apply_failed", attempts = 1, autoRuns = true, autoNextAt = 1600), 1000),
        )
        assertEquals(
            "Fleet tried this fix 3 times and stopped trying by itself; fix it here.",
            autoWords(open.copy(state = "apply_failed", attempts = 3, autoRuns = false), 1000),
        )
    }

    @Test
    fun the_hub_s_auto_runs_parses() {
        val f = json.decodeFromString(AutofixFinding.serializer(), """{"id":1,"auto_runs":false}""")
        assertEquals(false, f.autoRuns)
        assertEquals(null, json.decodeFromString(AutofixFinding.serializer(), """{"id":1}""").autoRuns)
    }

    @Test
    fun a_hub_without_autofix_is_never_asked() = runTest {
        val actions = FakeAutofixActions()
        val vm = vm(caps = HubCapabilities(), actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        assertFalse(vm.state.value.available)
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun opening_splits_the_problems_the_hidden_and_the_fixed() = runTest {
        val vm = vm()
        runCurrent()
        vm.open()
        runCurrent()
        val s = vm.state.value
        assertTrue(s.open && s.loaded && s.canApply)
        assertEquals(listOf(1L), s.problems.map { it.id })
        assertEquals(listOf(2L), s.hidden.map { it.id })
        assertEquals(listOf(3L), s.fixed.map { it.id })
        assertEquals("1 problem · 1 fixed in the last day", autofixLine(s))
    }

    @Test
    fun a_fix_that_clears_moves_the_row_to_the_fixed() = runTest {
        val actions = FakeAutofixActions()
        val vm = vm(actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        vm.apply(HOOKS)
        runCurrent()
        val s = vm.state.value
        assertEquals(listOf("list", "apply 1"), actions.calls)
        assertTrue(s.problems.isEmpty())
        assertEquals(listOf(1L, 3L), s.fixed.map { it.id })
        assertEquals("Fixed: The agent on mercury is behind.", s.notice)
    }

    @Test
    fun a_failed_fix_stays_with_its_reason() = runTest {
        val actions = FakeAutofixActions().apply { answer = { it.copy(state = "apply_failed", lastError = "ssh timed out") } }
        val vm = vm(actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        vm.apply(HOOKS)
        runCurrent()
        val row = vm.state.value.problems.single()
        assertEquals("apply_failed", row.state)
        assertEquals("Try again…", fixLabel(row))
        assertEquals("The fix did not work: ssh timed out", findingStateWords(row))
    }

    @Test
    fun later_hides_the_row() = runTest {
        val vm = vm()
        runCurrent()
        vm.open()
        runCurrent()
        vm.later(HOOKS)
        runCurrent()
        assertEquals(listOf(1L, 2L), vm.state.value.hidden.map { it.id })
        assertTrue(vm.state.value.problems.isEmpty())
    }

    @Test
    fun a_device_the_hub_does_not_trust_only_reads() = runTest {
        val actions = FakeAutofixActions().apply { canApply = false }
        val vm = vm(actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        assertFalse(vm.state.value.canApply)
        vm.apply(HOOKS)
        vm.later(HOOKS)
        runCurrent()
        assertEquals(listOf("list"), actions.calls)
    }

    @Test
    fun a_readonly_token_only_reads_even_when_the_hub_would_let_it() = runTest {
        val vm = vm(canWrite = false)
        runCurrent()
        vm.open()
        runCurrent()
        assertFalse(vm.state.value.canApply)
    }

    @Test
    fun a_refused_fix_shows_the_refusal() = runTest {
        val actions = FakeAutofixActions().apply { refuse = true }
        val vm = vm(actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        vm.apply(HOOKS)
        runCurrent()
        assertNotNull(vm.state.value.error)
        assertEquals(null, vm.state.value.busy)
    }

    @Test
    fun the_words() {
        assertEquals("host mercury", subjectWords("host:mercury"))
        assertEquals("session #12", subjectWords("session:12"))
        assertEquals("hub", subjectWords("hub"))
        assertEquals("Reinstall the hooks on venus", fixedLine(DONE))
        assertEquals("Reinstall the hooks on venus · 2 tries", fixedLine(DONE.copy(attempts = 2)))
        assertTrue(fixRunning(HOOKS.copy(state = "verifying", lastAttemptAt = 1000), 1030))
        assertFalse(fixRunning(HOOKS.copy(state = "verifying", lastAttemptAt = 1000), 1100))
        assertEquals("What fleet found broken, and its fixes", autofixLine(AutofixUiState()))
        assertEquals("No problems found", autofixLine(AutofixUiState(loaded = true)))
    }

    @Test
    fun the_hubs_list_decodes() {
        val wire = """
            {"findings":[{"id":7,"kind":"operator_unbound","subject":"token:ux-agent","host_alias":null,"severity":"blocking",
              "state":"open","first_seen_at":10,"last_seen_at":20,"attempts":0,"title":"Control sees nothing",
              "explanation":"Its token belongs to nobody.","evidence":["person_id is null"],
              "fix":{"summary":"Bind Control to you","changes":["client token ux-agent"],"safety":"safe","reversible":true,"auto":true},
              "action":null}],
             "fixed":[],"can_apply":true}
        """.trimIndent()
        val list = json.decodeFromString(AutofixFindings.serializer(), wire)
        assertTrue(list.canApply)
        val f = list.findings.single()
        assertEquals("token ux-agent", subjectWords(f.subject))
        assertEquals("Bind Control to you", f.fix?.summary)
        assertEquals(listOf("person_id is null"), f.evidence)
    }
}
