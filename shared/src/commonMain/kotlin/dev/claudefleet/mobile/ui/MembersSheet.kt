package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.OrgMemberRow
import dev.claudefleet.mobile.ui.components.DangerTextButton
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.DotWave
import dev.claudefleet.mobile.ui.kit.SheetOption
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.theme.Fleet

data class MembersHandlers(
    val onClose: () -> Unit = {},
    val onSetRole: (OrgMemberRow, String) -> Unit = { _, _ -> },
    val onRemove: (OrgMemberRow) -> Unit = {},
    val onConfirmRemove: (KeepShares) -> Unit = {},
    val onCancelRemove: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * An org's members on the New layout (redesign 11.10, member actions on
 * Company): each person's role, devices and when they joined; an admin of
 * the org changes a role or removes them, and Remove asks what happens to
 * what was shared with them, in the desktop's words.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersSheet(state: MembersUiState, nowSeconds: Long, handlers: MembersHandlers) {
    val showLoader = rememberLoaderVisible(state.loading || state.busy != null)
    BottomSheet(
        title = "Members",
        meta = state.orgName + if (state.canAct) " · you administer it" else " · read only",
        onDismiss = handlers.onClose,
        cancelLabel = "Close",
    ) {
        val o = Fleet.colors
        Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            if (showLoader) DotWave()
            state.notice?.let {
                Text(it, color = o.fg, fontSize = 14.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.members.isEmpty() && !state.loading) Text("Nobody is in it yet.", color = o.fgMuted, fontSize = 14.sp)
            state.members.forEachIndexed { i, m ->
                MemberRow(m, nowSeconds, state, handlers)
                if (i < state.members.lastIndex) HorizontalDivider(color = o.border)
            }
            Text("Adding someone pairs a device, so it is done on the desktop: Settings → Organisations.", color = o.fgMuted, fontSize = 13.sp)
        }
    }
    state.removing?.let { ask -> RemoveDialog(ask, state.orgName, handlers) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberRow(m: OrgMemberRow, nowSeconds: Long, state: MembersUiState, handlers: MembersHandlers) {
    val o = Fleet.colors
    Column(modifier = Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(m.label, color = o.fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(memberLine(m, nowSeconds), color = o.fg2, fontSize = 13.sp)
        if (state.canAct && !m.owner) {
            val enabled = state.busy == null
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (role in MembersViewModel.ROLES.filter { it != m.role }) {
                    TextButton(onClick = { handlers.onSetRole(m, role) }, enabled = enabled) {
                        Text("Make ${roleWord(role).lowercase()}", color = o.accent)
                    }
                }
                DangerTextButton(onClick = { handlers.onRemove(m) }, enabled = enabled) {
                    Text(if (state.busy == m.personId) "Working…" else "Remove")
                }
            }
        }
    }
}

@Composable
private fun RemoveDialog(ask: RemoveAsk, org: String, handlers: MembersHandlers) {
    val choices = keepChoices(ask.grants)
    var picked by remember(ask) { mutableStateOf(KeepShares.Revoke) }
    AlertDialog(
        onDismissRequest = handlers.onCancelRemove,
        title = { Text("Remove ${ask.member.label} from $org?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(removeQuestion(org, ask.grants) ?: "Nothing of $org is shared with them.")
                if (choices.isNotEmpty()) {
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in choices) SheetOption(title = keepLabel(c, ask.grants), selected = picked == c, onSelect = { picked = c })
                    }
                }
            }
        },
        confirmButton = { DangerTextButton(onClick = { handlers.onConfirmRemove(picked) }) { Text("Remove ${ask.member.label}") } },
        dismissButton = { TextButton(onClick = handlers.onCancelRemove) { Text("Cancel") } },
    )
}
