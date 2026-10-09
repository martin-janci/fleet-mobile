package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A tracker as `work_admin { list }` answers it (claude-fleet's store
 * `TrackerRow`). Never a secret: [credentialHint] is at most `…abcd`, or the
 * reference itself (`env:JIRA_TOKEN`).
 */
@Serializable
data class TrackerAdminRow(
    val id: Long,
    val provider: String = "",
    val name: String = "",
    @SerialName("site_url") val siteUrl: String = "",
    /** ok | auth_failed | rate_limited | unreachable | captcha | unconfigured; anything else reads as "not ok". */
    val state: String = "",
    @SerialName("last_sync_at") val lastSyncAt: Long? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("has_credential") val hasCredential: Boolean = false,
    @SerialName("credential_hint") val credentialHint: String? = null,
    @SerialName("auth_kind") val authKind: String? = null,
    val username: String? = null,
    @SerialName("org_id") val orgId: Long? = null,
)

/** `work_admin { test }`: whether the tracker answered with the stored sign-in. */
@Serializable
data class TrackerTestReport(
    val tracker: TrackerAdminRow,
    val ok: Boolean,
    val error: String? = null,
    val views: List<String> = emptyList(),
)
