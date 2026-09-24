package dev.claudefleet.mobile.store

import android.content.SharedPreferences
import dev.claudefleet.mobile.net.json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Android's [Prefs]: an ordinary (unencrypted) `SharedPreferences`, one
 * JSON-encoded string array per key.
 *
 * Unlike [AndroidSecrets], nothing stored through here is a credential — the
 * quick-reply chips and the draft history are exactly what a person just
 * typed at the hub, not a token — so the plain preferences file the host
 * passes in is enough; there is no reason to pay for
 * `EncryptedSharedPreferences`'s Keystore round trip for this.
 *
 * A malformed or missing entry reads as empty rather than throwing: a first
 * run has nothing stored yet, and [dev.claudefleet.mobile.ui.QuickReplies]
 * treats an empty read the same way either way.
 */
class AndroidPrefs(private val prefs: SharedPreferences) : Prefs {
    override fun getStringList(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun putStringList(key: String, value: List<String>) {
        val encoded = json.encodeToString(ListSerializer(String.serializer()), value)
        prefs.edit().putString(key, encoded).apply()
    }
}
