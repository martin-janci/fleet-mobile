package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.relativeAgo
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.Page
import dev.claudefleet.mobile.model.PageItem
import dev.claudefleet.mobile.model.SettingDescriptor
import dev.claudefleet.mobile.model.SettingProposal
import dev.claudefleet.mobile.model.choiceSetOf
import dev.claudefleet.mobile.model.fromDisplay
import dev.claudefleet.mobile.model.withChoice
import dev.claudefleet.mobile.notify.parseQuietHours
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.claudefleet.mobile.model.holds
import dev.claudefleet.mobile.model.inWords
import dev.claudefleet.mobile.model.rangeText
import dev.claudefleet.mobile.model.toDisplay
import dev.claudefleet.mobile.model.unitWord
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.model.SettingWrite
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import dev.claudefleet.mobile.ui.kit.InlineLoading
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.components.ErrorBanner
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import dev.claudefleet.mobile.model.changedFrom
import dev.claudefleet.mobile.model.scopeWords
import dev.claudefleet.mobile.ui.theme.FleetIcons

/**
 * The fleet's settings, drawn from the hub's own page specs (claude-fleet
 * declarative pages P6) — the section of Settings under *Fleet settings*.
 *
 * With no page open: the pages the hub offers a phone, and one line on who
 * may change them. With one open: its sections, each field drawn from its
 * descriptor. Everything the screen does is a [FleetSettingsViewModel] call;
 * `FleetSettingsViewModelTest` holds the decisions, since nothing here renders
 * a screen in a test.
 */
