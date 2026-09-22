package dev.claudefleet.mobile.net

/**
 * The hub wire-contract revisions this build understands, mirrored from the
 * desktop's `src-tauri/src/backend/contract.rs` (claude-fleet commit
 * 5fa119f7, v0.2.31). A hub outside the range is refused the way the desktop
 * refuses it: the banner says which side is behind, and no row event from
 * that hub is applied.
 */
const val MIN_HUB_CONTRACT: Int = 0
const val MAX_HUB_CONTRACT: Int = 1

/**
 * What a `contract` field that cannot be read as a revision counts as.
 *
 * `Int.MAX_VALUE`, mirroring the desktop's `u32::MAX`, and deliberately above
 * [MAX_HUB_CONTRACT] so it classifies as [ContractVerdict.AppTooOld]. A hub
 * that answers `"contract": "next"`, `1.5`, `true`, `{}` or a number past
 * `Int` is speaking a shape this build has no reading of, and the safe
 * reading of "I cannot parse this hub's version" is "this hub is ahead of
 * me", not "never mind, carry on". The previous `toIntOrNull()` dropped
 * straight to `null`, which is the one value [contractVerdict] trusts
 * unconditionally — so the least readable hub got the most trust.
 */
const val UNREADABLE_CONTRACT: Int = Int.MAX_VALUE

/** Where a hub's `ready`-frame `contract` stands against [MIN_HUB_CONTRACT]..[MAX_HUB_CONTRACT]. */
sealed interface ContractVerdict {
    /** Inside range, or the hub named no `contract` at all — pre-contract, and trusted. */
    data object Ok : ContractVerdict

    /** Below [MIN_HUB_CONTRACT]: this build has moved past a shape this hub still speaks. Update the hub. */
    data class HubTooOld(val revision: Int) : ContractVerdict

    /** Above [MAX_HUB_CONTRACT]: the hub speaks a shape newer than this build understands. Update the app. */
    data class AppTooOld(val revision: Int) : ContractVerdict
}

/**
 * Classify a `ready` frame's `contract` field.
 *
 * `null` — a hub that names no `contract` at all — is [ContractVerdict.Ok]: every
 * hub released before this mechanism existed sends no such field, and the
 * desktop trusts that hub too (`hub_contract_revision` reads a missing or
 * unparseable field as revision `0`, which is in range).
 */
fun contractVerdict(revision: Int?): ContractVerdict = when {
    revision == null -> ContractVerdict.Ok
    revision < MIN_HUB_CONTRACT -> ContractVerdict.HubTooOld(revision)
    revision > MAX_HUB_CONTRACT -> ContractVerdict.AppTooOld(revision)
    else -> ContractVerdict.Ok
}

/** The banner's words for a refused [ContractVerdict], or null when there is nothing to say. */
fun ContractVerdict.sentence(): String? = when (this) {
    ContractVerdict.Ok -> null
    is ContractVerdict.HubTooOld -> "This hub is too old for this app (contract $revision). Update the hub."
    is ContractVerdict.AppTooOld ->
        if (revision == UNREADABLE_CONTRACT) {
            // Naming the revision here would print 2147483647, which is not
            // a number any hub sent — it is this app's word for "unreadable".
            "This hub reported a contract this app cannot read. Update the app."
        } else {
            "This app is too old for this hub (contract $revision). Update the app."
        }
}

/**
 * The first hub release that accepts `send_prompt { keys }` and carries
 * `pending_input` on a row. The wire-contract revision does not move for this
 * — it is an additive change — so it is gated on the hub's own version string
 * instead, via [semverAtLeast].
 */
const val HUB_VERSION_KEYS: String = "0.2.35"

/**
 * Whether [version] is at or above [floor], read as `major.minor.patch`.
 *
 * Both strings are parsed by taking the leading `major.minor.patch` digits —
 * a leading `v` is tolerated, and anything from a `-pre` or `+build` suffix
 * onward is ignored. `version` being `null` or not parsable this way (e.g.
 * `"garbage"`) reads as "not at least", the same conservative answer
 * [contractVerdict] gives an unreadable contract.
 */
fun semverAtLeast(version: String?, floor: String): Boolean {
    val v = parseSemver(version) ?: return false
    val f = parseSemver(floor) ?: return false
    return v >= f
}

private val semverPrefix = Regex("""^v?(\d+)\.(\d+)\.(\d+)""")

private fun parseSemver(raw: String?): Semver? {
    if (raw == null) return null
    val match = semverPrefix.find(raw.trim()) ?: return null
    val (major, minor, patch) = match.destructured
    return Semver(major.toInt(), minor.toInt(), patch.toInt())
}

private data class Semver(val major: Int, val minor: Int, val patch: Int) : Comparable<Semver> {
    override fun compareTo(other: Semver): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        return patch.compareTo(other.patch)
    }
}
