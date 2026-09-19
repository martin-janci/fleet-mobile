package dev.claudefleet.mobile.store

import dev.claudefleet.mobile.net.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What this device needs to talk to one hub, and the only thing the app ever
 * persists.
 *
 * This store holds only the credential. The fleet list is deliberately not
 * cached here or anywhere else — see the "no cold-start cache" entry in the
 * design appendix (`docs/2026-09-18-fleet-mobile-design.md`).
 *
 * Not a `data class`, and that is the point: a generated `toString()` would
 * print [token], and the whole file exists to keep that from happening. The
 * token is a bearer credential — anything that reaches a log line, a crash
 * report or an exception message hands the fleet to whoever reads it.
 *
 * [hub] is the base URL with no trailing slash; [name] is the client name the
 * operator chose at `fleet-hub pair --name …`; [mode] is `full` or `readonly`.
 */
class Credentials(
    val hub: String,
    val token: String,
    val name: String,
    val mode: String,
) {
    /** Redacted on purpose. See the class comment. */
    override fun toString(): String =
        "Credentials(hub=$hub, name=$name, mode=$mode, token=<redacted>)"

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is Credentials && hub == other.hub && token == other.token &&
                name == other.name && mode == other.mode)

    override fun hashCode(): Int {
        var result = hub.hashCode()
        result = 31 * result + token.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + mode.hashCode()
        return result
    }

    /** True when this client may send prompts rather than only read. */
    val canWrite: Boolean get() = mode != READONLY

    companion object {
        const val READONLY: String = "readonly"
        const val FULL: String = "full"
    }
}

/**
 * The device's secure store, holding at most one [Credentials].
 *
 * One implementation per platform: `EncryptedSharedPreferences` on Android, the
 * Keychain on iOS. Deliberately an interface rather than an `expect class`,
 * because the two actuals need different constructors — Android needs a
 * `Context`, iOS needs nothing — and an `expect class` forces one shared
 * constructor signature on both. The alternative, a global application-context
 * holder installed by a `ContentProvider`, buys the `expect` keyword at the
 * price of hidden global state; the platform host passing its own store in is
 * plainer and makes the fake in the tests an ordinary implementation.
 *
 * Implementations must not log, and must not put the token in any message they
 * throw.
 */
interface Secrets {
    /** The stored credential, or null when this device is not paired. */
    suspend fun read(): Credentials?

    /** Replace whatever is stored. */
    suspend fun write(credentials: Credentials)

    /**
     * Forget the credential. This does *not* revoke it — that is the operator's.
     *
     * Throws [SecretsUnavailable] rather than returning quietly if the store
     * refused: `AppSession.forget()` publishes `Unpaired` on the strength of
     * this call, and a silent failure would leave the token on disk for the next
     * cold start to find while the operator believes it was forgotten.
     */
    suspend fun clear()
}

/**
 * The secure store refused an operation that must not fail quietly.
 *
 * Carries a description of *what* failed and never the value involved — the
 * same rule the platform implementations follow. A failed [Secrets.read] is not
 * one of these: an unreadable store reads as "not paired", because that degrade
 * is recoverable and a crash on every cold start is not.
 */
open class SecretsUnavailable(message: String) : Exception(message)

// ---------------------------------------------------------------------------
// The stored form, shared by both platform stores so the shape lives once.
// ---------------------------------------------------------------------------

internal fun Credentials.encode(): String = json.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("hub", hub)
        put("token", token)
        put("name", name)
        put("mode", mode)
    },
)

/** Null for anything that is not a complete credential, including junk. */
internal fun decodeCredentials(raw: String?): Credentials? {
    if (raw.isNullOrBlank()) return null
    val fields = try {
        json.parseToJsonElement(raw) as? JsonObject ?: return null
    } catch (_: Exception) {
        // Unreadable stored state is the same as none: the app re-pairs rather
        // than crashing on a store some earlier version wrote.
        return null
    }
    fun field(key: String): String? = (fields[key] as? JsonPrimitive)?.content
    val hub = field("hub")?.takeIf { it.isNotBlank() } ?: return null
    val token = field("token")?.takeIf { it.isNotBlank() } ?: return null
    val name = field("name")?.takeIf { it.isNotBlank() } ?: return null
    return Credentials(hub, token, name, field("mode") ?: Credentials.FULL)
}

/** The store's identity, kept identical across platforms. */
internal const val SECRETS_SERVICE: String = "dev.claudefleet.mobile"
internal const val SECRETS_ACCOUNT: String = "hub-credentials"
