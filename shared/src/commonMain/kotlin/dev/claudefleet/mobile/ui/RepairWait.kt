package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.claudefleet.mobile.ui.kit.FullscreenLoader
import dev.claudefleet.mobile.ui.kit.FullscreenWait
import dev.claudefleet.mobile.ui.kit.LoaderStep
import dev.claudefleet.mobile.ui.kit.StepState

/*
 * The Hex field over a session while its workspace is repaired or the
 * session recreated (MobileFullscreenLoaders). Both are one hub call that
 * answers at the end, so the checklist names what the hub goes through in
 * order and ticks nothing on the way: the first step is the one it starts
 * on, the rest wait, and the loader leaves when the answer lands (in the
 * conversation, as before). Leaving early is safe: the call runs on.
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

internal const val REPAIR_WAIT_NOTE: String =
    "The hub answers once, when it is done. You can leave; the result lands in the conversation."

const val REPAIR_WAIT_TAG = "session.repairWait"

/** The Hex field for [wait] over [name]'s session. [onLeave] hides it; the hub's call runs on. */
@Composable
fun RepairWaitScreen(wait: RepairWait, name: String?, onLeave: () -> Unit) {
    FullscreenLoader(
        wait = FullscreenWait.Repair,
        title = repairWaitTitle(wait),
        meta = name,
        onExit = onLeave,
        steps = repairWaitSteps(wait),
        note = REPAIR_WAIT_NOTE,
        modifier = Modifier.testTag(REPAIR_WAIT_TAG),
    )
}
