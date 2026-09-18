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
     */
    @Suppress("DEPRECATION")
    private val prefs: SharedPreferences by lazy {
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
    }

    override suspend fun read(): Credentials? = withContext(Dispatchers.IO) {
        decodeCredentials(prefs.getString(KEY_CREDENTIALS, null))
    }

    override suspend fun write(credentials: Credentials) {
        withContext(Dispatchers.IO) {
            // `commit`, not `apply`: `write` is a suspending function whose
            // caller is entitled to believe the credential is on disk when it
            // returns. `apply` would return before the write landed.
            prefs.edit().putString(KEY_CREDENTIALS, credentials.encode()).commit()
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(KEY_CREDENTIALS).commit()
        }
    }

    private companion object {
        const val DEFAULT_FILE = "$SECRETS_SERVICE.credentials"
        const val KEY_CREDENTIALS = SECRETS_ACCOUNT
    }
}
