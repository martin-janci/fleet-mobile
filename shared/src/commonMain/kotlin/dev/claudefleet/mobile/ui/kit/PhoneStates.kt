package dev.claudefleet.mobile.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/**
 * Where the phone stands with its hub, in the four states the MobileStates
 * board draws. A narrower question than [ConnectionStatus]: it folds in how
 * long the hub has been gone, because after `hub-lost-after` the app stops
 * saying "reconnecting" and says the hub is not answering.
 */
sealed interface PhoneConnection {
    /** Connected: nothing to say, so nothing is drawn. */
    data object Live : PhoneConnection

    /** Trying again, and not for long yet: the Gravity well. */
    data class Reconnecting(val attempt: Int) : PhoneConnection

    /** The hub is not answering: Signal lost, the last known rows and Retry. [reason] is what the last attempt said. */
    data class Offline(val reason: String?) : PhoneConnection

    /** The hub answered and this build will not use it: words only, no loader and no Retry. */
    data class Refused(val reason: String) : PhoneConnection
}

/**
 * The state to draw for [status], when the hub has been gone for
 * `hub-lost-after` or more if [hubLost]. A reconnect that runs past that
 * reads as Offline: the board's rule is that after 6 s the app says so
 * instead of spinning.
 */
fun phoneConnection(status: ConnectionStatus, hubLost: Boolean): PhoneConnection = when (status) {
    is ConnectionStatus.Connected -> PhoneConnection.Live
    is ConnectionStatus.Reconnecting ->
        if (hubLost) PhoneConnection.Offline(status.reason) else PhoneConnection.Reconnecting(status.attempt)
    is ConnectionStatus.Offline -> PhoneConnection.Offline(status.reason)
    is ConnectionStatus.Refused -> PhoneConnection.Refused(status.reason)
}

/** [phoneConnection] with the clock: how long the hub has been gone is counted in frames from the moment it went. */
@Composable
fun rememberPhoneConnection(status: ConnectionStatus): PhoneConnection {
    val down = status !is ConnectionStatus.Connected
    var lost by remember { mutableStateOf(false) }
    LaunchedEffect(down) {
        lost = false
        if (!down) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (!lost) {
            val now = withFrameMillis { it }
            lost = now - start >= OrbitMotion.hubLostAfterMs
        }
    }
    return phoneConnection(status, hubLost = down && lost)
}

/** What the offline banner says under its title. [asOf] is the time of the last answer ("14:52"), when known. */
fun offlineDetail(asOf: String?): String =
    if (asOf != null) "Showing what it said at $asOf. Answers wait until it is back."
    else "Showing what it last said. Answers wait until it is back."

/**
 * The banner at the top of a list while the hub is not live (MobileStates:
 * Hub offline). Once, at the top; the rows under it keep their age and say
 * "was", never a live spinner.
 *
 * - Offline: Signal lost, "The hub is not answering", what the last attempt
 *   said, and Retry.
 * - Reconnecting: the Gravity well, after `loader-delay`.
 * - Refused: the reason in words; there is nothing to retry.
 */
