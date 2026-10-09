package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetRepository
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.ui.kit.FullscreenLoader
import dev.claudefleet.mobile.ui.kit.FullscreenWait
import dev.claudefleet.mobile.ui.kit.LoaderProgress
import dev.claudefleet.mobile.ui.kit.LoaderStep
import dev.claudefleet.mobile.ui.kit.StepState

/**
 * Whether the first look at the fleet after pairing is over: the stream is
 * up, or the repository has stopped trying (refused, or offline for a reason
 * of its own). Either way the fleet's own screens say what happened from
 * here, so the loader gives way rather than hiding their banner. A hub that
 * has not answered [FLEET_CHECK_TRIES] tries is unreachable for now: the Hex
 * field stops there and the Sessions banner carries Retry (r13 P18).
 */
internal fun fleetCheckOver(status: ConnectionStatus): Boolean = when (status) {
    is ConnectionStatus.Connected, is ConnectionStatus.Refused -> true
    is ConnectionStatus.Offline -> status.reason != FleetRepository.NOT_STARTED
    is ConnectionStatus.Reconnecting -> status.attempt >= FLEET_CHECK_TRIES
}

/** How many failed tries the after-pairing check waits through before giving way. */
internal const val FLEET_CHECK_TRIES = 4

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

/**
 * How far the hub has got reading the fleet for the first time: of the hosts
 * it lists (hidden ones aside), how many it has probed once. A host never
 * probed has no `last_pinged_at`, and its sessions are not in the list yet.
 * Null when there is nothing left to read, so a hub that has run for a while
 * never shows the Galaxy at all.
 */
internal fun firstImportProgress(hosts: List<HostRow>): LoaderProgress? {
    val shown = hosts.filterNot { it.hidden }
    val read = shown.count { it.lastPingedAt != null }
    return if (read == shown.size) null else LoaderProgress(read, shown.size, "hosts read")
}

/** What the Galaxy says it is doing: the real count of hosts, and that it happens once. */
internal fun firstImportMeta(hosts: Int, sessions: Int): String {
    val found = if (hosts == 1) "1 host and is reading it" else "$hosts hosts and is reading them"
    val so = when (sessions) {
        0 -> ""
        1 -> " 1 session so far."
        else -> " $sessions sessions so far."
    }
    return "The hub found $found for the first time.$so This happens once."
}

/**
 * After the fleet check, the first import (redesign 14.12 and 14.19,
 * MobileFullscreenLoaders): the Galaxy while the hub still reads hosts it has
 * never probed, with the real count under it. It ends itself when
 * [firstImportProgress] has nothing left, and "Continue in the background"
 * ends it at once.
 */
@Composable
internal fun FirstImport(
    hosts: List<HostRow>,
    sessions: Int,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = firstImportProgress(hosts)
    LaunchedEffect(progress == null) { if (progress == null) onDone() }
    if (progress == null) return
    FullscreenLoader(
        wait = FullscreenWait.FirstImport,
        title = "Building your fleet",
        meta = firstImportMeta(progress.total, sessions),
        progress = progress,
        onExit = onDone,
        modifier = modifier,
    )
}
