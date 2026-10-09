package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.loginLabel
import dev.claudefleet.mobile.model.relativeWithin
import dev.claudefleet.mobile.model.usedText
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.SheetAction
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/**
 * What a session screen hands its paused-on-limit card and its Send later
 * sheet (redesign 4.10 and 14.14). The defaults do nothing: a preview, a test
 * that does not care, or a hub that serves neither.
 */
class SessionLaterHost(
    val onSendLater: (String) -> Unit = {},
    val onLoadQueued: () -> Unit = {},
    val onCancelQueued: (Long) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onProposeSwitch: () -> Unit = {},
    val onConfirmSwitch: () -> Unit = {},
    val onCancelSwitch: () -> Unit = {},
    val onWait: (Long) -> Unit = {},
    /** When the session's account limit resets (unix seconds), from `account_usage`; null when unknown. */
    val limitResetsAt: Long? = null,
    /** An account uuid's label from `list_accounts`; null when the hub named none. */
    val accountName: (String) -> String? = { null },
)

const val LIMIT_CARD_TAG = "session.limit"
const val LIMIT_SWITCH_TAG = "session.limit.switch"
const val LIMIT_CONFIRM_TAG = "session.limit.confirm"
const val LIMIT_WAIT_TAG = "session.limit.wait"
const val SEND_LATER_FIELD_TAG = "session.sendLater.field"

/** "Wait · resets in 2 h": the Wait button; null when the reading has no reset. */
internal fun waitLabel(resetsAt: Long?, nowSeconds: Long): String? =
    relativeWithin(resetsAt, nowSeconds)?.let { "Wait · resets in $it" }

/**
 * A "Paused · limit" session's two answers (claude-fleet 4.4 on the phone,
 * 4.10), as the desktop's `LimitActions` draws them: Switch account asks the
 * hub which login on the host has the most room on another account and shows
 * it; a second tap restarts the session under it, resuming its conversation.
 * Wait folds the buttons into "Waiting for the reset". Nothing moves without
 * the second tap, and neither button is pre-selected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LimitCard(state: SessionUiState, host: SessionLaterHost, modifier: Modifier = Modifier) {
    val o = Fleet.colors
    val target = state.switchTarget
    val waiting = state.waitingUntil
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag(LIMIT_CARD_TAG),
        color = o.bgRaise,
        contentColor = o.fg,
        shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp),
        border = BorderStroke(1.dp, o.border),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Paused · its account is at its usage limit",
                style = Fleet.type.textSm,
                color = o.fg,
            )
            state.limitNotice?.let {
                Text(it, style = Fleet.type.textXs, color = o.fgMuted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            val enabled = !state.busy && state.connected
            when {
                target != null -> {
                    Text(
                        "Resume under ${loginLabel(target, host.accountName)} · ${usedText(target)}?",
                        style = Fleet.type.textXs,
                        color = o.fgMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = host.onConfirmSwitch, enabled = enabled, modifier = Modifier.testTag(LIMIT_CONFIRM_TAG)) { Text("Switch") }
                        TextButton(onClick = host.onCancelSwitch, enabled = enabled) { Text("Cancel") }
                    }
                }
                waiting != null -> Text(
                    relativeWithin(waiting, state.nowSeconds)?.let { "Waiting for the reset · in $it" } ?: "Waiting for the reset",
                    style = Fleet.type.textXs,
                    color = o.fgMuted,
                )
                else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.canSwitchAccount) {
                        OutlinedButton(onClick = host.onProposeSwitch, enabled = enabled, modifier = Modifier.testTag(LIMIT_SWITCH_TAG)) {
                            Text("Switch account")
                        }
                    }
                    val resets = host.limitResetsAt
                    waitLabel(resets, state.nowSeconds)?.let { label ->
                        TextButton(onClick = { if (resets != null) host.onWait(resets) }, modifier = Modifier.testTag(LIMIT_WAIT_TAG)) { Text(label) }
                    }
                }
            }
        }
    }
}

/**
 * Send later (redesign 14.14): a prompt the hub keeps and types as a new turn
 * the next time the session is idle — never into a dialog, and whether or not
 * this phone is still running. claude-fleet's deferred prompts (5.10) hold it,
 * so "later" is the session's next idle moment, not a clock time. Lists what
 * is still waiting, each with Take back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SendLaterSheet(state: SessionUiState, host: SessionLaterHost, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { host.onLoadQueued() }
    val o = Fleet.colors
    BottomSheet(
        title = "Send later",
        meta = "It goes in as a new turn when the session is next idle; never into a question.",
        onDismiss = {
            host.onDismissNotice()
            onDismiss()
        },
        primary = SheetAction(
            label = if (text.isBlank()) "Write the prompt" else "Send when idle",
            enabled = text.isNotBlank() && state.canSendLater && !state.busy,
        ) {
            host.onSendLater(text)
            text = ""
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Prompt") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag(SEND_LATER_FIELD_TAG),
        )
        state.sendLaterNotice?.let {
            Text(it, style = Fleet.type.textSm, color = o.fg, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.queued.isNotEmpty()) {
            Text("Waiting · ${state.queued.size}", style = Fleet.type.textXs, color = o.fgMuted)
            for (q in state.queued) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(q.body, style = Fleet.type.textSm, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { host.onCancelQueued(q.id) }, enabled = state.connected) { Text("Take back") }
                }
            }
        }
    }
}
