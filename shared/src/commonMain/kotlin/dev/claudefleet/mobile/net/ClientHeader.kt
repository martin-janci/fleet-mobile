package dev.claudefleet.mobile.net

import dev.claudefleet.mobile.update.AppVersion
import kotlin.concurrent.Volatile

/**
 * `X-Fleet-Client` (claude-fleet update design §6.3): every request to the
 * hub says what this app is, so the hub knows the phone's build before it
 * ever asks `/update/check`.
 *
 * ```text
 * X-Fleet-Client: android/0.9.5 (android-aarch64; build 1a2b3c4; contract 0-14)
 * ```
 *
 * Sent to the hub only — [HubClient] and [HubEventStream] add it — and never
 * to GitHub or anywhere else this app fetches from. The contract window comes
 * from [MIN_HUB_CONTRACT] / [MAX_HUB_CONTRACT], so it cannot drift.
 */
const val CLIENT_HEADER: String = "X-Fleet-Client"

/** What this build is, as the platform knows it: `android` / `aarch64` / the short commit. */
data class ClientPlatform(
    /** `android` or `ios`: the hub's component name. */
    val component: String,
    /** `aarch64`, `x86_64`, `armv7`. */
    val arch: String,
    /** The commit it was built from, or null for a local build. */
    val build: String? = null,
)

/**
 * The header's value, or null when [version] is not a release version (a
 * local `dev` build) — the hub ignores a header it cannot parse, so sending
 * one says nothing.
 */
fun clientHeaderValue(platform: ClientPlatform, version: String): String? {
    val v = AppVersion.parse(version) ?: return null
    if (!platform.component.isWord() || !platform.arch.isWord()) return null
    val parts = buildList {
        add("${platform.component}-${platform.arch}")
        platform.build?.take(12)?.takeIf { it.isWord() && it != "unknown" }?.let { add("build $it") }
        add("contract $MIN_HUB_CONTRACT-$MAX_HUB_CONTRACT")
    }
    return "${platform.component}/$v (${parts.joinToString("; ")})"
}

private fun String.isWord(): Boolean =
    isNotEmpty() && length <= 40 && all { it.isLetterOrDigit() && it.code < 128 || it in "-_.+" }

/**
 * The value every hub request carries, set once by `AppContainer`. Global
 * rather than threaded through every `HubClient` constructor: what this
 * build is does not change while the process runs.
 */
object FleetClient {
    @Volatile
    var header: String? = null
        internal set
}
