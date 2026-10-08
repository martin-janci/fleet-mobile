package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetRepository
import dev.claudefleet.mobile.ui.kit.FullscreenLoader
import dev.claudefleet.mobile.ui.kit.FullscreenWait
import dev.claudefleet.mobile.ui.kit.LoaderStep
import dev.claudefleet.mobile.ui.kit.StepState

/**
 * Whether the first look at the fleet after pairing is over: the stream is
 * up, or the repository has stopped trying (refused, or offline for a reason
 * of its own). Either way the fleet's own screens say what happened from
 * here, so the loader gives way rather than hiding their banner.
 */
internal fun fleetCheckOver(status: ConnectionStatus): Boolean = when (status) {
    is ConnectionStatus.Connected, is ConnectionStatus.Refused -> true
    is ConnectionStatus.Offline -> status.reason != FleetRepository.NOT_STARTED
    is ConnectionStatus.Reconnecting -> false
}

/** The checklist under the Hex field: real steps, never "Loading…". */
internal fun fleetCheckSteps(status: ConnectionStatus, hub: String, clientName: String): List<LoaderStep> {
    val reach = when (status) {
        is ConnectionStatus.Connected -> LoaderStep("Reach $hub", StepState.Done)
        is ConnectionStatus.Reconnecting ->
            LoaderStep("Reach $hub", StepState.Running, detail = if (status.attempt > 1) "try ${status.attempt}" else null)
        else -> LoaderStep("Reach $hub", StepState.Running)
    }
    return listOf(
        LoaderStep(if (clientName.isBlank()) "Paired this phone" else "Paired as “$clientName”", StepState.Done),
        reach,
        LoaderStep("Read the hosts and sessions", if (status is ConnectionStatus.Connected) StepState.Done else StepState.Pending),
    )
}

/**
 * After pairing (redesign 14.11 and MobileFullscreenLoaders): the Hex field
 * while the phone's first connection to the fleet runs. It ends itself when
 * [fleetCheckOver] says so, and "Skip" ends it at once; the loader draws
 * nothing for the first `loader-delay`, so a quick hub never shows it.
 */
@Composable
internal fun FleetCheck(
    status: ConnectionStatus,
    hub: String,
    clientName: String,
    exitLabel: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(status) { if (fleetCheckOver(status)) onDone() }
    FullscreenLoader(
        wait = FullscreenWait.FleetCheck,
        title = "Checking your fleet",
        meta = hub,
        exitLabel = exitLabel,
        steps = fleetCheckSteps(status, hub, clientName),
        note = (status as? ConnectionStatus.Reconnecting)?.reason?.replaceFirstChar { it.uppercaseChar() },
        onExit = onDone,
        modifier = modifier,
    )
}
