package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SignInActions
import dev.claudefleet.mobile.epochMillis
import dev.claudefleet.mobile.model.LinkExpiry
import dev.claudefleet.mobile.model.LoginStatus
import dev.claudefleet.mobile.model.LoginTarget
import dev.claudefleet.mobile.model.PaneChoice
import dev.claudefleet.mobile.model.asksForCode
import dev.claudefleet.mobile.model.deviceCode
import dev.claudefleet.mobile.model.expiryLine
import dev.claudefleet.mobile.model.linkMinutes
import dev.claudefleet.mobile.model.paneChoices
import dev.claudefleet.mobile.model.signedInAgainLine
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How often the sheet asks the hub for the login pane (the desktop's `pollMs`). */
const val SIGN_IN_POLL_MS = 2_000L

data class SignInUiState(
    /**
     * The hub lists `add_account` with `again` to this pairing and the
     * pairing may write: Sign in again is offered. Off, every entry point
     * keeps its Copy login command only.
     */
    val available: Boolean = false,
    /** The login the sheet is open for; null when it is closed. */
    val target: LoginTarget? = null,
    /**
     * The host's own login on a Mac reached over SSH ([HostRow.loginSplit]):
     * the sheet says first that a `/login` there writes a second copy beside
     * the Keychain's, and starts only on Sign in here anyway (review w03-6).
     */
    val macNote: Boolean = false,
    /** `start_login` is on its way. */
    val starting: Boolean = false,
    /** The pane opened on the host: the sheet polls it until the login is signed in. */
    val started: Boolean = false,
    /** The hub's last `login_status`. */
    val status: LoginStatus? = null,
    /** When the current link first showed (unix ms): its expiry counts from here. */
    val linkSeenAt: Long? = null,
    val nowMs: Long = 0,
    val code: String = "",
    val sending: Boolean = false,
    val error: Friendly? = null,
    /**
     * The hub does not trust this phone to sign logins in (E_FORBIDDEN from
     * `owner_device_admin`): the sheet says which command trusts it, and
     * keeps the command to run on the host instead.
     */
    val forbidden: Boolean = false,
) {
    val signedIn: Boolean get() = status?.loggedIn == true

    /** A setup-token profile: the sheet shows its `claude setup-token` guidance, never the OAuth flow. */
    val token: Boolean get() = target?.token == true

    /** The command for a terminal on the host: the hub's when it sent one, else the phone's own. */
    val command: String? get() = status?.command?.takeIf { it.isNotBlank() } ?: target?.command

    val choices: List<PaneChoice> get() = paneChoices(status?.pane)
    val deviceCode: String? get() = deviceCode(status?.pane)
    val asksForCode: Boolean get() = asksForCode(status?.pane)
    val expiry: LinkExpiry? get() = linkSeenAt?.let { expiryLine(it, linkMinutes(status?.pane), nowMs) }

    /** "Sign work in again on htz". */
    val title: String get() = target?.let { "Sign ${it.name} in again on ${it.host}" }.orEmpty()

    /** What Done says once signed in. */
    val doneLine: String? get() = target?.takeIf { signedIn }?.let { signedInAgainLine(it, status?.email) }
}

/**
 * Sign in again on the phone (reviews w02-8 / w12-6, option a): the
 * desktop's `SignInAgain.svelte` and its login step. [open] asks the hub to
 * open a login pane over the signed-out login (`add_account { start_login,
 * again }`), then [SIGN_IN_POLL_MS] polls `login_status` for the sign-in
 * link and the pane until the hub reports the login signed in. The person
 * opens the link in the phone's browser, signs in, and pastes the code
 * ([sendCode]); [finish] closes the pane (`end_login`). One instance for the
 * app: Control's login card, the host sheet's Logins and the Accounts rows
 * all open the same sheet.
 *
 * A setup-token profile has no `/login` to renew (the hub refuses one), so
 * [open] only shows its guidance. A phone the hub does not trust gets
 * E_FORBIDDEN on `start_login`: the sheet stays open with the trust command
 * and the copy-command fallback.
 */
class SignInViewModel(
    private val fleet: FleetState,
    private val actions: SignInActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    private val pollMs: Long = SIGN_IN_POLL_MS,
    private val clock: () -> Long = { epochMillis() },
) {
    private val local = MutableStateFlow(SignInUiState())
    private var poller: Job? = null

    val state: StateFlow<SignInUiState> = combine(local, fleet.capabilities) { l, caps ->
        l.copy(available = canWrite && caps.signInAgain)
    }.stateIn(scope, SharingStarted.Eagerly, SignInUiState())

    /** Open the sheet for [target] and, unless it is a setup token, start the sign-in. */
    fun open(target: LoginTarget): Job = scope.launch {
        if (!state.value.available) return@launch
        // Another login's sheet closes its pane first.
        local.value.target?.takeIf { it != target && local.value.started }?.let { endQuietly(it) }
        poller?.cancel()
        val split = !target.token && target.profile == null &&
            fleet.hosts.value.firstOrNull { it.alias == target.host }?.loginSplit != null
        local.value = SignInUiState(target = target, starting = !target.token && !split, macNote = split, nowMs = clock())
        if (target.token || split) return@launch
        start(target)
    }

    /** The Mac note's Sign in here anyway: start the sign-in it held back. */
    fun signInAnyway(): Job = scope.launch {
        val target = local.value.target?.takeIf { local.value.macNote } ?: return@launch
        local.update { it.copy(macNote = false, starting = true) }
        start(target)
    }

    private suspend fun start(target: LoginTarget) {
        try {
            actions.start(target)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (local.value.target == target) {
                local.update { it.copy(starting = false, error = signInWords(t), forbidden = isTrustRefusal(t)) }
            }
            return
        }
        if (local.value.target != target) return
        local.update { it.copy(starting = false, started = true) }
        poller = scope.launch { pollLoop(target) }
    }

    private suspend fun pollLoop(target: LoginTarget) {
        while (local.value.target == target && !local.value.signedIn) {
            poll(target)
            if (local.value.signedIn) break
            delay(pollMs)
        }
    }

    /** One `login_status`; a failed read keeps the last one and says why, and the next poll tries again. */
    private suspend fun poll(target: LoginTarget) {
        val read = try {
            actions.status(target)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (local.value.target == target) local.update { it.copy(error = signInWords(t), forbidden = it.forbidden || isTrustRefusal(t), nowMs = clock()) }
            return
        }
        if (local.value.target != target) return
        val now = clock()
        local.update { s ->
            val url = read.signInUrl
            val seen = when {
                url == null -> s.linkSeenAt
                url != s.status?.signInUrl || s.linkSeenAt == null -> now
                else -> s.linkSeenAt
            }
            s.copy(status = read, linkSeenAt = seen, nowMs = now, error = if (read.loggedIn) null else s.error)
        }
        // The host row drops the login's signed-out note on the hub's next frame; read it now.
        if (read.loggedIn) refreshQuietly()
    }

    fun onCodeChange(code: String) {
        local.update { it.copy(code = code) }
    }

    /** Send the code the sign-in page showed (`login_code`), then read the pane again. */
    fun sendCode(): Job = scope.launch {
        val s = local.value
        val target = s.target ?: return@launch
        val code = s.code.trim()
        if (code.isEmpty() || s.sending || !s.started) return@launch
        local.update { it.copy(sending = true, error = null) }
        try {
            actions.code(target, code)
            local.update { if (it.target == target) it.copy(sending = false, code = "") else it }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { if (it.target == target) it.copy(sending = false, error = signInWords(t)) else it }
        }
        if (local.value.target == target) poll(target)
    }

    /** Press one of the pane's choices or keys (`login_key`), then read the pane again. */
    fun press(key: String): Job = scope.launch {
        val target = local.value.target?.takeIf { local.value.started } ?: return@launch
        local.update { it.copy(error = null) }
        try {
            actions.key(target, key)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { if (it.target == target) it.copy(error = signInWords(t)) else it }
        }
        if (local.value.target == target) poll(target)
    }

    /** The link expired: close the pane and open a new one (the desktop's Start over). */
    fun startOver(): Job = scope.launch {
        val target = local.value.target ?: return@launch
        if (target.token) return@launch
        poller?.cancel()
        endQuietly(target)
        local.update { it.copy(starting = true, started = false, status = null, linkSeenAt = null, error = null, forbidden = false, code = "") }
        start(target)
    }

    /** Done or Cancel: close the login pane on the host (when one was opened) and the sheet. */
    fun finish(): Job = scope.launch {
        val s = local.value
        val target = s.target ?: return@launch
        poller?.cancel()
        poller = null
        local.value = SignInUiState()
        if (s.started) endQuietly(target)
        if (s.signedIn) refreshQuietly()
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /** The sheet's clock for the link's expiry line; the sheet ticks it while open. */
    fun tick() {
        if (local.value.target != null) local.update { it.copy(nowMs = clock()) }
    }

    private suspend fun endQuietly(target: LoginTarget) {
        try {
            actions.end(target)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Best effort, as the desktop's: the pane is the host's, and a
            // later Sign in again opens a fresh one over it.
        }
    }

    private suspend fun refreshQuietly() {
        try {
            fleet.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }
}

/** The hub refused this phone as untrusted (`owner_device_admin`'s E_FORBIDDEN). */
internal fun isTrustRefusal(t: Throwable): Boolean = t is HubError.Tool && t.code == "E_FORBIDDEN"

/**
 * What a sign-in failure says. A trust refusal names the hub's command and
 * the way round it, since the copy command still works from a computer.
 */
internal fun signInWords(t: Throwable): Friendly {
    val words = friendly(t)
    if (!isTrustRefusal(t)) return words
    val command = trustHint((t as HubError.Tool).message)
    val trust = command?.let { "On the hub, run: $it. " }.orEmpty()
    return words.copy(
        title = "This phone may not sign logins in",
        body = "${trust}Only the hub's trusted full devices can sign a login in again. Or copy the login command below and run it on a computer.",
    )
}
