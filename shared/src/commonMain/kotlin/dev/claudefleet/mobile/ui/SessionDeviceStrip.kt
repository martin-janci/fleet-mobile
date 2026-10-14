package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.DebugDevice
import dev.claudefleet.mobile.ui.components.Base64Image
import dev.claudefleet.mobile.ui.components.DangerTextButton
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons

/** What the strip's presses call; the defaults do nothing (a preview, a test, a hub without devices). */
data class SessionDevicesHandlers(
    val onScreenshot: (DebugDevice) -> Unit = {},
    val onRelease: (DebugDevice) -> Unit = {},
    val onCloseShot: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

internal const val SESSION_DEVICE_STRIP = "session-device-strip"

/**
 * The test phones this session holds or waits for, under the session's tabs:
 * one line each with its state and time left or place in line, Screenshot
 * for a ready one and Release (asked first) for one it holds. Draws nothing
 * when the session has none.
 */
@Composable
fun SessionDeviceStrip(state: SessionDevicesUiState, nowSeconds: Long, handlers: SessionDevicesHandlers) {
    val holder = state.holder
    if (holder != null && state.devices.isNotEmpty()) {
        var confirmRelease by remember { mutableStateOf<DebugDevice?>(null) }
        val o = Fleet.colors
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).testTag(SESSION_DEVICE_STRIP)) {
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            for (d in state.devices) {
                val held = d.claimedBy == holder
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(FleetIcons.Device, contentDescription = null, tint = o.fgMuted, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(d.title, color = o.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stripLine(d, holder, nowSeconds), color = o.fgMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (state.canAct && d.ready && held) {
                        val busy = state.busy == "${d.id}:${DeviceMove.Screenshot.name}"
                        TextButton(onClick = { handlers.onScreenshot(d) }, enabled = state.busy == null) {
                            Text(if (busy) "Screenshot…" else "Screenshot", color = o.accent, fontSize = 13.sp)
                        }
                    }
                    if (state.canAct && held) {
                        DangerTextButton(onClick = { confirmRelease = d }, enabled = state.busy == null) { Text("Release", fontSize = 13.sp) }
                    }
                }
            }
            HorizontalDivider(color = o.border)
        }
        confirmRelease?.let { d ->
            AlertDialog(
                onDismissRequest = { confirmRelease = null },
                title = { Text("Release ${d.title}?") },
                text = { Text("The session loses its claim; whatever it was testing there may fail. The next in line gets the device.") },
                confirmButton = { DangerTextButton(onClick = { confirmRelease = null; handlers.onRelease(d) }) { Text("Release") } },
                dismissButton = { TextButton(onClick = { confirmRelease = null }) { Text("Cancel") } },
            )
        }
    }
    state.shot?.let { ShotDialog(it, handlers.onCloseShot) }
}

/** A screenshot over the screen, with the hub's line under it; a tap on the image shows it whole. */
@Composable
internal fun ShotDialog(shot: DeviceShot, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(shot.title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Base64Image(shot.shot.data, description = "Screenshot of ${shot.title}", maxHeight = 440.dp)
                if (shot.shot.caption.isNotBlank()) Text(shot.shot.caption, color = Fleet.colors.fgMuted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
