package dev.claudefleet.mobile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.claudefleet.mobile.ui.decodeBase64Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Images that came off the wire — a device screenshot, a tool result's image —
 * drawn from their base64. Decoded off the UI thread, once per text; a text
 * that is not an image says so in a line instead of throwing.
 */

private sealed interface Decoded {
    data object Pending : Decoded

    data object Unreadable : Decoded

    class Ready(val bitmap: ImageBitmap) : Decoded
}

@Composable
private fun rememberDecoded(data: String): Decoded {
    val decoded by produceState<Decoded>(Decoded.Pending, data) {
        value = withContext(Dispatchers.Default) { decodeBase64Image(data) }?.let { Decoded.Ready(it) } ?: Decoded.Unreadable
    }
    return decoded
}

/**
 * [data] (base64 PNG or JPEG) at most [maxHeight] tall, keeping its shape; a
 * tap opens it on the whole screen ([ImageViewer]).
 */
@Composable
fun Base64Image(data: String, description: String, modifier: Modifier = Modifier, maxHeight: Dp = 280.dp) {
    var open by remember(data) { mutableStateOf(false) }
    when (val d = rememberDecoded(data)) {
        Decoded.Pending -> Box(modifier.heightIn(min = 48.dp))
        Decoded.Unreadable -> Text(
            "The image could not be read.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        is Decoded.Ready -> Image(
            bitmap = d.bitmap,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            modifier = modifier
                .heightIn(max = maxHeight)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Image, onClickLabel = "View full screen") { open = true },
        )
    }
    if (open) ImageViewer(data, description, onClose = { open = false })
}

/** [data] on the whole screen, on black; a tap or Back closes it. */
@Composable
fun ImageViewer(data: String, description: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(role = Role.Button, onClickLabel = "Close") { onClose() }
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (val d = rememberDecoded(data)) {
                is Decoded.Ready -> Image(
                    bitmap = d.bitmap,
                    contentDescription = description,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Decoded.Unreadable -> Text("The image could not be read.", color = Color.White)
                Decoded.Pending -> Unit
            }
        }
    }
}
