package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.AuthActions
import dev.claudefleet.mobile.data.AuthState
import dev.claudefleet.mobile.store.Credentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Settings screen draws. */
data class SettingsUiState(
    /** The hub this device is paired with; empty once it is not. */
    val hub: String = "",
    /** The name the operator chose at `fleet-hub pair --name …`. */
    val clientName: String = "",
    /** `full` or `readonly`. */
    val mode: String = "",
    val appVersion: String = "",
    val forgetting: Boolean = false,
    val error: String? = null,
) {
    val readOnly: Boolean get() = mode == Credentials.READONLY

    /** Nothing to forget once there is no credential, and not twice at once. */
    val canForget: Boolean get() = !forgetting && hub.isNotBlank()
}

/**
 * Settings: which hub, under what name, with what rights, on what version of
 * the app — and one button that drops the credential.
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
) {
    private data class Local(val forgetting: Boolean = false, val error: String? = null)

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
        local.value = Local(forgetting = true)
        try {
            auth.forget()
            local.value = Local()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.value = Local(error = explain(t))
        }
    }

    fun dismissError() {
        local.value = local.value.copy(error = null)
    }

    private fun assemble(auth: AuthState, l: Local): SettingsUiState {
        val credentials = (auth as? AuthState.Paired)?.credentials
        return SettingsUiState(
            hub = credentials?.hub.orEmpty(),
            clientName = credentials?.name.orEmpty(),
            mode = credentials?.mode.orEmpty(),
            appVersion = appVersion,
            forgetting = l.forgetting,
            error = l.error,
        )
    }
}
