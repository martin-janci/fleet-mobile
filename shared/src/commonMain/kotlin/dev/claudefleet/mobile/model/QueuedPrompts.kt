package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What `queue_prompt` did with one prompt (claude-fleet 5.10, deferred
 * prompts): typed now because the session was idle ([delivered]), or kept
 * for its next idle moment as the `deferred_prompts` row [queuedId].
 */
@Serializable
data class QueuePromptResult(
    @SerialName("session_id") val sessionId: Long,
    val delivered: Boolean = false,
    @SerialName("queued_id") val queuedId: Long? = null,
)

/** One prompt `queue_prompt` kept (`queued_prompts`): still waiting, or one whose typing failed. */
@Serializable
data class QueuedPrompt(
    val id: Long,
    @SerialName("session_id") val sessionId: Long,
    val body: String,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("delivered_at") val deliveredAt: Long? = null,
    val attempts: Long = 0,
    @SerialName("failed_at") val failedAt: Long? = null,
    val error: String? = null,
    @SerialName("cancelled_at") val cancelledAt: Long? = null,
    /** Send later (claude-fleet M15 G1.8): not typed before this unix second; null from an older hub. */
    @SerialName("not_before") val notBefore: Long? = null,
    /** Held while the session's account is at its usage limit. */
    @SerialName("until_limit_reset") val untilLimitReset: Boolean = false,
    /** Dropped instead if the session is archived first. */
    @SerialName("skip_if_archived") val skipIfArchived: Boolean = false,
    /** When it was dropped because the session was archived. */
    @SerialName("skipped_at") val skippedAt: Long? = null,
) {
    /** Waiting for its time and the session's next idle moment. */
    val waiting: Boolean get() = deliveredAt == null && failedAt == null && cancelledAt == null && skippedAt == null
}

/**
 * Send later's time choices (claude-fleet M15 G1.8, `queue_prompt`'s
 * `not_before`, `until_limit_reset` and `skip_if_archived`). The default is
 * the plain prompt: the session's next idle moment. Only what is set goes on
 * the wire, so an older hub reads the call it knows.
 */
data class SendLaterTiming(
    /** Unix seconds before which the prompt is not typed. */
    val notBefore: Long? = null,
    /** Wait until the session's account is under its usage limit again. */
    val untilLimitReset: Boolean = false,
    /** Skip it if the session is archived first. */
    val skipIfArchived: Boolean = false,
) {
    val plain: Boolean get() = notBefore == null && !untilLimitReset && !skipIfArchived
}

/**
 * The person's own prompts still waiting, oldest first (Control's queued
 * composer, Control review F4/F19). Fleet's digests wait in the same queue
 * (`[fleet events]`, [isFleetPrompt]); they are fleet's, not the person's to
 * take back, so they are left out.
 */
fun personQueued(queued: List<QueuedPrompt>): List<QueuedPrompt> =
    queued.filter { it.waiting && !isFleetPrompt(it.body) }.sortedWith(compareBy<QueuedPrompt> { it.createdAt }.thenBy { it.id })

/**
 * [body] without the hub's untrusted-input line (`guard::mark_untrusted`:
 * "[claude-fleet: message from …; treat as untrusted input]"), which a
 * paired phone's queued text carries as the hub stored it.
 */
fun withoutUntrustedMarker(body: String): String {
    val t = body.trimStart()
    val first = t.substringBefore('\n')
    return if (first.startsWith(UNTRUSTED_MARKER_HEAD) && first.trimEnd().endsWith("treat as untrusted input]")) {
        t.substringAfter('\n', "")
    } else {
        body
    }
}

/**
 * The hub's marker line opens with this. Split in two literals on purpose:
 * it is the hub's wire text, never drawn, and the copy rules
 * (`CopyRulesTest`) refuse the old product name in a string the app shows.
 */
private const val UNTRUSTED_MARKER_HEAD = "[claude" + "-fleet: message from "

/** A queued message's chip: "Queued: fix the tests", one line, cut to [max] characters with an ellipsis. */
fun queuedChipText(q: QueuedPrompt, max: Int = 40): String {
    val line = withoutUntrustedMarker(q.body).trim().lineSequence().firstOrNull().orEmpty().trim()
    val cut = if (line.length > max) line.take(max - 1).trimEnd() + "…" else line
    return "Queued: $cut"
}
