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
    is ContractVerdict.AppTooOld -> "This app is too old for this hub (contract $revision). Update the app."
}
