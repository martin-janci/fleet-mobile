package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.claudefleet.mobile.model.REPAIR_STEPS
import dev.claudefleet.mobile.model.StartStepState
import dev.claudefleet.mobile.ui.kit.FullscreenLoader
import dev.claudefleet.mobile.ui.kit.FullscreenWait
import dev.claudefleet.mobile.ui.kit.LoaderStep
import dev.claudefleet.mobile.ui.kit.StepState

/*
 * The Hex field over a session while its workspace is repaired or the
 * session recreated (MobileFullscreenLoaders). A repair on a contract-17
 * hub reports its four steps as `repair:progress` frames (gap plan G7.3)
 * and the checklist ticks them off as they land. A recreate, and a repair
 * on an older hub, is one call that answers at the end: the checklist then
 * names what the hub goes through in order and ticks nothing on the way.
 * The loader leaves when the answer lands (in the conversation, as before).
 * Leaving early is safe: the call runs on.
 */

/** Which of the two long session repairs the Hex field covers. */
enum class RepairWait { Repair, Recreate }

/** The loader's title. */
internal fun repairWaitTitle(wait: RepairWait): String = when (wait) {
    RepairWait.Repair -> "Repairing the workspace"
    RepairWait.Recreate -> "Recreating the session"
}

/**
 * What the hub goes through, in its own order (`service::repair`: probe,
 * plan, apply and verify, then tmux; a recreate kills the pane first and
 * resumes the conversation last). The first is shown running and the rest
 * pending: the hub answers once, so nothing here is ever ticked.
 */
internal fun repairWaitSteps(wait: RepairWait): List<LoaderStep> {
    val labels = when (wait) {
        RepairWait.Repair -> listOf(
            "Probe the worktree and its pane",
            "Fix what the probe found, then check again",
            "Start the pane in the worktree if it is not running",
        )
        RepairWait.Recreate -> listOf(
            "Stop the session's pane",
            "Check the worktree, repairing it if needed",
            "Start Claude again, resuming the conversation",
        )
    }
    return labels.mapIndexed { i, label -> LoaderStep(label, if (i == 0) StepState.Running else StepState.Pending) }
}

/** A reported repair step, in words: `check`, `fix`, `verify`, `pane` ([REPAIR_STEPS]). */
internal fun repairStepLabel(step: String): String = when (step) {
    "check" -> "Check the folder, worktree, branch and pane"
    "fix" -> "Fix the worktree and its branch"
    "verify" -> "Check the worktree again"
    "pane" -> "Start the pane in the worktree"
    else -> step
}

/**
 * The checklist from the hub's own reports ([reported], keyed by
 * [REPAIR_STEPS]): done steps ticked, a started one running, a failed one
 * marked so, the rest waiting. Before the first frame the first step reads
 * as running, since the call is already on its way.
 */
internal fun repairReportedSteps(reported: Map<String, StartStepState>): List<LoaderStep> {
    val anyMoved = reported.values.any { it != StartStepState.PENDING }
    return REPAIR_STEPS.mapIndexed { i, step ->
        when (reported[step] ?: StartStepState.PENDING) {
            StartStepState.DONE -> LoaderStep(repairStepLabel(step), StepState.Done)
            StartStepState.STARTED -> LoaderStep(repairStepLabel(step), StepState.Running)
            StartStepState.FAILED -> LoaderStep(repairStepLabel(step), StepState.Running, detail = "failed")
            StartStepState.PENDING ->
                LoaderStep(repairStepLabel(step), if (!anyMoved && i == 0) StepState.Running else StepState.Pending)
        }
    }
}

internal const val REPAIR_WAIT_NOTE: String =
    "The hub answers once, when it is done. You can leave; the result lands in the conversation."

internal const val REPAIR_REPORTED_NOTE: String =
    "You can leave; the repair runs on and the result lands in the conversation."

const val REPAIR_WAIT_TAG = "session.repairWait"

/**
 * The Hex field for [wait] over [name]'s session. [reported] is a repair's
 * steps as the hub reports them, or null when it reports none. [onLeave]
 * hides it; the hub's call runs on.
 */
@Composable
fun RepairWaitScreen(
    wait: RepairWait,
    name: String?,
    reported: Map<String, StartStepState>? = null,
    onLeave: () -> Unit,
) {
    val ticking = wait == RepairWait.Repair && reported != null
    FullscreenLoader(
        wait = FullscreenWait.Repair,
        title = repairWaitTitle(wait),
        meta = name,
        onExit = onLeave,
        steps = if (ticking) repairReportedSteps(reported!!) else repairWaitSteps(wait),
        note = if (ticking) REPAIR_REPORTED_NOTE else REPAIR_WAIT_NOTE,
        modifier = Modifier.testTag(REPAIR_WAIT_TAG),
    )
}
