package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SignInActions
import dev.claudefleet.mobile.model.HostProfile
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.LoginStatus
import dev.claudefleet.mobile.model.LoginTarget
import dev.claudefleet.mobile.model.PaneChoice
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.SignedOutLogin
import dev.claudefleet.mobile.model.asksForCode
import dev.claudefleet.mobile.model.deviceCode
import dev.claudefleet.mobile.model.expiryLine
import dev.claudefleet.mobile.model.linkMinutes
import dev.claudefleet.mobile.model.loginTarget
import dev.claudefleet.mobile.model.paneChoices
import dev.claudefleet.mobile.model.signedOutByAccount
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class SignInFleet(caps: HubCapabilities) : FleetState {
    override val sessions = MutableStateFlow(emptyList<SessionRow>())
    override val hosts = MutableStateFlow(emptyList<HostRow>())
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(caps)
    var refreshed = 0
    override suspend fun refresh() {
        refreshed++
    }
}

private class FakeSignIn : SignInActions {
    val calls = mutableListOf<String>()
    var refuseStart: Throwable? = null
    /** What each `login_status` answers, in turn; the last one repeats. */
    var statuses = mutableListOf(LoginStatus(command = "claude /login"))

    private fun who(t: LoginTarget) = "${t.host}/${t.profile.orEmpty()}"

    override suspend fun start(target: LoginTarget) {
        calls += "start ${who(target)}"
        refuseStart?.let { throw it }
    }

    override suspend fun status(target: LoginTarget): LoginStatus {
        calls += "status ${who(target)}"
        return if (statuses.size > 1) statuses.removeAt(0) else statuses.first()
    }

    override suspend fun key(target: LoginTarget, key: String) {
        calls += "key ${who(target)} $key"
    }

    override suspend fun code(target: LoginTarget, code: String) {
        calls += "code ${who(target)} $code"
    }

    override suspend fun end(target: LoginTarget) {
        calls += "end ${who(target)}"
    }
}

private val WITH_SIGN_IN = HubCapabilities(
    tools = setOf("add_account"),
    actions = mapOf("add_account" to setOf("start_login", "login_status", "login_key", "login_code", "end_login", "api_key", "setup_token", "daily_limit")),
    params = mapOf("add_account" to setOf("action", "host_alias", "profile", "again", "code", "key")),
)

private const val URL = "https://claude.ai/oauth/authorize?code=true&client_id=x"
private val WORK = LoginTarget("htz", "work")

/**
 * Sign in again on the phone (reviews w02-8 / w12-6, option a): the
 * desktop's `SignInAgain.svelte` over `add_account { again: true }`.
 */
class SignInTest {

    @Test
    fun offered_only_where_the_hub_lists_add_account_with_again_and_the_phone_may_write() = runTest {
        fun available(caps: HubCapabilities, canWrite: Boolean = true): Boolean {
            val vm = SignInViewModel(SignInFleet(caps), FakeSignIn(), backgroundScope, canWrite)
            testScheduler.runCurrent()
            return vm.state.value.available
        }
        assertTrue(available(WITH_SIGN_IN))
        assertFalse(available(WITH_SIGN_IN, canWrite = false), "a readonly pairing keeps the copy command")
        assertFalse(available(HubCapabilities()), "an older hub, or one that does not list add_account to this device")
        assertFalse(
            available(WITH_SIGN_IN.copy(params = mapOf("add_account" to setOf("action", "host_alias", "profile")))),
            "a schema without again would open a new login instead",
        )
        assertFalse(available(WITH_SIGN_IN.copy(actions = mapOf("add_account" to setOf("api_key")))), "no start_login in its enum")
    }

