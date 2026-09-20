package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.net.HubClient
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.store.Credentials
import dev.claudefleet.mobile.store.Secrets
import dev.claudefleet.mobile.store.SecretsUnavailable
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a 401 means, for whoever is showing it: the Pair screen once
 * [AppSession.revoke] has run, or [FleetRepository]'s own connection banner in
 * the moment before it does. Defined once, here, since `AppSession` is the
 * class this file's own KDoc calls "the one place a credential is created or
 * destroyed."
 */
internal const val REVOKED_CREDENTIAL_REASON =
    "the hub no longer accepts this device's credential. Pair again to carry on."

/**
 * The hub issued a credential and this device could not keep it.
 *
 * A [SecretsUnavailable], so `explain()` shows its message and so it keeps that
 * type's promise: what it repeats is the store's own app-authored sentence and
 * never the credential. The token it failed to save is **not** mentioned, and
 * cannot be — that is the one value in this app worth a rule of its own.
 *
 * It exists because the two halves of this failure mean different things to the
 * person holding the phone. "The credential could not be written" describes the
 * store. It does not say that a pairing code was spent to get here, that the
 * hub now holds a client row nothing can use, or that trying the same code
 * again — the obvious next move, and the one the screen invites by keeping the
 * code in the field — will fail with a different error that reads like a typo.
 */
class CredentialNotKept(reason: String?) : SecretsUnavailable(
    buildString {
        append("the hub issued this device's credential, but it could not be saved")
        if (!reason.isNullOrBlank()) append(": ").append(reason)
        append(". That pairing code is spent — run `fleet-hub pair` again for a new one.")
    },
)

/** Whether this device holds a credential for a hub. */
sealed class AuthState {
    /** The store has not been read yet — the state at a cold start. */
    data object Unknown : AuthState()

    /** No credential: the app shows Pair. */
    data object Unpaired : AuthState()

    data class Paired(val credentials: Credentials) : AuthState()
}

/**
 * The app's own answer to "am I paired?", and the one place a credential is
 * created or destroyed.
 *
 * Two rules live here rather than in the screens:
 *
 *  1. **Pairing** goes to the base the code named, and stores what comes back.
 *  2. **A 401 clears the credential.** The hub answers 401 when a token is
 *     unknown or has been revoked, and only the operator can issue another, so
 *     the honest thing is to forget it and return to Pair rather than retry
 *     against a door that will not open. Every authenticated call goes through
 *     [withClient] so that no screen can forget to do this.
 *
 * Forgetting is not revoking. A token this app drops stays valid on the hub
 * until the operator revokes it from the terminal, which the app cannot do and
 * deliberately does not offer.
 */
