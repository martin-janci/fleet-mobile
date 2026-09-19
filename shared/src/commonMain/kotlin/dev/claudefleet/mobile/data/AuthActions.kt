package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.store.Credentials
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything a screen may do to this device's credential, and nothing else.
 *
 * Two methods: buy a credential with a pairing code, and drop the one we hold.
 * **There is deliberately no third.** The tool that cancels a token for good is
 * not one a client token may call — it is fleet administration, which
 * `mcp/guard.rs` reserves for the master — and cancelling is the operator's,
 * from the hub. So the Settings screen cannot offer it, and the reason it
 * cannot is that nothing in its hands reaches it.
 *
 * That is the same narrowing [SessionActions] makes for the session screen: a
 * guarantee by construction rather than a rule each screen has to remember. It
 * also keeps a screen from building its own `HubClient` and skipping
 * [AppSession.withClient], which is where "a 401 returns us to Pair" lives.
 *
 * [AppSession] is the one implementation.
 */
interface AuthActions {
    /** Whether this device holds a credential. */
    val state: StateFlow<AuthState>

    /**
     * Why the Pair screen is up instead of the fleet, or null when nothing
     * explains it — a first launch, a user-initiated [forget], or a reason
     * already shown and cleared. Only a 401 dropping the credential out from
     * under the app sets this; [forget] never does.
     *
     * Not persisted: it lives for as long as this device's [AppSession] does,
     * which is exactly as long as the explanation stays true.
     */
    val unpairReason: StateFlow<String?>

    /** Clear [unpairReason] once it has been shown, or a new pairing attempt starts. */
    fun clearUnpairReason()

    /**
     * Redeem [scanned] — a full pair URL or a bare code — for this client's own
     * token. [base] names the hub, and is consulted only when the input does
     * not name one itself.
     */
    suspend fun pair(scanned: String, base: String? = null): Credentials

    /**
     * Drop this device's credential.
     *
     * This does **not** cancel it. A token this app forgets stays good on the
     * hub until the operator cancels it from the terminal, and telling someone
     * otherwise would be the more dangerous of the two lies.
     */
    suspend fun forget()
}