    @Test
    fun the_flow_starts_polls_sends_the_code_and_ends_the_pane() = runTest {
        val fleet = SignInFleet(WITH_SIGN_IN)
        val actions = FakeSignIn()
        actions.statuses = mutableListOf(
            LoginStatus(pane = "Opening the browser…", command = "CLAUDE_CONFIG_DIR=~/.claude-profiles/work claude /login"),
            LoginStatus(pane = "Browser didn't open? Use the url below\n$URL\nPaste code here if prompted >", signInUrl = URL, command = "c"),
            LoginStatus(loggedIn = true, accountUuid = "u-1", email = "a@b.c", command = "c"),
        )
        var now = 1_000_000L
        val vm = SignInViewModel(fleet, actions, backgroundScope, canWrite = true, pollMs = 2_000, clock = { now })
        runCurrent()

        vm.open(WORK)
        runCurrent()
        assertEquals(listOf("start htz/work", "status htz/work"), actions.calls)
        assertTrue(vm.state.value.started)
        assertEquals("Sign work in again on htz", vm.state.value.title)
        assertNull(vm.state.value.status?.signInUrl)

        now += 2_000
        advanceTimeBy(2_001)
        val s = vm.state.value
        assertEquals(URL, s.status?.signInUrl)
        assertTrue(s.asksForCode)
        assertEquals(now, s.linkSeenAt, "the link's ten minutes count from when it first showed")
        assertEquals("The link expires in 10 min.", s.expiry?.text)

        vm.onCodeChange("  abc#123  ")
        vm.sendCode()
        runCurrent()
        assertTrue("code htz/work abc#123" in actions.calls, "the code is sent trimmed")
        assertTrue(vm.state.value.signedIn)
        assertEquals("work on htz is signed in again as a@b.c.", vm.state.value.doneLine)
        assertEquals("", vm.state.value.code)
        assertTrue(fleet.refreshed >= 1, "the host row is read again, so the signed-out note goes")

        val polls = actions.calls.count { it.startsWith("status") }
        advanceTimeBy(10_000)
        assertEquals(polls, actions.calls.count { it.startsWith("status") }, "polling stops once signed in")

        vm.finish()
        runCurrent()
        assertEquals("end htz/work", actions.calls.last())
        assertNull(vm.state.value.target)
    }

    @Test
    fun the_host_login_is_sent_as_no_profile_and_named_the_host_login() = runTest {
        val actions = FakeSignIn()
        val vm = SignInViewModel(SignInFleet(WITH_SIGN_IN), actions, backgroundScope, canWrite = true)
        runCurrent()
        vm.open(LoginTarget("pine", null))
        runCurrent()
        assertEquals("start pine/", actions.calls.first())
        assertEquals("Sign the host login in again on pine", vm.state.value.title)
        vm.finish()
        runCurrent()
    }

    /** w12-6: a phone the hub does not trust is told how to trust it, and keeps the command. */
    @Test
    fun an_untrusted_phone_is_told_the_trust_command_and_keeps_the_copy_command() = runTest {
        val actions = FakeSignIn()
        actions.refuseStart = HubError.Tool(
            "E_FORBIDDEN",
            "adding an account from a device needs the hub's operator to trust it (a full device): fleet-hub client trust pixel",
        )
        val vm = SignInViewModel(SignInFleet(WITH_SIGN_IN), actions, backgroundScope, canWrite = true)
        runCurrent()
        vm.open(WORK)
        runCurrent()
        val s = vm.state.value
        assertTrue(s.forbidden)
        assertFalse(s.started)
        assertEquals("This phone may not sign logins in", s.error?.title)
        assertTrue("fleet-hub client trust pixel" in s.error!!.body)
        assertTrue("copy the login command" in s.error!!.body)
        assertEquals("ssh -t htz 'CLAUDE_CONFIG_DIR=~/.claude-profiles/work claude /login'", s.command)
        assertEquals(listOf("start htz/work"), actions.calls, "nothing is polled after a refusal")

        vm.finish()
        runCurrent()
        assertEquals(listOf("start htz/work"), actions.calls, "no pane was opened, so none is closed")
    }

