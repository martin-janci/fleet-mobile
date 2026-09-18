@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.AppSession
import dev.claudefleet.mobile.data.AuthState
import dev.claudefleet.mobile.store.Credentials
import dev.claudefleet.mobile.store.Secrets
import dev.claudefleet.mobile.store.SecretsUnavailable
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN = "tok-0123456789abcdef-SECRET"
private const val HUB_URL = "https://hub.example.com"
private const val VERSION = "0.1.0"

/**
 * The platform store, in memory. Not a mock: it holds the one credential the
 * real ones hold, so "forget leaves nothing readable" is asserted against the
 * same shape `EncryptedSharedPreferences` and the Keychain present.
 */
private class FakeSecrets(
    private var stored: Credentials? = null,
    /** When set, `clear()` throws it — the store refusing to forget. */
    private val refuseToClear: SecretsUnavailable? = null,
) : Secrets {
    /** Held open, a clear stays in flight so the disabled button can be seen. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun read(): Credentials? = stored

    override suspend fun write(credentials: Credentials) {
        stored = credentials
    }

    override suspend fun clear() {
        gate?.await()
        refuseToClear?.let { throw it }
        stored = null
    }
}

/**
 * A real [AppSession] over a fake store, rather than a fake of the session.
 *
 * Forgetting *is* the interaction between the two — the view model asks, the
 * session clears the store and republishes its state — and a fake session would
 * only assert that a method was called, which is not the thing anyone cares
 * about.
 */
private fun paired(
    credentials: Credentials = Credentials(HUB_URL, TOKEN, "phone", Credentials.FULL),
    secrets: FakeSecrets = FakeSecrets(credentials),
): Pair<AppSession, FakeSecrets> {
    // The Settings screen makes no request at all, and an engine that refuses
    // everything is how that is held to rather than merely intended.
    val http = HttpClient(MockEngine { respondError(HttpStatusCode.NotImplemented) })
    return AppSession(secrets, http) to secrets
}

class SettingsViewModelTest {

    @Test
    fun settings_names_the_hub_the_client_and_the_mode() = runTest {
        val (session, _) = paired()
        session.restore()
        val vm = SettingsViewModel(session, backgroundScope, appVersion = VERSION)
        runCurrent()

        val state = vm.state.value
        assertEquals(HUB_URL, state.hub)
        assertEquals("phone", state.clientName)
        assertEquals(Credentials.FULL, state.mode)
        assertEquals(VERSION, state.appVersion)
        assertFalse(state.readOnly)
    }

    @Test
    fun a_readonly_credential_says_so() = runTest {
        val (session, _) = paired(Credentials(HUB_URL, TOKEN, "spectator", Credentials.READONLY))
        session.restore()
        val vm = SettingsViewModel(session, backgroundScope, appVersion = VERSION)
        runCurrent()

        assertTrue(vm.state.value.readOnly)
        assertEquals(Credentials.READONLY, vm.state.value.mode)
    }

    /**
     * The hub address is shown because an operator with two hubs needs to know
     * which one this phone is on. The token is not, and never is.
     */
    @Test
    fun the_screen_shows_the_hub_and_not_the_token() = runTest {
        val (session, _) = paired()
        session.restore()
        val vm = SettingsViewModel(session, backgroundScope, appVersion = VERSION)
        runCurrent()

        val state = vm.state.value
        assertEquals(HUB_URL, state.hub)
        assertFalse(TOKEN in state.toString(), "the token reached the ui state: $state")
    }

    // -----------------------------------------------------------------------
    // Forget
    // -----------------------------------------------------------------------

    @Test
    fun forget_clears_the_credential_and_returns_to_pair() = runTest {
        val (session, secrets) = paired()
        session.restore()
        val vm = SettingsViewModel(session, backgroundScope, appVersion = VERSION)
        runCurrent()

        vm.forget()
        runCurrent()

        assertNull(secrets.read(), "the store still holds a credential")
        assertIs<AuthState.Unpaired>(session.state.value, "the app should be back at Pair")
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.forgetting)
        // Nothing is left on the screen describing a hub this device no longer
        // has a credential for.
        assertEquals("", vm.state.value.hub)
    }

    /**
     * `Secrets.clear()` throws rather than failing quietly precisely so this
     * cannot happen silently: a screen that said "forgotten" while the token was
     * still on disk would be lying, and the next cold start would find it.
     */
    @Test
    fun a_store_that_refuses_to_forget_says_so_and_stays_paired() = runTest {
        val refusing = FakeSecrets(
            Credentials(HUB_URL, TOKEN, "phone", Credentials.FULL),
            refuseToClear = SecretsUnavailable("the keystore is unavailable"),
        )
        val (session, secrets) = paired(secrets = refusing)
        session.restore()
        val vm = SettingsViewModel(session, backgroundScope, appVersion = VERSION)
        runCurrent()

        vm.forget()
        runCurrent()

        val error = assertNotNull(vm.state.value.error, "a refused forget must be reported")
        assertTrue("keystore" in error, "the store's own words should survive: $error")
        assertFalse(vm.state.value.forgetting)
        assertNotNull(
            secrets.read(),
            "the credential is still there, and the screen must not pretend otherwise",
        )
        assertIs<AuthState.Paired>(session.state.value)
        assertEquals(HUB_URL, vm.state.value.hub)
    }

    @Test
    fun the_button_is_disabled_while_forgetting() = runTest {
        val (session, secrets) = paired()
        secrets.gate = CompletableDeferred()
        session.restore()
        val vm = SettingsViewModel(session, backgroundScope, appVersion = VERSION)
        runCurrent()
        assertTrue(vm.state.value.canForget)

        vm.forget()
        runCurrent()
        assertTrue(vm.state.value.forgetting)
        assertFalse(vm.state.value.canForget)

        secrets.gate?.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.forgetting)
        // Nothing left to forget, so the button stays off — for the other reason.
        assertFalse(vm.state.value.canForget)
    }

    /**
     * The whole point of the screen's wording, and the reason the interface it
     * is handed has two methods rather than three. A second `forget` while one
     * is in flight is not a second attempt at anything.
     */
    @Test
    fun forgetting_twice_clears_the_store_once() = runTest {
        val (session, secrets) = paired()
        secrets.gate = CompletableDeferred()
        session.restore()
        val vm = SettingsViewModel(session, backgroundScope, appVersion = VERSION)
        runCurrent()

        vm.forget()
        runCurrent()
        vm.forget()
        runCurrent()

        secrets.gate?.complete(Unit)
        runCurrent()

        assertNull(secrets.read())
        assertIs<AuthState.Unpaired>(session.state.value)
    }
}
