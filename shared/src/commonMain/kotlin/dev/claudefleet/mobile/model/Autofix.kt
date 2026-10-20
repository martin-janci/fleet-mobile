package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A known broken fleet state, as the hub's `autofix` tool answers it
 * (claude-fleet `service/autofix/api.rs`, `FindingView`): what is wrong in
 * plain words, what fleet saw, and the fix where one exists. Safe fixes run
 * on the hub by themselves; the rest wait for a person's Fix.
 */
@Serializable
data class AutofixFinding(
    val id: Long,
    val kind: String = "",
    /** `host:<alias>`, `session:<id>`, `token:<name>` or `hub`. */
    val subject: String = "",
    @SerialName("host_alias") val hostAlias: String? = null,
    /** blocking | degraded | info */
    val severity: String = "",
    /** open | verifying | apply_failed | dismissed | cleared */
    val state: String = "",
    @SerialName("first_seen_at") val firstSeenAt: Long = 0,
    @SerialName("last_seen_at") val lastSeenAt: Long = 0,
    val attempts: Int = 0,
    @SerialName("last_attempt_at") val lastAttemptAt: Long? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("dismissed_until") val dismissedUntil: Long? = null,
    /** Null only when the hub could not read the stored body. */
    val title: String? = null,
    val explanation: String? = null,
    val evidence: List<String> = emptyList(),
    val fix: AutofixFix? = null,
    val action: AutofixAction? = null,
    /**
     * Whether the hub tries the fix by itself: false when it will not
     * (automatic fixes off or paused, its tries for the day spent), null
     * from a hub older than the field.
     */
    @SerialName("auto_runs") val autoRuns: Boolean? = null,
    /** When it next tries (unix seconds), when [autoRuns]. */
    @SerialName("auto_next_at") val autoNextAt: Long? = null,
) {
    /** Hidden with Later, until the hub shows it again. */
    val dismissed: Boolean get() = state == "dismissed"

    /** Resolved: its fix ran and the re-check found it gone. */
    val cleared: Boolean get() = state == "cleared"

    /** Its fix ran and the re-check still found it. */
    val failed: Boolean get() = state == "apply_failed"

    /** Its fix ran and the hub has not re-checked yet. */
    val verifying: Boolean get() = state == "verifying"
}

/** What a finding's fix does. */
@Serializable
data class AutofixFix(
    val summary: String = "",
    /** What the fix writes, and where: the confirm lists these. */
    val changes: List<String> = emptyList(),
    /** safe | needs_approval */
    val safety: String = "",
    val reversible: Boolean = true,
    /** Whether the hub may run it by itself when it is safe. */
    val auto: Boolean = false,
)

/** A step fleet cannot take for the person (`{ "action": "login", "host" }`). */
@Serializable
data class AutofixAction(
    val action: String = "",
    val host: String? = null,
)

/** `autofix { list }`: the live findings, the recently fixed and whether this device may decide. */
@Serializable
data class AutofixFindings(
    val findings: List<AutofixFinding> = emptyList(),
    /** Cleared in the last day after a fix ran. */
    val fixed: List<AutofixFinding> = emptyList(),
    @SerialName("can_apply") val canApply: Boolean = false,
) {
    /** What a person is asked about: everything live but the hidden. */
    val problems: List<AutofixFinding> get() = findings.filterNot { it.dismissed }
}
