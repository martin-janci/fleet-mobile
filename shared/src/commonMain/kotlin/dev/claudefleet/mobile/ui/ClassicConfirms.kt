package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/*
 * The Classic bar's way to the MCP calls waiting for a person's OK. The New
 * bar shows them on Control; Classic has no Control tab, so they used to be
 * reachable only from the desktop. Now the Sessions tab's badge counts them,
 * a banner on the list says so, and it opens [ConfirmsScreen] with the same
 * cards Control draws.
 */

const val CONFIRMS_BANNER_TAG = "classic.confirms.banner"

/** "1 call waits for your OK", "3 calls wait for your OK". */
fun confirmsWaitingLine(count: Int): String =
    if (count == 1) "1 call waits for your OK" else "$count calls wait for your OK"

/**
 * What the Classic Sessions badge counts: sessions that need you plus the
 * calls waiting for an OK — both are "something waits for you".
 */
fun classicSessionsBadge(attention: Int, confirms: Int): Int = attention + confirms

/** The strip above the Classic Sessions list while a call waits; a tap opens [ConfirmsScreen]. */
@Composable
fun ConfirmsBanner(count: Int, onOpen: () -> Unit) {
    if (count <= 0) return
    val o = Fleet.colors
    Surface(
        color = o.bgPane,
        border = BorderStroke(1.dp, o.statusWaiting),
        shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(CONFIRMS_BANNER_TAG)
            .clickable(role = Role.Button, onClick = onOpen),
    ) {
        Text(
            "${confirmsWaitingLine(count)} · Review",
            style = Fleet.type.textMd,
            color = o.statusWaiting,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** The Classic Confirms screen: back, any failed answer, then the cards — or that nothing waits. */
@Composable
fun ConfirmsScreen(
    state: ControlUiState,
    onBack: () -> Unit,
    onAnswer: (String, Boolean) -> Unit,
    onDismissError: () -> Unit,
) {
    val o = Fleet.colors
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Waiting for your OK",
            subtitle = state.confirms.size.takeIf { it > 0 }?.let(::confirmsWaitingLine),
            navigation = { IconButton(onClick = onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") } },
        )
        ErrorBanner(state.error, onDismiss = onDismissError)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (state.confirms.isEmpty()) {
                Text(
                    if (state.connected) "Nothing waits for your OK." else "Not connected to the hub. Waiting calls show once it answers.",
                    style = Fleet.type.textMd,
                    color = o.fg2,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                ConfirmCards(state, onAnswer)
            }
        }
    }
}
