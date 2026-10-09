package dev.claudefleet.mobile.update

import dev.claudefleet.mobile.net.ClientPlatform
import dev.claudefleet.mobile.net.MAX_HUB_CONTRACT
import dev.claudefleet.mobile.net.MIN_HUB_CONTRACT
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The phone's update, as its hub decides it (claude-fleet update design S8,
 * `docs/superpowers/specs/2026-09-28-mobile-update-adapter.md`).
 *
 * The phone is always paired, so it asks its hub: `POST /update/check` with
 * what it runs, answered with a decision the hub drew from the signed
 * channel and the release's signed amendment that carries this APK. The
 * hub's operator decides the mode and any pin; the phone only ever offers.
 *
 * [fallback] (fleet-mobile's GitHub releases) is asked only when there is no
 * hub decision to be had: a hub too old to have `/update/check` (404), or a
 * build that cannot describe itself ([platform] null, a local `dev` build).
 * Any other failure shows no card: the hub's silence is not a reason to go
 * around it.
 */
class HubReleases(
    private val platform: ClientPlatform?,
    private val appVersion: String,
    /** POSTs the body to the paired hub's `/update/check`; null on 404. */
    private val check: suspend (String) -> String?,
    private val fallback: ReleaseSource,
) : ReleaseSource {
    override suspend fun latest(): ReleaseInfo? {
        val body = platform?.let { checkRequest(it, appVersion) } ?: return fallback.latest()
        val answer = try {
            check(body)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Unreachable, not paired, refused: no card. A 401 has already
            // sent the app back to Pair through `withClient`.
            return null
        } ?: return fallback.latest()
        return try {
            releaseFromDecision(answer)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
    }
}

/** fleet-mobile's release page for [version], for "Full notes". */
internal fun releasePage(version: String, repo: String = "martin-janci/fleet-mobile"): String =
    "https://github.com/$repo/releases/tag/v$version"

/**
 * The `/update/check` body (`update_proto: 1`). Null when [appVersion] is not
 * a release version: the hub refuses a version it cannot parse.
 */
internal fun checkRequest(platform: ClientPlatform, appVersion: String): String? {
    val v = AppVersion.parse(appVersion) ?: return null
    val body = buildJsonObject {
        put("update_proto", 1)
        put("component", platform.component)
        putJsonObject("platform") {
            put("os", platform.component)
            put("arch", platform.arch)
            put("variant", if (platform.component == "android") "apk" else "")
        }
        putJsonObject("installed") {
            put("version", v.toString())
            platform.build?.takeIf { it.isNotBlank() && it != "unknown" }?.let { put("commit", it) }
        }
        putJsonObject("speaks") {
            putJsonObject("contract_accepts") {
                put("min", MIN_HUB_CONTRACT)
                put("max", MAX_HUB_CONTRACT)
            }
        }
    }
    return body.toString()
}

private val decisionJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

@Serializable
private data class WireDecision(
    val status: String = "unknown",
    val target: WireTarget? = null,
    val reason: WireReason? = null,
)

@Serializable
private data class WireTarget(
    val version: String,
    val mandatory: Boolean = false,
    val deadline: String? = null,
    val artifact: JsonObject = JsonObject(emptyMap()),
    val url: String? = null,
)

@Serializable
private data class WireReason(val code: String = "", val text: String = "")

private val HEX64 = Regex("^[0-9a-f]{64}$")

/**
 * A hub decision as the card the phone offers, or null when it offers
 * nothing this phone installs: `up_to_date`, `hold`, `rollback` (a phone
 * never downgrades itself), `client_too_new`, an unknown status, or a target
 * whose artifact is not a well-formed APK.
 */
internal fun releaseFromDecision(body: String): ReleaseInfo? {
    val d = decisionJson.decodeFromString(WireDecision.serializer(), body)
    if (d.status != "update_available" && d.status != "update_required") return null
    val t = d.target ?: return null
    val a = t.artifact
    fun str(k: String) = (a[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (str("kind") != "apk") return null
    val version = AppVersion.parse(t.version) ?: return null
    val url = (t.url ?: str("url"))?.takeIf { it.startsWith("https://") } ?: return null
    val sha = str("sha256")?.lowercase()?.takeIf { HEX64.matches(it) } ?: return null
    val signer = str("signer_sha256")?.lowercase()?.takeIf { HEX64.matches(it) } ?: return null
    val size = (a["size"] as? JsonPrimitive)?.longOrNull ?: 0L
    val required = d.status == "update_required"
    return ReleaseInfo(
        version = version.toString(),
        apkUrl = url,
        sizeBytes = size,
        sha256 = sha,
        notes = emptyList(),
        pageUrl = releasePage(version.toString()),
        signerSha256 = signer,
        required = required,
        reason = d.reason?.text?.takeIf { required && it.isNotBlank() }
            ?: t.deadline?.takeIf { t.mandatory }?.let { "Required by ${it.take(10)}." },
    )
}
