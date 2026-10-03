package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.Page
import dev.claudefleet.mobile.model.PageItem
import dev.claudefleet.mobile.model.SettingDescriptor
import dev.claudefleet.mobile.model.SettingProposal
import dev.claudefleet.mobile.model.editableOnPhone
import dev.claudefleet.mobile.model.fromDisplay
import dev.claudefleet.mobile.model.holds
import dev.claudefleet.mobile.model.inWords
import dev.claudefleet.mobile.model.rangeText
import dev.claudefleet.mobile.model.toDisplay
import dev.claudefleet.mobile.model.unitWord
import dev.claudefleet.mobile.ui.components.ErrorBanner

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
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
) {
    if (state.loading && !state.loaded) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
    // Everything that can fail here reported into `state.error` and nothing
    // drew it: a failed read left an empty section and a failed Apply just
    // un-busied its button, both indistinguishable from "the hub has nothing
    // to say". The banner is the one the rest of the app uses; the Retry is
    // beside it because `load()`'s only other caller fires once per process
    // (and now on a reconnect — see `FleetSettingsViewModel.load`).
    // Wrapped into a [Friendly] here, at the point `ErrorBanner` needs one —
    // `FleetSettingsUiState.error` stays a plain `String?`, as the other
    // migrated screens' do.
    val errorAsFriendly = state.error?.asGenericFriendly()
    if (errorAsFriendly != null) {
        ErrorBanner(errorAsFriendly, onDismiss = onDismissError)
        if (!state.loaded) {
            TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Try again")
            }
        }
    }
    val page = state.page
    if (page == null) {
        PageList(state, clientName, onOpen)
    } else {
        PageBody(state, page, onBack, onOpen, onSet, onRefuse, onDecide)
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
    // Only once the hub HAS answered. `canWrite` defaults to false and is set
    // at the end of a successful load, so while the three reads were in flight
    // — and for ever after a failure — this read "the hub's operator trusts
    // this device: fleet-hub client trust <name>", which is a wrong
    // instruction for a device already trusted and also exactly what an empty
    // catalogue looked like.
    Text(
        text = when {
            !state.loaded -> "Reading the hub’s settings…"
            state.canWrite -> "The hub’s settings: a change here is the hub’s, for the whole fleet."
            else ->
                "The hub’s settings, read only. To change them here, the hub’s operator " +
                    "trusts this device: fleet-hub client trust ${clientName.ifBlank { "<name>" }}"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    if (state.loaded && state.pages.isEmpty()) {
        Text(
            "The hub offers this device no settings pages.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
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
    if (page.layout == "review_apply") {
        Review(state, onDecide)
        return
    }
    // ONE tab at a time, with its title, as the desktop's `PageView` draws it.
    // `allSections` flattened the lot: `settings.work` is tabs-only (Detection
    // / Tidy-up / Retention), so its six sections ran together with the three
    // tab names gone, and a tab's own `when` was never evaluated.
    val tabs = page.shownTabs(state.values)
    var tab by remember(page.id) { mutableStateOf(0) }
    if (tabs.size > 1) {
        val selected = tab.coerceIn(0, tabs.size - 1)
        ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = i == selected, onClick = { tab = i }, text = { Text(t.title) })
            }
        }
    }
    val sections = page.shownSections(state.values, tab.coerceIn(0, maxOf(tabs.size - 1, 0)))
        .filter { it.condition.holds(state.values) }
    for (section in sections) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        // `collapsible` / `advanced` were declared and never read, so four
        // advanced sections of the fixture drew open and unmarked. The desktop
        // closes an advanced one and badges it.
        var open by remember(page.id, section.title) { mutableStateOf(!section.advanced) }
        val foldable = section.collapsible || section.advanced
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (foldable) Modifier.clickable { open = !open } else Modifier)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                section.title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (section.advanced) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "ADVANCED",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (foldable) {
                Spacer(Modifier.weight(1f))
                Text(if (open) "▾" else "▸", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!open) continue
        // `Section.intro` was declared and never drawn, though the desktop
        // draws it: the one sentence that says what the section is for.
        section.intro?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        // Parsed ONCE per section, not on every recomposition: every item of
        // every drawn section went back through `PageItem.of` — a `when`
        // decode among them — on each frame a switch or a keystroke caused.
        val items = remember(section.items) { section.items.map(PageItem::of) }
        for (item in items) {
            when (item) {
                is PageItem.Field -> {
                    val d = state.descriptors[item.key]
                    if (d != null && item.condition.holds(state.values)) {
                        FieldRow(state, d, item.hint, item.readOnly, onSet, onRefuse, onDecide)
                    }
                }
                // `tone` was parsed and never read, so the fixture's five
                // `warn` notices drew as plain body text beside its three
                // `info` ones — the distinction the hub went to the trouble of
                // sending.
                is PageItem.Notice -> Text(
                    if (item.tone == "info") item.text else "⚠ ${item.text}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (item.tone) {
                        "danger" -> MaterialTheme.colorScheme.error
                        "warn" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .semantics { if (item.tone != "info") contentDescription = "Warning. ${item.text}" },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldRow(
    state: FleetSettingsUiState,
    d: SettingDescriptor,
    hint: String?,
    shownOnly: Boolean,
    onSet: (String, String) -> Unit,
    onRefuse: (String, String) -> Unit,
    onDecide: (Long, Boolean) -> Unit,
) {
    val value = state.values[d.key] ?: d.value
    val editable = state.editable(d.key) && !shownOnly
    val busy = d.key in state.busy
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (editable && d.kind.type == "bool") {
                Switch(checked = value == "true", enabled = !busy, onCheckedChange = { onSet(d.key, if (it) "true" else "false") })
            } else if (!editable || d.kind.type != "choice") {
                if (!editable || d.kind.type !in setOf("secs", "int", "text")) {
                    Text(d.inWords(value), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (editable && d.kind.type == "choice") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (o in d.kind.options) {
                    FilterChip(
                        selected = value == o,
                        enabled = !busy,
                        onClick = { onSet(d.key, o) },
                        label = { Text(d.optionLabel(o)) },
                    )
                }
            }
        }
        if (editable && d.kind.type in setOf("secs", "int", "text")) {
            ValueField(d, value, busy, state.settled, onSet, onRefuse)
        }
        val range = d.rangeText()
        Text(
            buildString {
                append(d.help)
                if (range.isNotEmpty()) append(" ($range)")
                hint?.let { append(" "); append(it) }
                if (d.restart == "app") append(" Applies after the hub restarts.")
                // The wire's other non-`none` value, which the desktop draws
                // and this dropped: the hub's enum is None | App | Hooks, and
                // `work.session_start_context` carries `hooks`.
                if (d.restart == "hooks") append(" Applies when the hosts’ hooks are next installed.")
                d.ownedBy?.let { append(" Change it with $it.") }
                // `!d.editableOnPhone`, not the composite `!editable`: that
                // folded "this kind needs a desktop" together with "this PAGE
                // shows this field read-only", and a desktop renders a
                // `readonly` widget as a plain span too — so it could not be
                // changed there either. Live on `decide.jev.work_link`.
                if (!d.readOnlyHere && !shownOnly && state.canWrite && !d.editableOnPhone) {
                    append(" Change it on a desktop.")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.fieldErrors[d.key]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        // Apply WRITES the value, so the row is an editing control and is
        // gated like the three above it: a page that marks the field
        // `widget: "readonly"` (`shownOnly`), or the hub that owns it
        // (`readOnlyHere`), locks it here too. The hub cannot catch this —
        // `settings::set_by` refuses `owned_by` keys and knows nothing about a
        // page's widget — so a tap used to turn on exactly what the page locks.
        // The desktop gates the same control (`FieldRow.svelte`, `{#if
        // proposal && !readonly}`); the Proposed-changes page still lists it.
        if (!shownOnly && !d.readOnlyHere) {
            state.proposalFor(d.key)?.let { p -> Suggestion(state, d, p, onDecide) }
        }
    }
}

/** A number or a short text, sent on Save: a field that wrote on every
 *  keystroke would send every half-typed value to the hub. */
@Composable
private fun ValueField(
    d: SettingDescriptor,
    value: String,
    busy: Boolean,
    settled: Int,
    onSet: (String, String) -> Unit,
    onRefuse: (String, String) -> Unit,
) {
    val shown = if (d.kind.type == "text") value else d.toDisplay(value)
    // `settled` is a key too: a write the view model drops as a no-op —
    // `"60.0"` or `"060"` for a stored `60` — changed no value, so the draft
    // kept the person's spelling and Save stayed live over a tap that did
    // nothing. Bumping it re-seeds the field with the hub's own text.
    var draft by remember(d.key, shown, settled) { mutableStateOf(shown) }
    val save = {
        if (d.kind.type == "text") {
            onSet(d.key, draft.trim())
        } else {
            d.fromDisplay(draft).fold(
                onSuccess = { onSet(d.key, it) },
                onFailure = { onRefuse(d.key, "${d.label}: ${it.message}") },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            enabled = !busy,
            suffix = if (d.unitWord.isNotEmpty() && d.kind.type != "text") ({ Text(d.unitWord) }) else null,
            // Every other text input in the app declares its keyboard; this one
            // did not, so a field `fromDisplay` will only read as a
            // non-negative decimal opened a full QWERTY — which is also how
            // `"2d"` got typed into an hours field.
            keyboardOptions = KeyboardOptions(
                keyboardType = if (d.kind.type == "text") KeyboardType.Text else KeyboardType.Decimal,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (!busy && draft != shown) save() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(enabled = !busy && draft != shown, onClick = save) {
            Text(if (busy) "Saving…" else "Save")
        }
    }
}

@Composable
private fun Suggestion(state: FleetSettingsUiState, d: SettingDescriptor, p: SettingProposal, onDecide: (Long, Boolean) -> Unit) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        // WHO suggested it, as the Review page and the desktop's own field row
        // both say: the hub's vocabulary is person | agent | system, so without
        // it a colleague's device's suggestion was indistinguishable from an
        // agent's on the one screen where a person acts on it.
        val who = p.sourceDetail?.let { "${p.source} ($it)" } ?: p.source
        Text(
            "✦ Suggested by $who: ${d.inWords(p.value)}" + (p.why?.let { " — “$it”" } ?: ""),
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
            // The value the SCREEN holds, not the proposal's frozen snapshot.
            // Two stores hold the same fact — `values`, which a write updates,
            // and `proposals[].current`, which it does not — and the hub does
            // not refresh a pending proposal when the key is set. So after the
            // person changed a key themselves the row printed the pre-change
            // value as "now" and suppressed the drift warning, for exactly the
            // case they had caused.
            val nowRaw = state.values[p.key] ?: p.current
            val now = d?.inWords(nowRaw) ?: nowRaw
            val next = d?.inWords(p.value) ?: p.value
            Text("$now → $next", style = MaterialTheme.typography.bodyMedium)
            if (nowRaw != p.before) {
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
