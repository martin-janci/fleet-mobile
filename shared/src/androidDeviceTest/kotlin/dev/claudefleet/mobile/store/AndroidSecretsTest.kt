package dev.claudefleet.mobile.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one test in this repository that needs real hardware.
 *
 * `AndroidSecrets` is `EncryptedSharedPreferences` over a master key in the
 * Android Keystore. Neither exists on the JVM, so every other test of the
 * credential path uses a fake `Secrets` and none of them has ever executed a
 * line of this class. The design asks for exactly this round trip — "Android
 * instrumentation: deliberately minimal — secure storage round trip" — and five
 * tasks went by with nothing scheduling it.
 *
 * Run it with an emulator or a device attached:
 *
 * ```
 * ./gradlew :shared:connectedAndroidDeviceTest
 * ```
 *
 * Each test uses its own preferences file so the cases cannot see each other's
 * state, and `@After` deletes it: an encrypted prefs file left behind would make
 * the next run read a value it did not write.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSecretsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fileName: String
    private lateinit var secrets: AndroidSecrets

    private val credential = Credentials(
        hub = "https://fleet.example.com",
        token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        name = "phone",
        mode = Credentials.FULL,
    )

    @Before
    fun setUp() {
        fileName = "test-secrets-${System.nanoTime()}"
        secrets = AndroidSecrets(context, fileName)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(fileName)
    }

    /**
     * Write, read back, clear — and then the point of the whole test: the entry
     * must be **gone**, not present and blank.
     *
     * `remove(key)` and `putString(key, "")` are one line apart in the source and
     * worlds apart in effect. A blanked entry reads back as null anyway, because
     * `decodeCredentials` rejects a blank string, so a test that only asserted
     * `read() == null` would pass over a store that still held a slot the
     * operator had been told was forgotten. The check goes past `AndroidSecrets`
     * to the store itself and asks whether the key is there at all.
     */
    @Test
    fun a_credential_survives_a_round_trip_and_clear_removes_the_entry() = runTest {
        assertNull(secrets.read(), "a fresh store must hold nothing")
        assertFalse(KEY in decryptedKeys(), "a fresh store must not have the entry")

        secrets.write(credential)

        val read = secrets.read()
        assertEquals(credential, read, "the credential must come back exactly as it was written")
        // Field by field as well: `Credentials.equals` is hand-written, and a
        // bug in it would let the assertion above pass on a wrong value.
        assertEquals(credential.hub, read?.hub)
        assertEquals(credential.token, read?.token)
        assertEquals(credential.name, read?.name)
        assertEquals(credential.mode, read?.mode)
        assertTrue(KEY in decryptedKeys(), "the entry must exist after a write")

        secrets.clear()

        assertNull(secrets.read(), "the credential must be unreadable after clear()")
        assertFalse(
            KEY in decryptedKeys(),
            "clear() must REMOVE the entry, not blank it",
        )
    }

    /**
     * A second write replaces the first rather than adding to it.
     *
     * Re-pairing against a different hub goes through this path, and a store
     * that kept the old entry would leave a revoked token on disk.
     */
    @Test
    fun writing_twice_leaves_one_credential() = runTest {
        secrets.write(credential)
        val second = Credentials(
            hub = "https://other.example.com",
            token = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            name = "tablet",
            mode = Credentials.READONLY,
        )
        secrets.write(second)

        assertEquals(second, secrets.read())
        assertEquals(setOf(KEY), decryptedKeys(), "exactly one entry, holding the newer credential")
    }

    /**
     * Nothing readable is on disk.
     *
     * This is the claim the class exists for and the one a fake `Secrets` can
     * never make: the preference *key* and its value are both encrypted, so
     * neither the key name nor the token appears in the file.
     * `PrefKeyEncryptionScheme.AES256_SIV` and
     * `PrefValueEncryptionScheme.AES256_GCM` are what `AndroidSecrets` asks for;
     * this checks that it got them.
     */
    @Test
    fun neither_the_key_nor_the_token_is_stored_in_the_clear() = runTest {
        secrets.write(credential)

        val file = File(context.dataDir, "shared_prefs/$fileName.xml")
        assertTrue(file.isFile, "expected the preferences file at ${file.path}")
        val text = file.readText()

        assertFalse(credential.token in text, "the token is on disk in the clear")
        assertFalse(KEY in text, "the preference key is on disk in the clear")
        assertFalse(credential.hub in text, "the hub URL is on disk in the clear")
        assertFalse(credential.name in text, "the client name is on disk in the clear")
    }

    /**
     * A store that was never written to reads as empty rather than throwing.
     *
     * `AppSession.restore()` runs this on every cold start and has no catch, so
     * an exception here is a crash on launch with no way out but a reinstall.
     */
    @Test
    fun reading_or_clearing_an_empty_store_is_not_an_error() = runTest {
        assertNull(secrets.read())
        // Clearing a store that holds nothing is not an error either:
        // `SettingsViewModel.forget()` can be tapped twice.
        secrets.clear()
        assertNull(secrets.read())
    }

    /**
     * The entry names as the store itself sees them.
     *
     * Not the raw XML: `EncryptedSharedPreferences` encrypts preference keys as
     * well as values, so the names in the file are base64 ciphertext and a test
     * looking for [KEY] there would pass whether the entry existed or not. This
     * opens a second handle on the same file, which is also a small proof that
     * the master key is stable across instances rather than regenerated.
     */
    @Suppress("DEPRECATION")
    private fun decryptedKeys(): Set<String> {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs: SharedPreferences = EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return prefs.all.keys
    }

    private companion object {
        /**
         * Spelled out rather than read from `SECRETS_ACCOUNT`, because this test
         * is also the record of what is on disk: changing the constant must
         * fail here and make someone decide what happens to the credentials
         * already stored under the old name.
         */
        const val KEY = "hub-credentials"
    }
}
