package dev.claudefleet.mobile.model

import kotlinx.serialization.Serializable

/**
 * One step boundary of an in-flight `new_session` (claude-fleet redesign
 * step 5.13, `fleet_core::events::StartProgress`): the `start:progress`
 * frame. [token] is the opaque string this phone minted and passed as
 * `start_token`, so only the phone that started the session can tell whose
 * start it is; the frame names no host, no session and no person.
 *
 * [step] is `worktree`, `tmux` or `agent` ([START_STEPS]); [state] is
 * `started`, `done` or `failed` (`warned` reads as done, as on the desktop).
 */
@Serializable
data class StartProgress(
    val token: String,
    val step: String,
    val index: Int = 0,
    val total: Int = 0,
    val state: String,
)

/** The three steps a start reports, in order — `StartStep::ALL` on the hub. */
val START_STEPS: List<String> = listOf("worktree", "tmux", "agent")

/** Where one start step stands, as the phone draws it. */
enum class StartStepState { PENDING, STARTED, DONE, FAILED }

/** Nothing reported yet: the call was sent, no step has answered. */
val NO_START_STEPS: Map<String, StartStepState> = START_STEPS.associateWith { StartStepState.PENDING }

private fun rank(s: StartStepState): Int = when (s) {
    StartStepState.PENDING -> 0
    StartStepState.STARTED -> 1
    StartStepState.DONE, StartStepState.FAILED -> 2
}

/**
 * Fold one frame into a start's steps — the desktop's `foldStartProgress`
 * (`src/lib/start_steps.ts`). A step only moves forward (a late `started`
 * never undoes its `done`), a failed step stays failed, a step that starts
 * closes the ones before it, and an unknown step or state changes nothing.
 */
fun Map<String, StartStepState>.folding(step: String, state: String): Map<String, StartStepState> {
    val i = START_STEPS.indexOf(step)
    if (i < 0) return this
    val next = when (state) {
        "started" -> StartStepState.STARTED
        "done", "warned" -> StartStepState.DONE
        "failed" -> StartStepState.FAILED
        else -> return this
    }
    val now = this[step] ?: StartStepState.PENDING
    if (now == StartStepState.FAILED || rank(next) < rank(now)) return this
    val out = this.toMutableMap()
    out[step] = next
    for (before in START_STEPS.take(i)) if (out[before] != StartStepState.FAILED) out[before] = StartStepState.DONE
    return out
}

/**
 * An opaque id for one start (`new_session`'s `start_token`): `st-`, the
 * time and a random tail, letters, digits and `-` only, well under the
 * hub's 64 (`validate_start_token`).
 */
fun newStartToken(nowSeconds: Long, random: kotlin.random.Random = kotlin.random.Random.Default): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    val tail = buildString { repeat(8) { append(alphabet[random.nextInt(alphabet.length)]) } }
    return "st-${nowSeconds.coerceAtLeast(0).toString(36)}-$tail"
}
