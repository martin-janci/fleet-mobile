package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One live member of an org as `org_admin { list_members }` answers it
 * (claude-fleet company administration phase D, `MemberSummary`): richer than
 * the [OrgMember] the org overview carries, with when they joined, whether
 * they own the hub, and their devices by name.
 */
@Serializable
data class OrgMemberRow(
    @SerialName("person_id") val personId: Long,
    val name: String = "",
    @SerialName("display_name") val displayName: String? = null,
    /** `admin` / `member` / `viewer`. */
    val role: String = "",
    @SerialName("added_at") val addedAt: Long = 0,
    /** The hub's owner: administers every org, and their membership is theirs. */
    val owner: Boolean = false,
    val devices: List<String> = emptyList(),
) {
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: name
}

/** `org_admin { member_grants }`: how many of the org's sessions are shared with a person. */
@Serializable
data class MemberGrants(val watch: Int = 0, val drive: Int = 0) {
    val total: Int get() = watch + drive
}

/** `org_admin { remove_member }`. */
@Serializable
data class MemberRemoved(
    val removed: Boolean = false,
    @SerialName("revoked_grants") val revokedGrants: Int = 0,
)
