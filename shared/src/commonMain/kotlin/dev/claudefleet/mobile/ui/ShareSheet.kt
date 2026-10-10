package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.GrantLevel
import dev.claudefleet.mobile.model.SessionGrant
import dev.claudefleet.mobile.ui.components.DangerTextButton
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.DotWave
import dev.claudefleet.mobile.ui.kit.SheetAction
import dev.claudefleet.mobile.ui.kit.SheetOption
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.theme.Fleet

data class ShareHandlers(
    val onClose: () -> Unit = {},
    val onKind: (ShareKind) -> Unit = {},
    val onRecipient: (String) -> Unit = {},
    val onLevel: (String) -> Unit = {},
    val onShare: () -> Unit = {},
    val onNarrow: (SessionGrant) -> Unit = {},
    val onAskRevoke: (SessionGrant) -> Unit = {},
    val onRevoke: () -> Unit = {},
    val onCancelRevoke: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * Share a session from the phone (redesign 11.10): the desktop's Share
 * sheet in the phone's sheet. Who holds a grant, with Narrow to watch and a
 * two-step Revoke; a share to a person or the whole org at Watch, Answer
 * (from contract 13) or Steer (`drive` on the wire); and the two things a
 * sharer is deciding without being told, said out loud. The whole org asks
 * for no name when the phone knows which org that is ([ShareUiState.wholeOrg]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(state: ShareUiState, handlers: ShareHandlers) {
    val o = Fleet.colors
    val showLoader = rememberLoaderVisible(state.busy || (state.grants == null && state.error == null))
    BottomSheet(
        title = "Share ${state.sessionName}",
        meta = "Through Fleet, with a person or an org you are in",
        onDismiss = handlers.onClose,
        cancelLabel = "Close",
        primary = SheetAction(if (state.busy) "Sharing…" else "Share", enabled = state.canShare, onClick = handlers.onShare)
            .takeIf { state.owner && state.available },
    ) {
        Column(
            modifier = Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            if (showLoader) DotWave()
            state.notice?.let {
                Text(it, color = o.fg, fontSize = 14.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (!state.owner) {
                Text(
                    "Only the session's owner can share it. A share never passes on: whoever it is shared with cannot share it further.",
                    color = o.fgMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.testTag(SHARE_TAG + "not-owner"),
                )
                return@Column
            }
            Heading("Share with")
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SheetOption(title = "A person", selected = state.kind == ShareKind.Person, onSelect = { handlers.onKind(ShareKind.Person) }, enabled = !state.busy)
                SheetOption(
                    title = "The whole org",
                    sub = listOfNotNull(state.wholeOrg, "its members and admins in it now, never a later joiner or a viewer").joinToString(" · "),
                    selected = state.kind == ShareKind.Org,
                    onSelect = { handlers.onKind(ShareKind.Org) },
                    enabled = !state.busy,
                )
            }
            // MobileFormsWork: the whole org needs no field when the phone knows which org that is.
            if (!state.orgKnown) OutlinedTextField(
                value = state.recipient,
                onValueChange = handlers.onRecipient,
                singleLine = true,
                enabled = !state.busy,
                label = { Text(if (state.kind == ShareKind.Org) "Org" else "Person") },
                placeholder = { Text(if (state.kind == ShareKind.Org) "an org you are a member of" else "their name on this fleet") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag(SHARE_TAG + "recipient"),
            )
            Heading("They can")
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (level in state.levels) {
                    SheetOption(
                        title = GrantLevel.choice(level),
                        sub = GrantLevel.detail(level).takeIf { it.isNotEmpty() },
                        selected = state.level == level,
                        onSelect = { handlers.onLevel(level) },
                        enabled = !state.busy,
                        modifier = Modifier.testTag(SHARE_TAG + "level-" + level),
                    )
                }
            }
            HorizontalDivider(color = o.border)
            Heading("Shared with")
            val grants = state.grants
            when {
                grants == null -> Unit
                grants.isEmpty() -> Text(
                    "Not shared with anyone. Only you can see this session.",
                    color = o.fgMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.testTag(SHARE_TAG + "empty"),
                )
                else -> grants.forEachIndexed { i, g ->
                    GrantRow(g, state, handlers)
                    if (i < grants.lastIndex) HorizontalDivider(color = o.border)
                }
            }
            HorizontalDivider(color = o.border)
            Text(
                "They also get the history from before the share: the transcript, the work journal and the timeline come with the session.",
                color = o.fg2,
                fontSize = 13.sp,
            )
            Text(
                "Watch, answer and steer are enforced by Fleet, not by SSH. A share never gives a terminal, and anyone who " +
                    "independently has SSH to ${state.hostAlias.ifBlank { "its host" }} can still attach.",
                color = o.fg2,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = Fleet.colors.fgMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GrantRow(g: SessionGrant, state: ShareUiState, handlers: ShareHandlers) {
    val o = Fleet.colors
    val to = g.recipient
    val enabled = state.canAct && to != null
    Column(modifier = Modifier.padding(vertical = 4.dp).testTag(SHARE_TAG + "grant"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(g.label, color = o.fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(GrantLevel.word(g.level), color = o.fg2, fontSize = 13.sp)
        if (to == null) {
            Text("This hub did not name the recipient, so the phone cannot act on it. Use fleet-hub.", color = o.fgMuted, fontSize = 13.sp)
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.confirming == to) {
                Text("Revoke?", color = o.fg, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, end = 4.dp))
                DangerTextButton(onClick = handlers.onRevoke, enabled = enabled) { Text("Revoke") }
                TextButton(onClick = handlers.onCancelRevoke, enabled = !state.busy) { Text("Keep", color = o.accent) }
            } else {
                // Narrow, never widen: no tool raises a grant. Widening is a revoke and a fresh share.
                if (g.narrowable) {
                    TextButton(onClick = { handlers.onNarrow(g) }, enabled = enabled) { Text("Narrow to watch", color = o.accent) }
                }
                DangerTextButton(onClick = { handlers.onAskRevoke(g) }, enabled = enabled) { Text("Revoke") }
            }
        }
    }
}

const val SHARE_TAG = "share."
