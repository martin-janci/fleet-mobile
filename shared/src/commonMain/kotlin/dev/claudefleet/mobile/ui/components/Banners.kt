package dev.claudefleet.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.ui.Friendly
import dev.claudefleet.mobile.ui.theme.FleetIcons

/**
 * What the event stream is doing, when it is doing anything worth saying.
 *
 * Draws nothing while connected — a banner that is always there stops being
 * read. The design's rule is that the last snapshot stays on screen behind it
 * and actions are disabled rather than hidden, so this never covers the list.
 */
@Composable
fun ConnectionBanner(
    status: ConnectionStatus,
    /**
     * What the screen's own probe of the hub last said, where a screen makes
     * one — see [connectionNotice]. Null on the screens that do not.
     */
    hubReachable: Boolean? = null,
    modifier: Modifier = Modifier,
) {
    val text = connectionNotice(status, hubReachable) ?: return
    Notice(text, modifier)
}

/**
 * What [ConnectionBanner] says, or null while connected. Out here rather than
 * inside the composable because nothing in this repository can render one, and
 * the one claim worth a test — the reconnect reason reaches the screen — is a
 * claim about this string.
 *
 * [hubReachable] is the third connection state, and it was computed and never
 * drawn. A session screen probes the hub directly whenever the stream is down
 * (`fleet_health`), and when that probe answers `true` the app is in a state
 * the two-word vocabulary could not express: the hub is live and Send works,
 * only the update stream is missing. A banner saying "reconnecting" over a
 * working Send button reads as a contradiction, so it says what is actually
 * true instead.
 */
internal fun connectionNotice(status: ConnectionStatus, hubReachable: Boolean? = null): String? = when (status) {
    is ConnectionStatus.Connected -> null
    // The reason is drawn. It used to be carried and discarded — the field
    // existed, was computed on every failure, and no composable read it, so
    // a person watching the app retry saw a counter going up and never what
    // it was retrying *from*. Absent on the first attempt, where there is no
    // previous failure to name.
    is ConnectionStatus.Reconnecting -> if (hubReachable == true) {
        "live over the hub; the update stream is down (reconnecting…)"
    } else {
        val head =
            if (status.attempt <= 1) "connecting to the hub…"
            else "reconnecting to the hub (attempt ${status.attempt})…"
        status.reason?.let { "$head $it" } ?: head
    }
    is ConnectionStatus.Offline -> status.reason
    // The hub answered and this build will not talk to it; `reason` is
    // already the sentence that says which side is behind.
    is ConnectionStatus.Refused -> status.reason
}

/**
 * A failure a person can act on: plain language up front, the hub's own words
 * behind "Details".
 *
 * Draws nothing for a null [error] or one that is not actually an error — see
 * [Friendly.isError] — so a session that has simply gone silent
 * (`E_NO_TRANSCRIPT`) never raises this banner at all.
 */
@Composable
fun ErrorBanner(error: Friendly?, onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    if (error == null || !error.isError) return
    var showDetails by remember(error) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    FleetIcons.Warning,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(error.title, style = MaterialTheme.typography.titleSmall)
                    // `explain(HubError.Http)` is "the hub answered HTTP
                    // $status: $body" with the body capped at 1 000
                    // characters plus an ellipsis, and a reverse proxy's
                    // error page is exactly that shape — but that text now
                    // lives behind Details, not here. [Friendly.body] is the
                    // plain sentence, and three lines is still enough of it.
                    Text(
                        text = error.body,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (error.details != null) {
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) "Hide" else "Details")
                    }
                }
                if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            AnimatedVisibility(showDetails && error.details != null) {
                Text(
                    text = error.details.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(8.dp),
                )
            }
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
