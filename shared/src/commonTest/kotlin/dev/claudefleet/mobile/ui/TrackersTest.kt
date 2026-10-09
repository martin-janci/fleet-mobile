package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.TrackerActions
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.TrackerAdminRow
import dev.claudefleet.mobile.model.TrackerTestReport
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class TrackersFleet(caps: HubCapabilities, contract: Int?) : FleetState {
    override val sessions = MutableStateFlow(emptyList<SessionRow>())
    override val hosts = MutableStateFlow(emptyList<HostRow>())
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(caps)
    override val hubContract = MutableStateFlow(contract)
    override suspend fun refresh() = Unit
}

private val ACME = TrackerAdminRow(id = 4, provider = "jira", name = "acme", siteUrl = "https://acme.atlassian.net", state = "ok", username = "me@acme.io")

private class FakeTrackerActions : TrackerActions {
    val calls = mutableListOf<String>()
    var rows = listOf(ACME)
    var accept = true
    var refuse: HubError? = null
    var askConfirm = false

    override suspend fun list(): List<TrackerAdminRow> = rows.also { calls += "list" }

    override suspend fun add(siteUrl: String, provider: String?): TrackerAdminRow {
        calls += "add $siteUrl $provider"
        refuse?.let { throw it }
        val row = TrackerAdminRow(id = 9, provider = provider ?: inferTrackerProvider(siteUrl).orEmpty(), name = "new", siteUrl = siteUrl, state = "unconfigured")
        rows = rows + row
        return row
    }

    override suspend fun setCredential(trackerId: Long, authKind: String, username: String?, secret: String): TrackerAdminRow {
        calls += "credential $trackerId $authKind $username ${secret.length}"
        return rows.first { it.id == trackerId }
    }

    override suspend fun test(trackerId: Long): TrackerTestReport {
        calls += "test $trackerId"
        val row = rows.first { it.id == trackerId }.copy(state = if (accept) "ok" else "auth_failed")
        rows = rows.map { if (it.id == trackerId) row else it }
        return TrackerTestReport(row, ok = accept, error = if (accept) null else "401 Unauthorized")
    }

    override suspend fun remove(trackerId: Long, confirmNonce: String?) {
        calls += "remove $trackerId $confirmNonce"
        if (askConfirm && confirmNonce == null) {
            throw HubError.Tool("E_CONFIRM_REQUIRED", "approve it first", buildJsonObject { put("confirm", JsonPrimitive("n-1")) })
        }
        rows = rows.filter { it.id != trackerId }
    }
}

private val WITH_WORK_ADMIN = HubCapabilities(tools = setOf("work_admin"))

/**
 * Trackers on the phone (redesign 14.20): the owner's trusted phone connects,
 * tests and disconnects the hub's trackers from contract 13 on; the secret
 * goes to the hub once and is not kept.
 */
class TrackersTest {

    private fun TestScope.trackers(
        caps: HubCapabilities = WITH_WORK_ADMIN,
        contract: Int? = 13,
        actions: FakeTrackerActions = FakeTrackerActions(),
        canWrite: Boolean = true,
    ) = TrackersViewModel(TrackersFleet(caps, contract), actions, backgroundScope, canWrite)

    @Test
    fun it_needs_contract_13_work_admin_and_a_pairing_that_writes() = runTest {
        for ((vm, why) in listOf(
            trackers(contract = 12) to "contract 12",
            trackers(contract = null) to "no contract",
            trackers(caps = HubCapabilities()) to "no work_admin",
            trackers(canWrite = false) to "read-only pairing",
        )) {
            runCurrent()
            assertFalse(vm.state.value.available, why)
        }
        val vm = trackers()
        runCurrent()
        assertTrue(vm.state.value.available)
    }

    @Test
    fun a_hub_too_old_is_never_asked() = runTest {
        val actions = FakeTrackerActions()
        val vm = trackers(contract = 12, actions = actions)
        runCurrent()
        vm.load()
        vm.startConnect()
        runCurrent()
        assertEquals(emptyList<String>(), actions.calls)
        assertNull(vm.state.value.wizard)
    }

