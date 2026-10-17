package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.data.FleetSettingsActions
import dev.claudefleet.mobile.model.AUTONOMY_KEYS
import dev.claudefleet.mobile.model.Autonomy
import dev.claudefleet.mobile.model.autonomyOf
import dev.claudefleet.mobile.model.autonomyWord
import dev.claudefleet.mobile.model.autonomyWrites
import dev.claudefleet.mobile.ui.kit.OrbitChip
import dev.claudefleet.mobile.ui.theme.Fleet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val AUTONOMY_CHIP_TAG = "control.autonomy"

data class AutonomyUiState(
    /** The dial's four settings as the hub last answered; empty until read. */
    val values: Map<String, String> = emptyMap(),
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val error: Friendly? = null,
) {
    val current: Autonomy? get() = autonomyOf(values)
}

/**
 * The autonomy chip's state: read the four settings, write a position's
 * changes in order. A failed write stops the rest and says so; the next
 * read shows what landed.
 */
class AutonomyViewModel(private val actions: FleetSettingsActions, private val scope: CoroutineScope) {
    private val local = MutableStateFlow(AutonomyUiState())
    val state: StateFlow<AutonomyUiState> = local.asStateFlow()

    fun load(): Job = scope.launch {
        try {
            val all = actions.describe()
            local.update { s -> s.copy(values = all.filter { it.key in AUTONOMY_KEYS }.associate { it.key to it.value }, loaded = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        }
    }

    fun set(target: Autonomy): Job? {
        if (local.value.busy) return null
        local.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try {
                for ((key, value) in autonomyWrites(target, local.value.values)) {
                    actions.set(key, value)
                    local.update { s -> s.copy(values = s.values + (key to value)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                local.update { it.copy(error = friendly(t)) }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }
}

/** "Autonomy · Within your brief": a tap opens the three positions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutonomyChip(state: AutonomyUiState, onSet: (Autonomy) -> Unit, onDismissError: () -> Unit) {
    if (!state.loaded) return
    val o = Fleet.colors
    var open by remember { mutableStateOf(false) }
    OrbitChip(
        "Autonomy · ${autonomyWord(state.current)}",
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clickable(onClickLabel = "Change Control's autonomy") { open = true }
            .testTag(AUTONOMY_CHIP_TAG),
    )
    if (!open) return
    ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("How much Control does on its own", color = o.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            for (a in Autonomy.entries) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !state.busy, role = Role.RadioButton) { onSet(a) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(selected = state.current == a, onClick = null, enabled = !state.busy)
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(a.label, color = o.fg, fontSize = 15.sp)
                        Text(a.blurb, color = o.fgMuted, fontSize = 13.sp)
                    }
                }
            }
            if (state.current == null) {
                Text("Now: Custom. The settings were set apart; picking a position sets all four.", color = o.fgMuted, fontSize = 13.sp)
            }
            state.error?.let { e ->
                Text("${e.title}. ${e.body}", color = o.danger, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onDismissError).padding(vertical = 6.dp))
            }
        }
    }
}