    /** w12-3: a setup token is renewed with `claude setup-token`, never with the OAuth flow. */
    @Test
    fun a_setup_token_login_never_starts_the_oauth_flow() = runTest {
        val actions = FakeSignIn()
        val vm = SignInViewModel(SignInFleet(WITH_SIGN_IN), actions, backgroundScope, canWrite = true)
        runCurrent()
        val ci = LoginTarget("htz", "ci", token = true)
        vm.open(ci)
        runCurrent()
        assertTrue(actions.calls.isEmpty())
        assertTrue(vm.state.value.token)
        assertEquals("ssh -t htz 'claude setup-token'", vm.state.value.command)
        vm.startOver()
        runCurrent()
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun nothing_opens_where_the_hub_does_not_offer_it() = runTest {
        val actions = FakeSignIn()
        val vm = SignInViewModel(SignInFleet(HubCapabilities()), actions, backgroundScope, canWrite = true)
        runCurrent()
        vm.open(WORK)
        runCurrent()
        assertNull(vm.state.value.target)
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun a_choice_is_pressed_by_its_digit_and_start_over_opens_a_new_pane() = runTest {
        val actions = FakeSignIn()
        actions.statuses = mutableListOf(LoginStatus(pane = "Select login method:\n❯ 1. Claude account with subscription\n  2. Anthropic Console account", command = "c"))
        val vm = SignInViewModel(SignInFleet(WITH_SIGN_IN), actions, backgroundScope, canWrite = true)
        runCurrent()
        vm.open(WORK)
        runCurrent()
        assertEquals(listOf(PaneChoice("1", "Claude account with subscription"), PaneChoice("2", "Anthropic Console account")), vm.state.value.choices)
        vm.press("1")
        runCurrent()
        assertTrue("key htz/work 1" in actions.calls)
        actions.calls.clear()
        vm.startOver()
        runCurrent()
        assertEquals(listOf("end htz/work", "start htz/work", "status htz/work"), actions.calls)
        vm.finish()
        runCurrent()
    }

    @Test
    fun the_pane_helpers_read_what_the_desktop_reads() {
        assertTrue(asksForCode("Paste code here if prompted >"))
        assertFalse(asksForCode(null))
        assertEquals("ABCD-1234", deviceCode("Enter code abcd-1234 on the page"))
        assertNull(deviceCode("no code here"))
        assertEquals(15, linkMinutes("This link expires in 15 minutes"))
        assertEquals(10, linkMinutes(null))
        assertEquals(emptyList(), paneChoices(null))
        assertEquals("The link expires in 1 min.", expiryLine(0, 10, 10 * 60_000L - 1).text)
        val gone = expiryLine(0, 10, 10 * 60_000L)
        assertTrue(gone.expired)
        assertEquals("This link has expired. Start over for a new one.", gone.text)
    }

    @Test
    fun an_account_row_lists_its_signed_out_logins() {
        val htz = HostRow(
            alias = "htz",
            accountUuid = "u-host",
            signedOutLogins = listOf(SignedOutLogin(profile = null), SignedOutLogin(profile = "work"), SignedOutLogin(profile = "ci"), SignedOutLogin(profile = "unread")),
            claudeProfiles = listOf(
                HostProfile(name = "work", accountUuid = "u-work"),
                HostProfile(name = "ci", accountUuid = "u-work", credential = "setup_token"),
                HostProfile(name = "unread"),
            ),
        )
        val hidden = HostRow(alias = "old", hidden = true, accountUuid = "u-host", signedOutLogins = listOf(SignedOutLogin()))
        assertEquals(
            mapOf(
                "u-host" to listOf(LoginTarget("htz", null)),
                "u-work" to listOf(LoginTarget("htz", "work"), LoginTarget("htz", "ci", token = true)),
            ),
            signedOutByAccount(listOf(htz, hidden)),
        )
        assertEquals(LoginTarget("htz", "ci", token = true), loginTarget(htz, "ci"))
    }
}
