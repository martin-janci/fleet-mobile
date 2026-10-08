package dev.claudefleet.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.DebugDevice
import dev.claudefleet.mobile.ui.components.DangerTextButton
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.DotWave
import dev.claudefleet.mobile.ui.kit.SheetAction
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.theme.Fleet

data class DebugDevicesHandlers(
    val onClose: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onPress: (DebugDevice, DeviceMove) -> Unit = { _, _ -> },
    val onCloseLogs: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * Debug devices on the New bar (redesign 11.10, DebugDevices): the test
 * phones, emulators and simulators on the fleet's hosts, not the people's own
 * devices. Each row says its state, where it is and who holds it, with the
 * presses it allows; Shut down asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugDevicesSheet(state: DebugDevicesUiState, nowSeconds: Long, handlers: DebugDevicesHandlers) {
    var confirmShutdown by remember { mutableStateOf<DebugDevice?>(null) }
    val showLoader = rememberLoaderVisible(state.loading || state.busy != null)
    BottomSheet(
        title = "Debug devices",
        meta = "Test phones on your hosts · a session claims one while it tests",
        onDismiss = handlers.onClose,
        cancelLabel = "Close",
        primary = if (state.canAct) {
            SheetAction(label = if (state.busy == "scan") "Scanning…" else "Scan all hosts", enabled = state.busy == null, onClick = handlers.onScan)
        } else {
            null
        },
    ) {
        val o = Fleet.colors
        Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            if (showLoader) DotWave()
            state.notice?.let {
                Text(it, color = o.fg, fontSize = 14.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.devices.isEmpty() && !state.loading) {
                Text("No test phones found. Plug one into a host or start an emulator, then scan.", color = o.fgMuted, fontSize = 14.sp)
            }
            state.devices.forEachIndexed { i, d ->
                DeviceRow(d, nowSeconds, state, onPress = { move ->
                    if (move == DeviceMove.Shutdown) confirmShutdown = d else handlers.onPress(d, move)
                })
                if (i < state.devices.lastIndex) HorizontalDivider(color = o.border)
            }
            val failed = state.hosts.filter { !it.error.isNullOrBlank() }
            if (failed.isNotEmpty()) {
                HorizontalDivider(color = o.border)
                for (h in failed) Text("${h.host}: ${h.error}", color = o.fgMuted, fontSize = 13.sp)
            }
            Text("Install, run and screenshots are on the desktop: Settings, Organisations, Debug devices.", color = o.fgMuted, fontSize = 13.sp)
        }
    }
    confirmShutdown?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmShutdown = null },
            title = { Text("Shut down ${d.title}?") },
            text = {
                Text(
                    if (d.claimedBy != null) {
                        "It is held by ${holderName(d.claimedBy)}; what runs on it stops. Boot it again from here."
                    } else {
                        "What runs on it stops. Boot it again from here."
                    },
                )
            },
            confirmButton = { DangerTextButton(onClick = { confirmShutdown = null; handlers.onPress(d, DeviceMove.Shutdown) }) { Text("Shut down") } },
            dismissButton = { TextButton(onClick = { confirmShutdown = null }) { Text("Cancel") } },
        )
    }
    state.logs?.let { logs ->
        AlertDialog(
            onDismissRequest = handlers.onCloseLogs,
            title = { Text("${logs.title} · logs") },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (logs.output.truncated) Text("The last lines only; the hub cut the rest.", color = Fleet.colors.fgMuted, fontSize = 12.sp)
                    SelectionContainer {
                        Text(
                            logs.output.output.ifBlank { "Nothing logged." },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            softWrap = false,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = handlers.onCloseLogs) { Text("Close") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceRow(d: DebugDevice, nowSeconds: Long, state: DebugDevicesUiState, onPress: (DeviceMove) -> Unit) {
    val o = Fleet.colors
    Column(modifier = Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(d.title, color = o.fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            deviceState(d) + " · " + deviceLine(d, nowSeconds),
            color = if (d.ready || d.claimedBy != null) o.fg2 else o.fgMuted,
            fontSize = 13.sp,
        )
        val moves = if (state.canAct) deviceMoves(d) else emptyList()
        if (moves.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (move in moves) {
                    val enabled = state.busy == null
                    if (move == DeviceMove.Shutdown) {
                        DangerTextButton(onClick = { onPress(move) }, enabled = enabled) { Text(move.label) }
                    } else {
                        TextButton(onClick = { onPress(move) }, enabled = enabled) {
                            Text(if (state.busy == "${d.id}:${move.name}") "${move.label}…" else move.label, color = o.accent)
                        }
                    }
                }
            }
        }
    }
}
