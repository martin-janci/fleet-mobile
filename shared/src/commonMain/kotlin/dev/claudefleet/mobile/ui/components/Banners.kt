package dev.claudefleet.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ConnectionStatus

/**
 * What the event stream is doing, when it is doing anything worth saying.
 *
 * Draws nothing while connected — a banner that is always there stops being
 * read. The design's rule is that the last snapshot stays on screen behind it
 * and actions are disabled rather than hidden, so this never covers the list.
 */
@Composable
fun ConnectionBanner(status: ConnectionStatus, modifier: Modifier = Modifier) {
    val text = connectionNotice(status) ?: return
    Notice(text, modifier)
}

/**
 * What [ConnectionBanner] says, or null while connected. Out here rather than
 * inside the composable because nothing in this repository can render one, and
 * the one claim worth a test — the reconnect reason reaches the screen — is a
 * claim about this string.
 */
internal fun connectionNotice(status: ConnectionStatus): String? = when (status) {
    is ConnectionStatus.Connected -> null
    // The reason is drawn. It used to be carried and discarded — the field
    // existed, was computed on every failure, and no composable read it, so
    // a person watching the app retry saw a counter going up and never what
    // it was retrying *from*. Absent on the first attempt, where there is no
    // previous failure to name.
    is ConnectionStatus.Reconnecting -> {
        val head =
            if (status.attempt <= 1) "connecting to the hub…"
            else "reconnecting to the hub (attempt ${status.attempt})…"
        status.reason?.let { "$head $it" } ?: head
    }
    is ConnectionStatus.Offline -> status.reason
}

/** A failure a person can act on: the hub's own words, not a paraphrase. */
@Composable
fun ErrorBanner(message: String?, onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    if (message == null) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Review S4. `explain(HubError.Http)` is "the hub answered HTTP
            // $status: $body" with the body capped at 1 000 characters plus an
            // ellipsis — 1 040 measured — and a reverse proxy's error page is
            // exactly that shape. Unbounded, it pushed Dismiss off the row and
            // swallowed the list behind it. Three lines is enough to read what
            // went wrong; the rest was never legible on a phone anyway.
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