class AppSession(
    private val secrets: Secrets,
    private val http: HttpClient,
) : AuthActions {
    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _unpairReason = MutableStateFlow<String?>(null)
    override val unpairReason: StateFlow<String?> = _unpairReason.asStateFlow()

    override fun clearUnpairReason() {
        _unpairReason.value = null
    }

    /** The credential in hand, or null. */
    fun credentials(): Credentials? = (_state.value as? AuthState.Paired)?.credentials

    /** Read the store once at start-up and publish what it held. */
    suspend fun restore(): AuthState {
        val stored = secrets.read()
        val next = if (stored == null) AuthState.Unpaired else AuthState.Paired(stored)
        _state.value = next
        return next
    }

    /**
     * Redeem a scanned or typed pairing code and keep what it buys.
     *
     * [scanned] is a full pair URL or a bare code; [base] supplies the hub for
     * the bare-code case, where nothing in the input names one. Nothing is
     * stored unless the hub answers with a token, so a refused code leaves the
     * device exactly as it was.
     */
    override suspend fun pair(scanned: String, base: String?): Credentials {
        val target = PairTarget.require(scanned)
        // The typed address goes through the same check as a scanned one
        // (`hubBase`), rather than through `trim()` alone: it is the field an
        // operator dictates over the phone and a person pastes into, so it is
        // the easier of the two to get something wrong into, not the harder.
        val reached = target.base
            ?: base?.let { hubBase(it) }
            ?: throw NotAPairingCode(NotAPairingCode.NO_HUB)

        val result = HubClient(http, reached).pair(target.code)
        val credentials = Credentials(
            hub = preferredBase(echoed = result.hub, reached = reached),
            token = result.token,
            name = result.name,
            mode = result.mode,
        )
        // Everything from here on happens *after* the code has been spent, and
        // the message has to say so. `POST /pair` calls
        // `PairingRegistry::consume` before it mints anything, and the hub's own
        // comment on the failure branch is "the code is spent either way — mint
        // a new one". So a store that will not write leaves the person holding
        // a code that can never work again, and the honest thing is to tell
        // them that rather than to report only the write.
        //
        // Reachable, and on the worst day for it: `AndroidSecrets.prefs` is
        // null when the Keystore master key is gone but the preferences file
        // survived — the state a phone is in after being restored from a
        // backup, which is exactly when somebody is setting the app up and
        // pairing for the first time. Without this they read "the secure store
        // could not be opened", try the same code again, and get "that code
        // was refused" — a second, contradictory error that reads like a typo
        // and costs them another trip to the terminal.
        try {
            secrets.write(credentials)
        } catch (e: SecretsUnavailable) {
            throw CredentialNotKept(e.message)
        }
        _state.value = AuthState.Paired(credentials)
        return credentials
    }

    /**
     * Drop the credential. Does not cancel it; see the class comment.
     *
     * User-initiated — Settings' *Forget this hub* is the one caller — so any
     * [unpairReason] a 401 left standing is cleared rather than carried: this
     * flip was not the hub's doing, and the Pair screen must not explain itself
     * with the wrong reason.
     */
    override suspend fun forget() {
        secrets.clear()
        _unpairReason.value = null
        _state.value = AuthState.Unpaired
    }

    /**
     * Drop the credential because the hub itself refused it, and record why.
     *
     * The one difference from [forget]: [unpairReason] is set rather than
     * cleared, so the Pair screen can say more than "you are signed out."
     * [withClient]'s own 401 handling and [FleetRepository]'s `onRevoked`
     * (wired up in `AppContainer`) are the only two 401 paths in the app, and
     * both go through here rather than through [forget].
     */
    suspend fun revoke() {
        secrets.clear()
        _unpairReason.value = REVOKED_CREDENTIAL_REASON
        _state.value = AuthState.Unpaired
    }

    /**
     * Run [block] against a client built from the stored credential, dropping
     * that credential if the hub answers 401.
     *
     * Every authenticated call in the app goes through here, so "a 401 returns
     * us to Pair" is one rule in one place rather than a habit each screen has
     * to remember.
     */
    suspend fun <T> withClient(block: suspend (HubClient) -> T): T {
        val credentials = credentials()
            ?: secrets.read()?.also { _state.value = AuthState.Paired(it) }
            ?: throw HubError.Unauthorized("this device is not paired")

        return try {
            block(HubClient(http, credentials.hub, credentials.token))
        } catch (e: HubError.Unauthorized) {
            // `clear()` throws rather than failing quietly, and that must not
            // swallow the 401: the 401 is what routes the app back to Pair, and
            // a store that refused to forget is the lesser problem of the two.
            // The state deliberately stays `Paired` in that case — publishing
            // `Unpaired` while the token is still on disk is exactly the lie
            // that would tell. `revoke()` mirrors that: `unpairReason` is set
            // only after `secrets.clear()` returns, so a store that refuses to
            // forget leaves neither the state nor the reason behind.
            try {
                revoke()
            } catch (_: Exception) {
                // Nothing to add: the caller is about to be told about the 401.
            }
            throw e
        }
    }

    private companion object {
        /**
         * Which base URL to keep after pairing.
         *
         * The hub echoes its own idea of where to come back to, and normally
         * that is the right one: the name scanned off a QR may be one of
         * several that reach it, and only the hub knows its public address.
         *
         * It is checked exactly as a scanned or typed address is — see
         * [hubBase] — and an echo that would be refused from either of those
         * loses to the address that demonstrably worked.
         *
         * Beyond that, the field is the configured public URL *or*, when none is
         * configured, the hub's own loopback base. `http://127.0.0.1:8899`
         * means "this phone" once it is stored on a phone, and the app would
         * never reach the hub again. So a loopback echo loses to the address
         * that demonstrably just worked.
         */
        fun preferredBase(echoed: String, reached: String): String {
            // This was the THIRD door into `Credentials.hub` and the one the
            // "both now go through one `hubBase`" commit did not touch. A hub
            // that echoes `https://someone:secret@evil.example.com` had it
            // stored verbatim and printed unredacted by `Credentials.toString()`,
            // which is the exact failure the userinfo rule exists to prevent —
            // arriving through the entry point nobody checked.
            //
            // Severity is lower than the typed field, because fleet's own
            // `HubBase::public` refuses all of this before it can be echoed and
            // someone who can rewrite the pair response cannot mint a token
            // anyway. It is defence in depth on a field this app has decided
            // matters, and leaving one of three doors open reads as closed.
            // THE ORDER OF THESE TWO LINES IS LOAD-BEARING. `hubBase` runs
            // FIRST, and the only value this function can return other than
            // `reached` is `hubBase`'s output — never `echoed` itself. That is
            // the whole of what keeps `https://user:pw@evil.example.com` out of
            // `Credentials.hub`, since refusing userinfo is `hubBase`'s job and
            // not `isLoopbackUrl`'s.
            //
            // A rewrite that tested `isLoopbackUrl(echoed)` first and returned
            // the raw `echoed` on the other branch would look equivalent, pass a
            // reading, and store the password. `EchoedBaseTest` fails if it is
            // ever written that way — including the case that names the harm,
            // that `hub` is the one field `Credentials.toString()` prints in the
            // clear.
            val candidate = hubBase(echoed) ?: return reached
            return if (isLoopbackUrl(candidate)) reached else candidate
        }
    }
}
