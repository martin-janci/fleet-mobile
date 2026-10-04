package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A path the move would carry, or leave behind (and why). */
@Serializable
data class MovePath(val path: String = "", val bytes: Long? = null, val reason: JsonElement? = null)

/** Where the move would land: `absent`, `clean`, `dirty` or `unknown` (the probe could not answer). */
@Serializable
data class MoveTargetState(val state: String = "unknown", val head: String? = null)

/** `move_session { dry_run }`: what a move would carry and where it would land. Changes nothing. */
@Serializable
data class MovePreview(
    @SerialName("from_host") val fromHost: String = "",
    @SerialName("to_host") val toHost: String = "",
    val branch: String = "",
    @SerialName("unpushed_commits") val unpushedCommits: Int? = null,
    @SerialName("commits_ahead") val commitsAhead: Int? = null,
    val dirty: List<JsonElement> = emptyList(),
    @SerialName("ignored_carried") val ignoredCarried: List<MovePath> = emptyList(),
    @SerialName("ignored_left_behind") val ignoredLeftBehind: List<MovePath> = emptyList(),
    @SerialName("transcript_bytes") val transcriptBytes: Long = 0,
    @SerialName("target_path") val targetPath: String = "",
    val target: MoveTargetState = MoveTargetState(),
    val unknowns: List<String> = emptyList(),
)

/** `move_session`'s answer, by its `kind`. */
sealed interface MoveOutcome {
    /** Moved: the session now runs on the target, as [target]. */
    data class Moved(val target: SessionRow, val sourceKilled: Boolean, val warnings: List<String>) : MoveOutcome

    data class Preview(val preview: MovePreview) : MoveOutcome

    /** Waiting for the session to go idle, then it moves — by [deadlineUnix] at the latest. */
    data class Waiting(val toHost: String, val deadlineUnix: Long) : MoveOutcome

    data class WaitCancelled(val wasWaiting: Boolean) : MoveOutcome
}

@Serializable
private data class MovedWire(
    val target: SessionRow,
    @SerialName("source_killed") val sourceKilled: Boolean = false,
    val warnings: List<String> = emptyList(),
)

@Serializable
private data class WaitingWire(@SerialName("to_host") val toHost: String = "", @SerialName("deadline_unix") val deadlineUnix: Long = 0)

@Serializable
private data class CancelledWire(@SerialName("was_waiting") val wasWaiting: Boolean = false)

/** Reads a `move_session` reply by its `kind` tag. */
internal fun moveOutcomeOf(element: JsonElement, decode: (kotlinx.serialization.DeserializationStrategy<*>, JsonElement) -> Any?): MoveOutcome {
    val kind = ((element as? JsonObject)?.get("kind") as? JsonPrimitive)?.content
    return when (kind) {
        "preview" -> MoveOutcome.Preview(decode(MovePreview.serializer(), element) as MovePreview)
        "waiting" -> (decode(WaitingWire.serializer(), element) as WaitingWire).let { MoveOutcome.Waiting(it.toHost, it.deadlineUnix) }
        "wait_cancelled" -> MoveOutcome.WaitCancelled((decode(CancelledWire.serializer(), element) as CancelledWire).wasWaiting)
        else -> (decode(MovedWire.serializer(), element) as MovedWire).let { MoveOutcome.Moved(it.target, it.sourceKilled, it.warnings) }
    }
}