@Composable
fun HubBanner(
    connection: PhoneConnection,
    modifier: Modifier = Modifier,
    asOf: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val o = Fleet.colors
    val shown = when (connection) {
        PhoneConnection.Live -> false
        is PhoneConnection.Reconnecting -> rememberLoaderVisible(true)
        else -> true
    }
    if (!shown) return
    val shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp)
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = gutter, vertical = 8.dp)
            .background(o.waitingFaint, shape)
            .border(1.dp, o.waitingLine, shape)
            .heightIn(min = OrbitTokens.spacing("touch-min").dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (connection) {
            is PhoneConnection.Offline -> OrbitMarkLoader(MarkMotion.SignalLost, size = 34.dp)
            is PhoneConnection.Reconnecting -> OrbitMarkLoader(MarkMotion.GravityWell, size = 34.dp)
            else -> Unit
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val (title, lines) = when (connection) {
                is PhoneConnection.Offline -> "The hub is not answering" to listOfNotNull(offlineDetail(asOf), connection.reason)
                is PhoneConnection.Reconnecting ->
                    "Reconnecting to the hub" to listOf(if (connection.attempt <= 1) "Rows keep what they last said." else "Attempt ${connection.attempt}. Rows keep what they last said.")
                is PhoneConnection.Refused -> "This hub cannot be used" to listOf(connection.reason)
                PhoneConnection.Live -> "" to emptyList()
            }
            Text(title, color = o.fg, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            for (line in lines) Text(line, color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (connection is PhoneConnection.Offline && onRetry != null) {
            TextButton(onClick = onRetry) { Text("Retry", color = o.accent, fontSize = 15.sp) }
        }
    }
}

/**
 * The body of a list that has nothing to show while the network comes back
 * (MobileStates: Reconnecting): the Gravity well, what it is doing, and the
 * last known list one tap away. Like every loader, nothing until
 * `loader-delay`.
 */
@Composable
fun ReconnectingPanel(
    hub: String,
    modifier: Modifier = Modifier,
    onShowLastKnown: (() -> Unit)? = null,
) {
    if (!rememberLoaderVisible(true)) return
    val o = Fleet.colors
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        OrbitMarkLoader(MarkMotion.GravityWell, size = 88.dp)
        Text("Back on the network", color = o.fg, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Finding $hub again. Your rows refresh the moment it answers.",
            color = o.fgMuted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
        if (onShowLastKnown != null) {
            QuietButton("Show the last known list", onShowLastKnown)
        }
    }
}

/**
 * A conversation that is still loading (MobileStates: Loading a session):
 * nothing for `loader-delay`, then a strip with the Dot wave and skeleton
 * turns. Quick replies and Stop belong to the screen and stay hidden until
 * turns arrive.
 */
@Composable
fun ConversationLoading(waiting: Boolean, modifier: Modifier = Modifier) {
    if (!rememberLoaderVisible(waiting)) return
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(o.bgRaise).padding(horizontal = gutter, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DotWave(columns = 5, rows = 2, dot = 3.dp, gap = 3.dp)
            Text("Loading the conversation", color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SkeletonTurn(fromPerson = true, widths = listOf(1f, 0.6f))
            SkeletonTurn(fromPerson = false, widths = listOf(1f, 0.92f, 0.75f, 0.4f))
            SkeletonTurn(fromPerson = true, widths = listOf(0.8f))
            SkeletonTurn(fromPerson = false, widths = listOf(0.95f, 0.6f))
        }
    }
}

/** One skeleton turn: the person's on the right at 62 % width, the agent's full width on the left. */
@Composable
private fun SkeletonTurn(fromPerson: Boolean, widths: List<Float>) {
    val bar = Fleet.colors.controlBorder
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (fromPerson) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier.fillMaxWidth(if (fromPerson) 0.62f else 1f),
            horizontalAlignment = if (fromPerson) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            for (w in widths) {
                Box(Modifier.fillMaxWidth(w).height(12.dp).background(bar, RoundedCornerShape(6.dp)))
            }
        }
    }
}

/** What the pull-to-refresh label says at [progress] (1 = far enough to release) and while [refreshing]. */
fun pullLabel(progress: Float, refreshing: Boolean): String = when {
    refreshing -> "Refreshing"
    progress >= 1f -> "Release to refresh"
    else -> "Pull to refresh"
}

/**
 * The pull-to-refresh indicator (MobileStates: Pull to refresh): the Orbit
 * mark draws its ring as the list is pulled, then Chases while the refresh
 * runs, once that has taken `loader-delay`. For Sessions, Inbox, Work and
 * Hosts alike.
 */
@Composable
fun PullOrbit(progress: Float, refreshing: Boolean, modifier: Modifier = Modifier) {
    val o = Fleet.colors
    val chasing = rememberLoaderVisible(refreshing)
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (chasing) {
            OrbitMarkLoader(MarkMotion.Chase, size = 30.dp)
        } else {
            OrbitMarkLoader(MarkMotion.Still, size = 30.dp, pull = if (refreshing) 1f else progress)
        }
        Text(pullLabel(progress, refreshing), color = o.fgMuted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

/** The quiet action under a state: text in `fg-2`, touch height. */
@Composable
internal fun QuietButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp),
    ) { Text(label, color = Fleet.colors.fg2, fontSize = 15.sp) }
}