@Composable
fun FleetSettingsSection(
    state: FleetSettingsUiState,
    clientName: String,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onSet: (String, String) -> Unit,
    onRefuse: (String, String) -> Unit,
    onDecide: (Long, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancelConfirm: () -> Unit,
    onHistory: (String) -> Unit = {},
    onCloseHistory: () -> Unit = {},
    /** Drawn above an open page's fields, by page id: Decisions (Jev) opens with who opted in (14.17). */
    pageHead: @Composable (String) -> Unit = {},
    /** Read the settings again after a failed read. */
    onRetry: () -> Unit = {},
    onDismissError: () -> Unit = {},
    /** The Save bar's rows (G1.5): typed and picked values stage; Reset puts the default back. */
    batch: FieldBatch? = null,
) {
    state.history?.let { (key, rows) ->
        SettingHistoryDialog(state.descriptors[key]?.label ?: key, rows, state.historyError, onCloseHistory, onRetry = { onHistory(key) })
    }
    InlineLoading(waiting = state.loading && !state.loaded, modifier = Modifier.padding(horizontal = 16.dp))
    ErrorBanner(state.loadError, onDismiss = onDismissError, onRetry = onRetry)
    val page = state.page
    if (page == null) {
        PageList(state, clientName, onOpen)
    } else {
        PageBody(state, page, onBack, onOpen, onSet, onRefuse, onDecide, onHistory, head = { pageHead(page.id) }, batch = batch)
    }
    state.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = onCancelConfirm,
            title = { Text(c.label) },
            text = { Text(c.message) },
            confirmButton = { TextButton(onClick = onConfirm) { Text("Change it") } },
            dismissButton = { TextButton(onClick = onCancelConfirm) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PageList(state: FleetSettingsUiState, clientName: String, onOpen: (String) -> Unit) {
    Text(
        text = "Fleet settings",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Text(
        text = if (state.canWrite) {
            "The hub’s settings: a change here is the hub’s, for the whole fleet."
        } else {
            "The hub’s settings, read only. To change them here, the hub’s operator " +
                "trusts this device: fleet-hub client trust ${clientName.ifBlank { "<name>" }}"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    for (p in state.pages) {
        val waiting = if (p.layout == "review_apply") state.proposals.size else 0
        Column(
            modifier = Modifier.fillMaxWidth().clickable { onOpen(p.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, style = MaterialTheme.typography.bodyLarge)
                if (waiting > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text("$waiting waiting", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            p.intro?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

@Composable
private fun PageBody(
    state: FleetSettingsUiState,
    page: Page,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onSet: (String, String) -> Unit,
    onRefuse: (String, String) -> Unit,
    onDecide: (Long, Boolean) -> Unit,
    onHistory: (String) -> Unit,
    head: @Composable () -> Unit = {},
    batch: FieldBatch? = null,
) {
    TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 8.dp)) { Text("‹ Fleet settings") }
    Text(page.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
    page.intro?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    head()
    if (page.layout == "review_apply") {
        Review(state, onDecide)
        return
    }
    val sections = page.allSections.filter { it.condition.holds(state.values) }
    for (section in sections) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        Text(
            section.title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        val grid = if (section.matrix) matrixColumns(state, section.items.map { PageItem.of(it) }) else null
        if (grid != null) {
            MatrixGrid(state, grid, onSet)
            continue
        }
        for (raw in section.items) {
            when (val item = PageItem.of(raw)) {
                is PageItem.Field -> {
                    val d = state.descriptors[item.key]
                    if (d != null && item.condition.holds(state.values)) {
                        FieldRow(state, d, item.hint, item.readOnly, onSet, onRefuse, onDecide, onHistory, batch)
                    }
                }
                is PageItem.Notice -> Text(
                    item.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                is PageItem.Link -> state.pages.firstOrNull { it.id == item.page }?.let { target ->
                    TextButton(onClick = { onOpen(target.id) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("${item.label ?: target.title} ›")
                    }
                }
                is PageItem.Elsewhere -> Unit
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

/**
 * A settings page's Save bar hooks for its rows (claude-fleet G1.5). With
 * them a typed or picked value stages ([onStage], [onType]) and the row says
 * where its value lives and what it was changed from, with Reset. Without
 * them (a guide) every change writes at once, as before.
 */
class FieldBatch(
    val onStage: (String, String) -> Unit,
    val onType: (String, String) -> Unit,
    val onReset: (String) -> Unit,
)

/**
 * The page's one Save bar (G1.5): "2 changes · Discard · Save" while
 * something typed is not saved, and the toggle just written with Undo. Drawn
 * by the settings screen under its scrolling fields, so it stays in view.
 */
@Composable
fun FleetSaveBar(
    state: FleetSettingsUiState,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onUndo: () -> Unit,
    onDismissUndo: () -> Unit,
) {
    val n = state.changeCount
    state.undo?.takeIf { n == 0 }?.let { u ->
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${u.label}: ${u.words}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onUndo) { Text("Undo") }
            TextButton(onClick = onDismissUndo) { Text("OK") }
        }
    }
    if (n == 0) return
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { contentDescription = "Unsaved changes" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(saveBarCount(n), style = MaterialTheme.typography.bodyMedium)
            if (state.stageProblems.isNotEmpty()) {
                Text("Fix the field marked below to save.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        TextButton(enabled = !state.saving, onClick = onDiscard) { Text("Discard") }
        Spacer(Modifier.width(4.dp))
        Button(enabled = state.canSave, onClick = onSave) { Text(if (state.saving) "Saving…" else "Save") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FieldRow(
    state: FleetSettingsUiState,
    d: SettingDescriptor,
    hint: String?,
    shownOnly: Boolean,
    onSet: (String, String) -> Unit,
    onRefuse: (String, String) -> Unit,
    onDecide: (Long, Boolean) -> Unit,
    onHistory: (String) -> Unit,
    batch: FieldBatch? = null,
) {
    val stored = state.values[d.key] ?: d.value
    // On a page with a Save bar the row shows what is staged; a guide shows the hub's value.
    val value = if (batch != null) state.shown(d.key) else stored
    val editable = state.editable(d.key) && !shownOnly
    val busy = d.key in state.busy || (batch != null && state.saving)
    val unsaved = batch != null && (d.key in state.staged || d.key in state.stageProblems)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val isSwitch = editable && d.kind.type == "bool"
        Row(
            // A switch row is the switch: its label is what a screen reader reads.
            modifier = if (isSwitch) {
                Modifier.toggleable(value = value == "true", enabled = !busy, role = Role.Switch) {
                    onSet(d.key, if (it) "true" else "false")
                }
            } else Modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(d.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (unsaved) {
                Text(
                    "not saved",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            if (isSwitch) {
                Switch(checked = value == "true", enabled = !busy, onCheckedChange = null)
            } else if (!editable || d.kind.type !in setOf("choice", "choice_set")) {
                if (!editable || d.kind.type !in TYPED) {
                    Text(d.inWords(value), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (editable && d.kind.type == "choice") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (o in d.offeredOptions()) {
                    FilterChip(
                        selected = value == o,
                        enabled = !busy,
                        onClick = { if (batch != null) batch.onStage(d.key, o) else onSet(d.key, o) },
                        label = { Text(d.optionLabel(o)) },
                    )
                }
            }
        }
        if (editable && d.kind.type == "choice_set") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val held = choiceSetOf(value)
                for (o in d.kind.options) {
                    FilterChip(
                        selected = o in held,
                        enabled = !busy,
                        onClick = { onSet(d.key, d.withChoice(value, o, o !in held)) },
                        label = { Text(d.optionLabel(o)) },
                    )
                }
            }
        }
        if (editable && d.kind.type in TYPED) {
            if (batch != null) {
                StagedValueField(d, stored, value, state.fieldEpoch[d.key] ?: 0, busy, batch.onType)
            } else {
                ValueField(d, value, busy, onSet, onRefuse)
            }
        }
        val range = d.rangeText()
        Text(
            buildString {
                append(d.help)
                if (range.isNotEmpty()) append(" ($range)")
                hint?.let { append(" "); append(it) }
                if (d.restart == "app") append(" Applies after the hub restarts.")
                d.ownedBy?.let { append(" Change it with $it.") }
                if (!d.readOnlyHere && state.canWrite && !editable) append(" Change it on a desktop.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (batch != null) {
            ScopeLine(d, stored, editable && !busy, batch.onReset, onHistory.takeIf { state.historyAvailable })
        }
        (state.fieldErrors[d.key] ?: state.stageProblems[d.key].takeIf { batch != null })?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        state.proposalFor(d.key)?.let { p -> Suggestion(state, d, p, onDecide) }
        if (batch == null && state.historyAvailable) {
            TextButton(onClick = { onHistory(d.key) }, contentPadding = PaddingValues(0.dp)) { Text("History") }
        }
    }
}

/**
 * Under a row's help (G1.5): a badge for where the value lives ("hub", or
 * the org whose own value overrides it), what it was changed from with
 * Reset, else "default"; History behind ⋮.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScopeLine(
    d: SettingDescriptor,
    stored: String,
    canReset: Boolean,
    onReset: (String) -> Unit,
    onHistory: ((String) -> Unit)?,
) {
    val scope = d.scopeWords()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                scope.badge,
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
                    .semantics { contentDescription = "Kept on the ${scope.badge}" },
            )
            if (scope.note.isNotEmpty()) Text(scope.note, style = MaterialTheme.typography.labelSmall, color = muted)
            val changed = d.changedFrom(stored)
            if (changed != null) {
                Text(changed, style = MaterialTheme.typography.labelSmall, color = muted)
                if (canReset) {
                    Text(
                        "Reset",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(role = Role.Button) { onReset(d.key) }.padding(horizontal = 2.dp),
                    )
                }
            } else if (scope.note.isEmpty()) {
                Text("default", style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
        if (onHistory != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(FleetIcons.MoreVert, contentDescription = "${d.label}: more") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("History") }, onClick = { menu = false; onHistory(d.key) })
                }
            }
        }
    }
}

/**
 * A number or a short text staged as it is typed (G1.5): nothing is sent
 * until the page's Save bar. The field keeps what the person typed; it starts
 * again from the shown value only when the hub's value changes or Reset or
 * Discard put it back ([epoch]).
 */
@Composable
private fun StagedValueField(
    d: SettingDescriptor,
    stored: String,
    shown: String,
    epoch: Int,
    busy: Boolean,
    onType: (String, String) -> Unit,
) {
    val asText = d.kind.type == "text" || d.kind.type == "time_range"
    val initial = if (asText) shown else d.toDisplay(shown)
    var draft by remember(d.key, stored, epoch) { mutableStateOf(initial) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it; onType(d.key, it) },
        singleLine = true,
        enabled = !busy,
        suffix = if (d.unitWord.isNotEmpty() && !asText) ({ Text(d.unitWord) }) else null,
        placeholder = if (d.kind.type == "time_range") ({ Text("22:00-07:30") }) else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The kinds typed into a field and sent on Save. */
private val TYPED = setOf("secs", "int", "text", "time_range")

/**
 * A matrix section's columns: its fields when every one is a choice-set
 * setting this hub described, over the same options, and the condition of
 * each holds; else null, and the section is drawn as plain rows.
 */
internal fun matrixColumns(state: FleetSettingsUiState, items: List<PageItem>): List<SettingDescriptor>? {
    val fields = items.filterIsInstance<PageItem.Field>()
    if (fields.size < 2 || fields.size != items.size) return null
    val ds = fields.map { f -> state.descriptors[f.key]?.takeIf { it.kind.type == "choice_set" && f.condition.holds(state.values) } ?: return null }
    return ds.takeIf { d -> d.all { it.kind.options == d.first().kind.options } }
}

/** The matrix: a row per option, a column per field, a box where the field holds the option. */
@Composable
private fun MatrixGrid(state: FleetSettingsUiState, columns: List<SettingDescriptor>, onSet: (String, String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            for (c in columns) {
                Text(c.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(MATRIX_CELL), textAlign = TextAlign.Center)
            }
        }
        for (o in columns.first().kind.options) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(columns.first().optionLabel(o), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                for (c in columns) {
                    val value = state.values[c.key] ?: c.value
                    val on = o in choiceSetOf(value)
                    val enabled = state.editable(c.key) && c.key !in state.busy
                    Checkbox(
                        checked = on,
                        enabled = enabled,
                        onCheckedChange = { onSet(c.key, c.withChoice(value, o, it)) },
                        modifier = Modifier.width(MATRIX_CELL).semantics { contentDescription = "${c.label}: ${c.optionLabel(o)}" },
                    )
                }
            }
        }
        for (c in columns) state.fieldErrors[c.key]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (state.canWrite && columns.none { state.editable(it.key) }) {
            Text("Change it on a desktop.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val MATRIX_CELL = 68.dp

/** A number or a short text, sent on Save: a field that wrote on every
 *  keystroke would send every half-typed value to the hub. */
@Composable
private fun ValueField(
    d: SettingDescriptor,
    value: String,
    busy: Boolean,
    onSet: (String, String) -> Unit,
    onRefuse: (String, String) -> Unit,
) {
    val asText = d.kind.type == "text" || d.kind.type == "time_range"
    val shown = if (asText) value else d.toDisplay(value)
    var draft by remember(d.key, shown) { mutableStateOf(shown) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            enabled = !busy,
            suffix = if (d.unitWord.isNotEmpty() && !asText) ({ Text(d.unitWord) }) else null,
            placeholder = if (d.kind.type == "time_range") ({ Text("22:00-07:30") }) else null,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            enabled = !busy && draft != shown,
            onClick = {
                if (d.kind.type == "time_range") {
                    val t = draft.trim()
                    if (t.isEmpty() || parseQuietHours(t) != null) {
                        onSet(d.key, t)
                    } else {
                        onRefuse(d.key, "${d.label}: a range like 22:00-07:30, or empty for none")
                    }
                } else if (d.kind.type == "text") {
                    onSet(d.key, draft.trim())
                } else {
                    d.fromDisplay(draft).fold(
                        onSuccess = { onSet(d.key, it) },
                        onFailure = { onRefuse(d.key, "${d.label}: ${it.message}") },
                    )
                }
            },
        ) { Text(if (busy) "Saving…" else "Save") }
    }
}

@Composable
private fun Suggestion(state: FleetSettingsUiState, d: SettingDescriptor, p: SettingProposal, onDecide: (Long, Boolean) -> Unit) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            "✦ Suggested: ${d.inWords(p.value)}" + (p.why?.let { " — “$it”" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (state.canWrite) {
            Row {
                TextButton(enabled = "#${p.id}" !in state.busy, onClick = { onDecide(p.id, true) }) { Text("Apply") }
                TextButton(enabled = "#${p.id}" !in state.busy, onClick = { onDecide(p.id, false) }) { Text("Not this") }
            }
        }
    }
}

@Composable
private fun Review(state: FleetSettingsUiState, onDecide: (Long, Boolean) -> Unit) {
    if (state.proposals.isEmpty()) {
        Text(
            "No proposed changes.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    for (p in state.proposals) {
        val d = state.descriptors[p.key]
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(d?.label ?: p.key, style = MaterialTheme.typography.bodyLarge)
            val now = d?.inWords(p.current) ?: p.current
            val next = d?.inWords(p.value) ?: p.value
            Text("$now → $next", style = MaterialTheme.typography.bodyMedium)
            if (p.current != p.before) {
                Text(
                    "Changed since it was proposed (it was ${d?.inWords(p.before) ?: p.before}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            p.why?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall) }
            Text(
                "suggested by ${p.sourceDetail?.let { "${p.source} ($it)" } ?: p.source}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.canWrite) {
                Row {
                    TextButton(enabled = "#${p.id}" !in state.busy, onClick = { onDecide(p.id, true) }) { Text("Apply") }
                    TextButton(enabled = "#${p.id}" !in state.busy, onClick = { onDecide(p.id, false) }) { Text("Not this") }
                }
            }
        }
    }
}

/** One setting's writes, newest first: when, who, from what to what, and the proposal it applied. */
@Composable
private fun SettingHistoryDialog(
    label: String,
    rows: List<SettingWrite>?,
    error: Friendly?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("History · $label") },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                when {
                    // A failed read says so, in the dialog it was asked from, with Retry.
                    error != null -> ErrorBanner(error, onRetry = onRetry)
                    rows == null -> InlineLoading(waiting = true)
                    rows.isEmpty() -> Text("Never changed: it has its default.", style = MaterialTheme.typography.bodySmall)
                    else -> for (w in rows) {
                        Text(
                            "${w.before ?: "default"} → ${w.after}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            listOfNotNull(
                                relativeAgo(w.at, epochSeconds()),
                                listOfNotNull(w.actor, w.actorDetail).joinToString(" "),
                                w.proposalId?.let { "proposal #$it" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
