package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.AuthActions
import dev.claudefleet.mobile.data.AuthState
import dev.claudefleet.mobile.data.VersionActions
import dev.claudefleet.mobile.store.Credentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Settings screen draws. */
data class SettingsUiState(
    /** The hub this device is paired with; empty once it is not. */
    val hub: String = "",
    /** The name the operator chose at `fleet-hub pair --name …`. */
    val clientName: String = "",
    /** `full` or `readonly`. */
    val mode: String = "",
    /** This app's version — the phone's, from its own build. */
    val appVersion: String = "",
    /**
     * The HUB's version, blank until it is read (and if it will not answer).
     *
     * Kept apart from [appVersion] rather than folded into one "version"
     * field, because they are two programs: a phone updated from the store
     * and a hub the operator upgrades, on separate release trains. One number
     * on this screen could only have been one of them, and whichever it was,
     * it was being read as the other half the time.
     */
    val hubVersion: String = "",
    val forgetting: Boolean = false,
    val error: String? = null,
) {
    /**
     * The same question [Credentials.canWrite] asks, answered by the same rule.
     *
     * It used to be `mode == Credentials.READONLY`, which is not the negation
     * of the permission the session screen enforces — the two agreed only on
     * the two literal modes. On anything else this screen showed the raw mode
     * string, as though it were an access level in good standing, *while* the
     * prompt box was enabled. So the one screen whose job is to tell a person
     * what this device may do was wrong in the same direction as the bug it
     * ought to have exposed.
     */
    val readOnly: Boolean get() = !Credentials.grantsWrite(mode)

    /** Nothing to forget once there is no credential, and not twice at once. */
    val canForget: Boolean get() = !forgetting && hub.isNotBlank()
}

/**
 * Settings: which hub, under what name, with what rights, on what version —
 * the app's and the hub's, named apart — and one button that drops the
 * credential.
 *
 * **There is no revoke.** Cancelling a token for good is fleet administration,
 * which the hub reserves for the master credential and refuses a client's
 * (`mcp/guard.rs`), and it is the operator's own from the terminal. The screen
 * does not offer it, and the reason it cannot is structural rather than a
 * matter of what got drawn: [AuthActions] has two methods, and neither of them
 * reaches the hub's client registry. Forgetting is local, and the screen says
 * so, because "forgotten" and "cancelled" differ by exactly the thing that
 * matters when a phone is lost.
 *
 * A refused forget is reported and the screen stays paired. `Secrets.clear()`
 * throws rather than failing quietly for precisely this reason: saying the
 * credential is gone while it is still on disk would be the one lie worth
 * avoiding here, and the next cold start would find it.
 */
class SettingsViewModel(
    private val auth: AuthActions,
    scope: CoroutineScope,
    private val appVersion: String,
    /**
     * How the hub's own version is read. Left out, nothing asks and the field
     * stays blank — which is what the tests below rely on to keep "the
     * Settings screen makes no request at all" literally true of a screen
     * whose other fields all come from the stored credential.
     */
    private val versions: VersionActions? = null,
) {
    private data class Local(
        val forgetting: Boolean = false,
        val error: String? = null,
        val hubVersion: String = "",
    )

    private val local = MutableStateFlow(Local())
    private val work = scope

    val state: StateFlow<SettingsUiState> =
        combine(auth.state, local) { auth, l -> assemble(auth, l) }
            .stateIn(scope, SharingStarted.Eagerly, assemble(auth.state.value, local.value))

    /**
     * Drop this device's credential.
     *
     * Returns to Pair by way of [AuthActions.state] rather than by publishing a
     * flag of its own: the credential's existence has one owner, and a screen
     * holding a second opinion about it is how a store that refused to clear
     * ends up looking cleared.
     */
    fun forget(): Job = work.launch {
        // Read from `local`, not from `state`: `state` is a `stateIn` of a
        // `combine` and trails by a dispatch, and a second tap must not depend
        // on how promptly a collector was resumed.
        if (local.value.forgetting) return@launch
        // `copy`, not a fresh `Local`: the hub version read when the screen
        // opened is still true of the hub, and a forget that FAILS leaves the
        // screen paired — with a blanked-out version field, if this threw it
        // away on the way.
        local.update { it.copy(forgetting = true, error = null) }
        try {
            auth.forget()
            local.update { it.copy(forgetting = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(forgetting = false, error = explain(t)) }
        }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    private val _place = MutableStateFlow<SettingsPlace>(SettingsPlace.Home)

    /**
     * Which of the New layout's Settings pages is up (redesign 14.11): its
     * home, This phone, or one group. Kept here, beside the screen's other
     * state, so leaving Settings and coming back lands where the person was.
     */
    val place: StateFlow<SettingsPlace> = _place.asStateFlow()

    fun open(place: SettingsPlace) {
        _place.value = place
    }

    /** Back one level inside Settings; false when already on its home, so the caller's back goes on. */
    fun back(): Boolean {
        if (_place.value == SettingsPlace.Home) return false
        _place.value = SettingsPlace.Home
        return true
    }

    /**
     * Read the hub's version, once per visit to the screen.
     *
     * On opening rather than at construction: this view model is built when
     * the paired UI mounts and lives as long as it does, so a hub upgraded in
     * the meantime would otherwise be reported at whatever it was running
     * when the app started. A failure leaves the previous answer standing —
     * a version that was true a minute ago beats a dash — and says nothing,
     * since [VersionActions.hubVersion] is a label, not an action somebody
     * asked for.
     */
    fun load(): Job = work.launch {
        val v = versions?.hubVersion() ?: return@launch
        local.update { it.copy(hubVersion = v) }
    }

    private fun assemble(auth: AuthState, l: Local): SettingsUiState {
        val credentials = (auth as? AuthState.Paired)?.credentials
        return SettingsUiState(
            hub = credentials?.hub.orEmpty(),
            clientName = credentials?.name.orEmpty(),
            mode = credentials?.mode.orEmpty(),
            appVersion = appVersion,
            // Not shown while unpaired: there is no hub to have a version.
            hubVersion = if (credentials == null) "" else l.hubVersion,
            forgetting = l.forgetting,
            error = l.error,
        )
    }
}