    @Test
    fun connect_adds_signs_in_and_tests_then_forgets_the_secret() = runTest {
        val actions = FakeTrackerActions()
        val vm = trackers(actions = actions)
        runCurrent()
        vm.startConnect()
        vm.editSite("https://acme.atlassian.net/browse/PAY-1")
        runCurrent()
        assertEquals("jira", vm.state.value.wizard?.provider)
        vm.next()
        runCurrent()
        assertEquals(TrackerStep.SignIn, vm.state.value.wizard?.step)
        vm.editEmail("me@acme.io")
        vm.editSecret("tok-123")
        vm.connect()
        runCurrent()
        assertEquals(
            listOf("add https://acme.atlassian.net/browse/PAY-1 null", "credential 9 basic me@acme.io 7", "test 9", "list"),
            actions.calls,
        )
        assertNull(vm.state.value.wizard)
        assertEquals("Connected new.", vm.state.value.notice)
        assertEquals(listOf(4L, 9L), vm.state.value.trackers.map { it.id })
    }

    @Test
    fun a_refused_token_stays_in_the_wizard_and_a_retry_does_not_add_twice() = runTest {
        val actions = FakeTrackerActions().apply { accept = false }
        val vm = trackers(actions = actions)
        runCurrent()
        vm.startConnect()
        vm.editSite("https://linear.app/acme")
        vm.next()
        vm.editSecret("bad")
        vm.connect()
        runCurrent()
        val w = assertNotNull(vm.state.value.wizard)
        assertEquals("", w.secret, "the secret is not kept after it is sent")
        assertEquals("Linear did not accept the sign-in: 401 Unauthorized", w.failure)
        assertFalse("bad" in w.toString())

        actions.accept = true
        vm.editSecret("good")
        vm.connect()
        runCurrent()
        assertEquals(1, actions.calls.count { it.startsWith("add") })
        assertEquals("credential 9 bearer null 4", actions.calls.filter { it.startsWith("credential") }.last())
        assertNull(vm.state.value.wizard)
    }

    @Test
    fun the_site_step_asks_which_tracker_when_the_address_does_not_say() = runTest {
        val vm = trackers()
        runCurrent()
        vm.startConnect()
        vm.editSite("https://jira.acme.internal")
        runCurrent()
        assertEquals("Pick which tracker this is.", siteBlocker(vm.state.value.wizard!!))
        vm.next()
        runCurrent()
        assertEquals(TrackerStep.Site, vm.state.value.wizard?.step)
        vm.chooseProvider("jira_dc")
        vm.next()
        runCurrent()
        assertEquals(TrackerStep.SignIn, vm.state.value.wizard?.step)
        assertFalse(needsEmail(vm.state.value.wizard?.provider))
        // Back keeps the answers, but not the token.
        vm.editSecret("x")
        vm.back()
        runCurrent()
        assertEquals("https://jira.acme.internal", vm.state.value.wizard?.siteUrl)
        assertEquals("", vm.state.value.wizard?.secret)
    }

    @Test
    fun sign_in_again_skips_to_the_sign_in_and_never_adds() = runTest {
        val actions = FakeTrackerActions()
        val vm = trackers(actions = actions)
        runCurrent()
        vm.signInAgain(ACME)
        runCurrent()
        assertEquals(TrackerStep.SignIn, vm.state.value.wizard?.step)
        assertEquals("me@acme.io", vm.state.value.wizard?.email)
        vm.editSecret("t")
        vm.connect()
        runCurrent()
        assertTrue(actions.calls.none { it.startsWith("add") })
        assertEquals("credential 4 basic me@acme.io 1", actions.calls.first())
    }

    @Test
    fun an_untrusted_phone_is_told_how_the_operator_trusts_it() = runTest {
        val actions = FakeTrackerActions().apply {
            refuse = HubError.Tool("E_FORBIDDEN", "only the owner's trusted phone may manage trackers")
        }
        val vm = trackers(actions = actions)
        runCurrent()
        vm.startConnect()
        vm.editSite("https://github.com/acme/app")
        vm.next()
        vm.editSecret("ghp")
        vm.connect()
        runCurrent()
        val error = assertNotNull(vm.state.value.error)
        assertEquals("This phone may not manage trackers yet", error.title)
        assertTrue("fleet-hub client trust" in error.body)
        assertEquals("", vm.state.value.wizard?.secret)
    }

