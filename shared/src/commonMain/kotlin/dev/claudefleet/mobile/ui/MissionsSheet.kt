package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.MissionCard
import dev.claudefleet.mobile.model.MissionDetail
import dev.claudefleet.mobile.model.MissionStep
import dev.claudefleet.mobile.model.autonomyLabel
import dev.claudefleet.mobile.model.dollars
import dev.claudefleet.mobile.model.isQuestion
import dev.claudefleet.mobile.model.key
import dev.claudefleet.mobile.model.line
import dev.claudefleet.mobile.model.options
import dev.claudefleet.mobile.model.pauseMove
import dev.claudefleet.mobile.model.summary
import dev.claudefleet.mobile.ui.components.DangerTextButton
import dev.claudefleet.mobile.ui.components.ErrorBanner

data class MissionsHandlers(
    val onClose: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSelect: (Long) -> Unit = {},
    val onBack: () -> Unit = {},
    /** One step; null takes every next step. */
    val onStart: (MissionStep?) -> Unit = {},
    val onDecide: (MissionCard, Boolean, String?) -> Unit = { _, _, _ -> },
    val onTogglePause: () -> Unit = {},
    val onPauseAll: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * Missions as a sheet: the list with Pause all, and one mission's next steps
 * (Go), its cards (Apply / Dismiss, a question answered in words) and the
 * autonomy that applies, with Pause or Resume. Pause all asks first.
 * With [orbit] (the New layout) one mission is drawn by [OrbitMissionDetail].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionsSheet(state: MissionsUiState, handlers: MissionsHandlers, orbit: Boolean = false) {
    var confirmPauseAll by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        val detail = state.detail
        // The New layout draws one mission as the MobileControl board's panel.
        if (orbit && detail != null) {
            OrbitMissionDetail(detail, state, handlers)
            return@ModalBottomSheet
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (detail != null) TextButton(onClick = handlers.onBack) { Text("‹ Missions") }
                Text(
                    detail?.mission?.name ?: "Missions",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { heading() },
                )
                TextButton(onClick = handlers.onRefresh, enabled = !state.loading) { Text("Refresh") }
            }
            if (state.loading || state.busy != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
            }
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            Outcome(state)
            if (detail == null) MissionList(state, handlers) { confirmPauseAll = true } else MissionBody(detail, state, handlers)
        }
    }
    if (confirmPauseAll) {
        AlertDialog(
            onDismissRequest = { confirmPauseAll = false },
            title = { Text("Pause all missions?") },
            text = { Text("Every running mission you may change stops taking steps, and its grant ends. Runs already going keep going.") },
            confirmButton = { DangerTextButton(onClick = { confirmPauseAll = false; handlers.onPauseAll() }) { Text("Pause all") } },
            dismissButton = { TextButton(onClick = { confirmPauseAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun Outcome(state: MissionsUiState) {
    val text = state.results?.let { results ->
        if (results.isEmpty()) {
            "Nothing to take now."
        } else {
            results.joinToString("\n") { r -> (if (r.ok) "✓ " else "✗ ") + r.step.line() + if (r.detail.isNotBlank()) " — ${r.detail}" else "" }
        }
    } ?: state.notice ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun MissionList(state: MissionsUiState, handlers: MissionsHandlers, onPauseAll: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            if (state.missions.isEmpty() && !state.loading) {
                item { Text("No missions. Start one on the desktop.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp)) }
            }
            items(state.missions, key = { "mission:${it.id}" }) { m -> MissionRow(m) { handlers.onSelect(m.id) } }
            item { Spacer(Modifier.height(8.dp)) }
        }
        if (state.canPauseAll) {
            HorizontalDivider()
            DangerTextButton(
                onClick = onPauseAll,
                enabled = state.running > 0 && state.busy == null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            ) { Text(if (state.running > 0) "Pause all (${state.running} running)" else "Pause all") }
        }
    }
}

@Composable
private fun MissionRow(m: Mission, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp)) {
        Text(m.name.ifBlank { "Mission ${m.id}" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(m.summary(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MissionBody(detail: MissionDetail, state: MissionsUiState, handlers: MissionsHandlers) {
    val m = detail.mission
    val plan = detail.plan
    val titles = remember(detail.items) { detail.items.associate { it.id to (it.key ?: it.title) } }
    LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "head") {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (m.goal.isNotBlank()) Text(m.goal, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(m.summary(), detail.phase).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                plan?.let { p ->
                    val a = p.autonomy
                    Text(
                        "Autonomy ${a.effective}: ${autonomyLabel(a.effective)}" + if (a.why.isNotBlank()) " (${a.why})" else "",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    a.grant?.let { g ->
                        Text(
                            "Grant: level ${g.level} by ${g.grantedBy}" + (g.budgetMicros?.let { " · budget ${dollars(it)}" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text("Spent ${dollars(p.costMicros)}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (state.canPause && detail.mayChange && m.pauseMove() != null) {
            item(key = "pause") {
                TextButton(
                    onClick = handlers.onTogglePause,
                    enabled = state.busy == null,
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) { Text(if (m.state == "active") "Pause" else "Resume") }
            }
        }
        if (plan == null) {
            item(key = "noplan") {
                Text(
                    if (m.state == "draft") "A draft: start it on the desktop." else "This mission takes no more steps.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            return@LazyColumn
        }
        if (plan.cards.isNotEmpty()) {
            item(key = "cards-h") { SectionTitle("Waiting for you (${plan.cards.size})") }
            items(plan.cards, key = { "card:${it.id}" }) { c -> CardRow(c, state, detail.mayChange, handlers) }
        }
        item(key = "steps-h") { SectionTitle("Next steps") }
        if (plan.steps.isEmpty()) {
            item(key = "steps-none") {
                Text("Nothing is ready.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }
        items(plan.steps, key = { "step:${it.key()}" }) { s ->
            StepRow(s, s.itemId?.let { titles[it] }, state.canStart && detail.mayChange && state.busy == null, handlers)
        }
        if (state.canStart && detail.mayChange && plan.steps.count { it.kind != "ask" } > 1) {
            item(key = "start-all") {
                TextButton(
                    onClick = { handlers.onStart(null) },
                    enabled = state.busy == null,
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) { Text("Take every step") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
internal fun StepRow(s: MissionStep, item: String?, enabled: Boolean, handlers: MissionsHandlers) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(s.line(), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            item?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        // Go on an ask puts its question in the cards, where it is answered.
        TextButton(onClick = { handlers.onStart(s) }, enabled = enabled) { Text("Go") }
    }
}

@Composable
internal fun CardRow(c: MissionCard, state: MissionsUiState, mayChange: Boolean, handlers: MissionsHandlers) {
    var answer by remember(c.id) { mutableStateOf("") }
    val enabled = state.canDecide && mayChange && state.busy == null
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
        Text(c.line(), style = MaterialTheme.typography.bodyMedium)
        Text(
            listOfNotNull(c.source, c.note?.takeIf { it.isNotBlank() }).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (c.isQuestion && state.canDecide && mayChange) {
            val options = c.options
            if (options.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (o in options) TextButton(onClick = { answer = o }, enabled = enabled) { Text(o) }
                }
            }
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                label = { Text("Answer") },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.canDecide && mayChange) {
            Row {
                TextButton(
                    onClick = { handlers.onDecide(c, true, answer.takeIf { c.isQuestion }) },
                    enabled = enabled && (!c.isQuestion || answer.isNotBlank()),
                ) { Text(if (c.isQuestion) "Answer" else "Apply") }
                TextButton(onClick = { handlers.onDecide(c, false, null) }, enabled = enabled) { Text("Dismiss") }
            }
        }
    }
}
