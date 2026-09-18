package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.net.HubClient
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.store.Credentials
import dev.claudefleet.mobile.store.Secrets
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    val state: StateFlow<AuthState> = _state.asStateFlow()

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
    suspend fun pair(scanned: String, base: String? = null): Credentials {
        val target = PairTarget.require(scanned)
        val hubBase = target.base
            ?: base?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: throw NotAPairingCode(
                "that code does not say which hub it belongs to. Scan the QR " +
                    "instead, or type the hub's address as well.",
            )

        val result = HubClient(http, hubBase).pair(target.code)
        val credentials = Credentials(
            hub = preferredBase(echoed = result.hub, reached = hubBase),
            token = result.token,
            name = result.name,
            mode = result.mode,
        )
        secrets.write(credentials)
        _state.value = AuthState.Paired(credentials)
        return credentials
    }

    /** Drop the credential. Does not revoke it; see the class comment. */
    suspend fun forget() {
        secrets.clear()
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
            forget()
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
         * But that field is the configured public URL *or*, when none is
         * configured, the hub's own loopback base. `http://127.0.0.1:8899`
         * means "this phone" once it is stored on a phone, and the app would
         * never reach the hub again. So a loopback echo loses to the address
         * that demonstrably just worked.
         */
        fun preferredBase(echoed: String, reached: String): String {
            val candidate = echoed.trim().trimEnd('/')
            if (candidate.isEmpty()) return reached
            return if (isLoopback(candidate)) reached else candidate
        }

        fun isLoopback(url: String): Boolean {
            val authority = url.substringAfter("://", "").substringBefore('/')
            val host = when {
                authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
                else -> authority.substringBefore(':')
            }
            return host.equals("localhost", ignoreCase = true) ||
                host == "::1" ||
                host.startsWith("127.")
        }
    }
}