    @Test
    fun remove_waits_on_the_hubs_approval_and_resends_its_nonce() = runTest {
        val actions = FakeTrackerActions().apply { askConfirm = true }
        val vm = trackers(actions = actions)
        runCurrent()
        vm.load()
        runCurrent()
        vm.askRemove(ACME)
        vm.confirmRemove()
        runCurrent()
        assertEquals(REMOVE_NEEDS_APPROVAL, vm.state.value.notice)
        assertEquals(listOf(4L), vm.state.value.trackers.map { it.id })

        vm.askRemove(ACME)
        vm.confirmRemove()
        runCurrent()
        assertEquals("remove 4 n-1", actions.calls.filter { it.startsWith("remove") }.last())
        assertEquals("Disconnected acme.", vm.state.value.notice)
        assertEquals(emptyList<TrackerAdminRow>(), vm.state.value.trackers)
    }

    @Test
    fun helpers_say_it_in_words() {
        assertEquals("jira", inferTrackerProvider("https://acme.atlassian.net/browse/X-1"))
        assertEquals("github", inferTrackerProvider("https://github.com/acme/app/issues/3"))
        assertEquals("asana", inferTrackerProvider("https://app.asana.com/0/1"))
        assertEquals("linear", inferTrackerProvider("https://linear.app/acme/issue/A-1"))
        assertNull(inferTrackerProvider("acme.atlassian.net"))
        assertEquals("Sign-in refused", trackerStateWord("auth_failed"))
        assertEquals("acme.atlassian.net · me@acme.io · synced 5m ago", trackerLine(ACME, "5m ago"))
        assertEquals("2 connected · 1 needs a look", trackersHeadline(listOf(ACME, ACME.copy(id = 5), ACME.copy(id = 6, state = "unreachable"))))
        assertEquals("No trackers yet", trackersHeadline(emptyList()))
        assertEquals("Step 2 of 2 · Sign in", trackerStepHeading(TrackerStep.SignIn))
        assertEquals("Checking the token with Jira Cloud…", connectingLine(TrackerWork.Checking, "jira"))
    }
}

/** Review r09 B4, B5: step bars from two steps on, and closing asks only if something was typed. */
class WizardRulesTest {
    @Test
    fun step_bars_show_from_two_steps_and_fill_up_to_the_current() {
        assertFalse(dev.claudefleet.mobile.ui.kit.stepBarsShown(1))
        assertTrue(dev.claudefleet.mobile.ui.kit.stepBarsShown(2))
        assertTrue(dev.claudefleet.mobile.ui.kit.stepBarReached(2, 2))
        assertFalse(dev.claudefleet.mobile.ui.kit.stepBarReached(3, 2))
    }

    @Test
    fun what_counts_as_typed() {
        assertFalse(newSessionTyped(NewSessionUiState()))
        assertTrue(newSessionTyped(NewSessionUiState(branch = "fix/x")))
        assertFalse(addProjectTyped("", " ", "", ""))
        assertTrue(addProjectTyped("", "", "acme", ""))
        assertFalse(trackerTyped(TrackerWizard()))
        assertTrue(trackerTyped(TrackerWizard(siteUrl = "https://x")))
        // Sign in again starts from the tracker's own address: only a token typed counts.
        assertFalse(trackerTyped(TrackerWizard(siteUrl = "https://x", created = ACME, email = "me@acme.io")))
        assertTrue(trackerTyped(TrackerWizard(created = ACME, secret = "t")))
    }

    @Test
    fun closing_the_tracker_wizard_asks_only_when_typed() = runTest {
        val vm = TrackersViewModel(TrackersFleet(WITH_WORK_ADMIN, 13), FakeTrackerActions(), backgroundScope, true)
        runCurrent()
        vm.startConnect()
        vm.requestClose()
        runCurrent()
        assertNull(vm.state.value.wizard, "nothing typed: it just closes")

        vm.startConnect()
        vm.editSite("https://acme.atlassian.net")
        vm.back()
        runCurrent()
        assertEquals(true, vm.state.value.wizard?.askingClose)
        vm.keepEditing()
        runCurrent()
        assertEquals("https://acme.atlassian.net", vm.state.value.wizard?.siteUrl)
        assertEquals(false, vm.state.value.wizard?.askingClose)
        vm.closeWizard()
        runCurrent()
        assertNull(vm.state.value.wizard)
    }
}
