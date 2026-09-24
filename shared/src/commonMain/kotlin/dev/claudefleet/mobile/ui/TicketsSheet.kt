package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.WorkStatusDot

/** Everything the Tickets sheet reports. */
data class TicketsHandlers(
    val onClose: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onSelect: (Ticket?) -> Unit = {},
    val onOpenLive: () -> Unit = {},
    val onStartHere: () -> Unit = {},
    val onResumeHost: (String) -> Unit = {},
    val onResume: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * The Tickets sheet: *My work*, *Current sprint*, *Recent*, and a search by
 * key or pasted URL. Tapping a ticket shows what can be done with it.
 * Ticket text is the tracker's and is drawn as plain text only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketsSheet(state: TicketsUiState, handlers: TicketsHandlers) {
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Tickets", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = handlers.onQuery,
                label = { Text("Key or ticket URL") },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { handlers.onSearch() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            if (state.loading || state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            state.selected?.let { TicketActions(it, state.busy, handlers) }
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                state.found?.let { found ->
                    item(key = "found") { SectionTitle("Found") }
                    item(key = "found-${found.id}") { TicketRow(found, state.selected?.ticket?.id == found.id, handlers) }
                }
                for (section in state.sections) {
                    item(key = "section-${section.view}") { SectionTitle(section.title) }
                    if (section.tickets.isEmpty()) {
                        item(key = "empty-${section.view}") {
                            Text(
                                "Nothing here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                            )
                        }
                    }
                    items(section.tickets, key = { "${section.view}-${it.id}" }) { ticket ->
                        TicketRow(ticket, state.selected?.ticket?.id == ticket.id, handlers)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun TicketRow(ticket: Ticket, selected: Boolean, handlers: TicketsHandlers) {
    ListItem(
        modifier = Modifier.clickable { handlers.onSelect(if (selected) null else ticket) },
        leadingContent = { ticket.statusCategory?.let { WorkStatusDot(it) } },
        headlineContent = {
            Text(
                ticket.label,
                style = MaterialTheme.typography.titleSmall,
                textDecoration = if (ticket.unavailable) TextDecoration.LineThrough else null,
            )
        },
        supportingContent = {
            if (ticket.title.isNotBlank()) Text(ticket.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            val status = ticket.statusName ?: if (ticket.liveSessionIds.isNotEmpty()) "live" else null
            status?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        },
    )
}

/** Open, Start here, or Resume with a host picker — only what the hub and the token allow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TicketActions(detail: TicketDetail, busy: Boolean, handlers: TicketsHandlers) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(detail.ticket.label, style = MaterialTheme.typography.titleSmall)
                if (detail.ticket.title.isNotBlank()) {
                    Text(
                        " · ${detail.ticket.title}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            detail.ticket.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (detail.canResume && detail.resumeHosts.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (host in detail.resumeHosts) {
                        FilterChip(
                            selected = host == detail.resumeHost,
                            onClick = { handlers.onResumeHost(host) },
                            enabled = !busy,
                            label = { Text(host) },
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (detail.liveSessionId != null) Button(onClick = handlers.onOpenLive) { Text("Open") }
                if (detail.canResume) {
                    Button(onClick = handlers.onResume, enabled = !busy) {
                        Text(detail.resumeHost?.let { "Resume on $it" } ?: "Resume")
                    }
                }
                if (detail.canStart) OutlinedButton(onClick = handlers.onStartHere, enabled = !busy) { Text("Start here") }
            }
        }
    }
}
