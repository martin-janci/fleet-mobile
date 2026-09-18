package dev.claudefleet.mobile.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android's secure store: `EncryptedSharedPreferences`, whose master key lives
 * in the hardware-backed Android Keystore and never leaves it. The file on disk
 * holds ciphertext for both the key and the value, so another app reading it —
 * or someone reading a backup — gets nothing.
 *
 * The credential is stored as a single JSON string under one preference key, so
 * a write is one atomic edit rather than four that could half-land.
 *
 * Nothing here logs. The token is never put in an exception message, and the
 * only place it exists in the clear is the return value of [read].
 *
 * **On the deprecation:** `EncryptedSharedPreferences` and `MasterKey` are
 * deprecated as of `androidx.security:security-crypto:1.1.0`, which AndroidX
 * shipped without a replacement API — the guidance is to use the Keystore
 * directly. They are used here because they still do exactly this job, and
 * because hand-rolling Keystore envelope encryption for one string would be a
 * larger security surface than the deprecation is a risk. The `@Suppress` is
 * deliberate and should be revisited when AndroidX names a successor.
 */
class AndroidSecrets(
    context: Context,
    private val fileName: String = DEFAULT_FILE,
) : Secrets {

    private val appContext: Context = context.applicationContext

    /**
     * Built lazily and on a worker thread: creating the master key talks to the
     * Keystore, which on a cold start can take long enough to be felt.
     *
     * Nullable, and that is the whole of review finding S2's second half. The
     * prefs *file* is restorable from a backup or a device-to-device transfer;
     * the Keystore master key that decrypts it is not. On a device that has been
     * restored, `create` (or the first `getString`) throws
     * `AEADBadTagException` / `InvalidProtocolBufferException`, and
     * `AppSession.restore()` has no catch — so the app would have crashed on
     * every cold start with no way out but a reinstall. An unreadable store is
     * treated as an empty one, which is the same degrade `decodeCredentials`
     * already makes for an unreadable *value*.
     */
    @Suppress("DEPRECATION")
    private val prefs: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            // Deliberately no logging and no rethrow: the exception carries the
            // store's identity, and there is nothing the person can do with it.
            null
        }
    }

    override suspend fun read(): Credentials? = withContext(Dispatchers.IO) {
        val store = prefs ?: return@withContext null
        val stored = try {
            store.getString(KEY_CREDENTIALS, null)
        } catch (_: Exception) {
            // The file opened but this entry will not decrypt: same degrade.
            null
        }
        decodeCredentials(stored)
    }

    override suspend fun write(credentials: Credentials) {
        withContext(Dispatchers.IO) {
            val store = prefs ?: throw SecretsUnavailable("the secure store could not be opened")
            // `commit`, not `apply`: `write` is a suspending function whose
            // caller is entitled to believe the credential is on disk when it
            // returns. `apply` would return before the write landed.
            //
            // And the result is checked (review S3): a discarded `false` here
            // means `pair()` returns, publishes `Paired`, and the credential is
            // gone at the next launch with nothing having said so.
            val wrote = store.edit().putString(KEY_CREDENTIALS, credentials.encode()).commit()
            if (!wrote) throw SecretsUnavailable("the credential could not be written")
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            // A store that will not open holds nothing this app can read, so
            // there is nothing to forget and nothing to fail about.
            val store = prefs ?: return@withContext
            // The mirror image, and the worse one: a discarded `false` leaves
            // the token on disk while `AppSession.forget()` has already
            // published `Unpaired`, so the next cold start silently pairs the
            // app again with a credential the operator believes was forgotten.
            val cleared = store.edit().remove(KEY_CREDENTIALS).commit()
            if (!cleared) throw SecretsUnavailable("the credential could not be removed")
        }
    }

    private companion object {
        const val DEFAULT_FILE = "$SECRETS_SERVICE.credentials"
        const val KEY_CREDENTIALS = SECRETS_ACCOUNT
    }
}
