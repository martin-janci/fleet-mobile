package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.Facet
import dev.claudefleet.mobile.ui.theme.FleetIcons

/*
 * The filter chrome the Sessions list and My work share, laid out like the
 * desktop's (claude-fleet `ActiveFilters.svelte`, `FilterChipGroup.svelte`):
 * a *Filters (n)* entry that opens one sheet of labelled chip groups, a
 * strip of removable chips for what is on plus *Clear all*, and the
 * *N archived hidden · Show archived* row at the end of a list.
 *
 * Every control is at least 48 dp to the touch: Material's chips and text
 * buttons reserve that around a smaller drawing on their own, and the rows
 * here set it themselves.
 */

/** Material's minimum touch target. */
internal val TOUCH_TARGET = 48.dp

/** "Filters", or "Filters (2)" while the sheet holds two filters that are on. */
internal fun filtersButtonLabel(count: Int): String = if (count > 0) "Filters ($count)" else "Filters"

/**
 * The archived row's sentence: "3 archived sessions hidden", or, while they
 * are shown, "Showing archived sessions". [noun] is the plural ("sessions",
 * "tasks"). [attention] of the hidden ones want a person, and the row says
 * so: hidden by default must not mean a blocked agent nobody can see.
 */
internal fun archivedRowText(hidden: Int, showing: Boolean, noun: String, attention: Int = 0): String = when {
    showing -> "Showing archived $noun"
    else -> buildString {
        append(if (hidden == 1) "1 archived ${noun.removeSuffix("s")} hidden" else "$hidden archived $noun hidden")
        if (attention > 0) append(if (attention == 1) " · 1 needs you" else " · $attention need you")
    }
}

/** The entry to the filter sheet: selected, and counted, while the sheet holds something that is on. */
@Composable
internal fun FiltersButton(count: Int, onClick: () -> Unit) {
    FilterChip(
        selected = count > 0,
        onClick = onClick,
        label = { Text(filtersButtonLabel(count)) },
        leadingIcon = {
            Icon(FleetIcons.Filters, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
        },
        modifier = Modifier.semantics {
            contentDescription = if (count > 0) "Filters, $count on" else "Filters"
        },
    )
}

/**
 * One chip per active filter, each removable with its ×, then *Clear all*.
 * Drawn only while something narrows the list. The chips scroll sideways
 * rather than wrap, so the strip is one line on any width and *Clear all*
 * never leaves the screen.
 */
@Composable
internal fun <I> FilterStrip(
    facets: List<Facet<I>>,
    onClear: (I) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
    /** Something said before the chips ("3 of 87"); null says nothing. */
    lead: String? = null,
) {
    if (facets.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (lead != null) {
                item(key = "lead") {
                    Text(
                        lead,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(facets, key = { it.id.toString() }) { facet ->
                InputChip(
                    selected = false,
                    onClick = { onClear(facet.id) },
                    label = { Text(facet.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = {
                        Icon(FleetIcons.Close, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize))
                    },
                    modifier = Modifier
                        .widthIn(max = 240.dp)
                        // One node, said as what the tap does.
                        .clearAndSetSemantics {
                            contentDescription = "${facet.label}. Remove filter"
                            role = Role.Button
                            onClick { onClear(facet.id); true }
                        },
                )
            }
        }
        TextButton(onClick = onClearAll, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("Clear all", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * The row at the end of a list whose archived items are hidden by default:
 * *3 archived sessions hidden · Show archived*, or, while they are shown,
 * *Showing archived sessions · Hide archived*.
 */
@Composable
internal fun ArchivedRow(
    hidden: Int,
    showing: Boolean,
    noun: String,
    onSetShown: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    attention: Int = 0,
) {
    if (hidden <= 0 && !showing) return
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            archivedRowText(hidden, showing, noun, attention),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onSetShown(!showing) }) {
            Text(if (showing) "Hide archived" else "Show archived")
        }
    }
}

/** A labelled group of the filter sheet: the heading a chip cannot carry on its own. */
@Composable
internal fun FilterGroup(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp).semantics { heading() },
        )
        content()
    }
}

/** A wrapping row of chips. Material reserves 48 dp around each 32 dp chip, so the rows need no extra gap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipFlow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** One choice of a single-choice group: *Any* is every group's reset. */
@Composable
internal fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, leadingIcon: (@Composable () -> Unit)? = null) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = leadingIcon,
        modifier = Modifier.widthIn(max = 240.dp),
    )
}

/**
 * A labelled switch, the whole row one 48 dp toggle — a screen reader hears
 * one switch with its label, not a label and an unnamed switch.
 */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit, help: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TOUCH_TARGET)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (help != null) {
                Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
