package dev.claudefleet.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.AutofixFinding
import dev.claudefleet.mobile.model.relativeAgo
import dev.claudefleet.mobile.ui.components.DangerTextButton
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.DotWave
import dev.claudefleet.mobile.ui.kit.SheetAction
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.components.SectionLabel
import dev.claudefleet.mobile.ui.theme.Fleet

data class AutofixHandlers(
    val onClose: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onApply: (AutofixFinding) -> Unit = {},
    val onLater: (AutofixFinding) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * Problems & fixes (claude-fleet `autofix`): what fleet found broken, each
 * row its explanation and, behind Details, what fleet saw and what the fix
 * changes. Fix always asks first, listing those changes; Later hides a row
 * for a day. Below: the rows hidden with Later, and the fixes run in the
 * last day, each with what ran.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutofixSheet(state: AutofixUiState, nowSeconds: Long, handlers: AutofixHandlers) {
    var confirm by remember { mutableStateOf<AutofixFinding?>(null) }
    val showLoader = rememberLoaderVisible(state.loading || state.busy != null)
    BottomSheet(
        title = "Problems & fixes",
        meta = "What fleet found broken · safe fixes run by themselves",
        onDismiss = handlers.onClose,
        cancelLabel = "Close",
        primary = SheetAction(label = if (state.busy == "scan") "Checking…" else "Check now", enabled = state.busy == null, onClick = handlers.onScan),
    ) {
        val o = Fleet.colors
        Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            if (showLoader) DotWave()
            state.notice?.let {
                Text(it, color = o.fg, fontSize = 14.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.loaded && state.problems.isEmpty()) {
                Text(noOpenProblemsLine(state), color = o.fgMuted, fontSize = 14.sp)
            }
            state.problems.forEachIndexed { i, f ->
                FindingRow(f, nowSeconds, state, onFix = { confirm = f }, onLater = { handlers.onLater(f) })
                if (i < state.problems.lastIndex) HorizontalDivider(color = o.border)
            }
            if (state.hidden.isNotEmpty()) {
                Fold("Hidden until later", state.hidden.size) {
                    for (f in state.hidden) FindingRow(f, nowSeconds, state, onFix = { confirm = f }, onLater = {})
                }
            }
            if (state.fixed.isNotEmpty()) {
                Fold("Fixed in the last day", state.fixed.size) {
                    for (f in state.fixed) FixedRow(f, nowSeconds)
                }
            }
            if (!state.canApply && state.findings.any { it.fix != null }) {
                Text("The desktop or a trusted device can apply these fixes.", color = o.fgMuted, fontSize = 13.sp)
            }
        }
    }
    confirm?.let { f ->
        val fix = f.fix ?: return@let
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(fix.summary.ifBlank { "Fix ${findingTitle(f)}?" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (fix.changes.isNotEmpty()) {
                        Text("This changes:")
                        for (c in fix.changes) Text("• $c", fontSize = 14.sp)
                    }
                    if (!fix.reversible) Text("It cannot be undone.", fontWeight = FontWeight.Medium)
                }
            },
            confirmButton = {
                val onFix: () -> Unit = { confirm = null; handlers.onApply(f) }
                if (fix.reversible) TextButton(onClick = onFix) { Text("Fix") } else DangerTextButton(onClick = onFix) { Text("Fix") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Fold(label: String, count: Int, content: @Composable () -> Unit) {
    val o = Fleet.colors
    var open by rememberSaveable(label) { mutableStateOf(false) }
    HorizontalDivider(color = o.border)
    TextButton(onClick = { open = !open }) {
        Text((if (open) "▾ " else "▸ ") + "$label · $count", color = o.fg2, fontSize = 14.sp)
    }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FindingRow(f: AutofixFinding, nowSeconds: Long, state: AutofixUiState, onFix: () -> Unit, onLater: () -> Unit) {
    val o = Fleet.colors
    var details by rememberSaveable(f.id) { mutableStateOf(false) }
    val blocking = f.severity == "blocking"
    Column(modifier = Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(if (blocking) o.usageCrit else o.statusWaiting, CircleShape))
            Text(findingTitle(f), color = o.fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }
        Text(
            listOfNotNull(subjectWords(f.subject), relativeAgo(f.firstSeenAt, nowSeconds)?.let { "seen $it" }).joinToString(" · "),
            color = o.fgMuted,
            fontSize = 13.sp,
        )
        f.explanation?.takeIf { it.isNotBlank() }?.let { Text(it, color = o.fg2, fontSize = 14.sp) }
        findingStateWords(f)?.let {
            Text(it, color = if (f.failed) o.usageWarn else o.fgMuted, fontSize = 13.sp)
        }
        autoWords(f, nowSeconds, canApply = state.canApply)?.let { Text(it, color = o.fgMuted, fontSize = 13.sp) }
        if (details) {
            if (f.evidence.isNotEmpty()) {
                Label("What fleet saw")
                for (e in f.evidence) Text("• $e", color = o.fg2, fontSize = 13.sp)
            }
            f.fix?.let { fix ->
                Label("What the fix does" + if (fix.reversible) "" else " (cannot be undone)")
                for (c in fix.changes) Text("• $c", color = o.fg2, fontSize = 13.sp)
            }
        }
        // A sign-in finding has no Fix button: what to do is shown up front,
        // not behind Details (review w09-8).
        if (f.fix == null && f.action?.action == "login" && f.action.host != null) {
            Text("Log in on ${f.action.host} from the desktop: fleet cannot do it for you.", color = o.fg2, fontSize = 13.sp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val enabled = state.busy == null
            if (f.fix != null && state.canApply) {
                TextButton(onClick = onFix, enabled = enabled && !fixRunning(f, nowSeconds)) {
                    Text(if (state.busy == "${f.id}:apply") "Fixing…" else fixLabel(f), color = o.accent)
                }
            }
            if (state.canApply && !f.dismissed) {
                TextButton(onClick = onLater, enabled = enabled) { Text("Later", color = o.accent) }
            }
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Details", color = o.accent) }
        }
    }
}

@Composable
private fun FixedRow(f: AutofixFinding, nowSeconds: Long) {
    val o = Fleet.colors
    Column(modifier = Modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(findingTitle(f), color = o.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            listOfNotNull(fixedLine(f), subjectWords(f.subject), relativeAgo(f.lastAttemptAt ?: f.lastSeenAt, nowSeconds)).joinToString(" · "),
            color = o.fgMuted,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun Label(text: String) = SectionLabel(text, Modifier.padding(top = 4.dp), letterSpacing = 0.5.sp)
