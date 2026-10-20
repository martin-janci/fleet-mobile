package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.ESCALATIONS_SHOWN
import dev.claudefleet.mobile.model.Escalation
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.LaterEntry
import dev.claudefleet.mobile.model.dropRecoveredHosts
import dev.claudefleet.mobile.model.laterEntryText
import dev.claudefleet.mobile.model.parseLaterEntry
import dev.claudefleet.mobile.model.pruneLater
import dev.claudefleet.mobile.store.Prefs
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The escalation cards put off with Later, kept in [prefs] so a card the
 * person put off stays off after the app restarts (Control review F18). A
 * key carries the session's turn ([dev.claudefleet.mobile.model.escalationKey]),
 * so the same session needing the person again later is a new card.
 */
class EscalationLater(private val prefs: Prefs? = null, private val now: () -> Long = { epochSeconds() }) {
    private var entries: List<LaterEntry> = run {
        val t = now()
        pruneLater(prefs?.getStringList(KEY).orEmpty().map { parseLaterEntry(it, t) }, t)
    }
    private val keys = MutableStateFlow(entries.map { it.key })

    val later: StateFlow<List<String>> = keys.asStateFlow()

    fun putOff(key: String) {
        val t = now()
        save(pruneLater(entries.filterNot { it.key == key } + LaterEntry(key, t), t))
    }

    /**
     * Forget the Later of every host-down card whose host answers again, so
     * the host's next outage is a card again (review w21-1). Writes only
     * when something was dropped.
     */
    fun forgetRecoveredHosts(hosts: List<HostRow>) {
        val kept = dropRecoveredHosts(entries, hosts)
        if (kept.size != entries.size) save(kept)
    }

    private fun save(next: List<LaterEntry>) {
        entries = next
        keys.value = next.map { it.key }
        prefs?.putStringList(KEY, next.map(::laterEntryText))
    }

    companion object {
        const val KEY = "control_escalations_later"
    }
}

const val ESCALATION_CARD_TAG = "control.escalation."
const val ESCALATION_OPEN_TAG = "control.escalation.open."
const val ESCALATION_LATER_TAG = "control.escalation.later."
const val ESCALATIONS_MORE_TAG = "control.escalations.more"

/** "2 more in Inbox" (the tab it opens; review w21-13), or null when every card is drawn. */
internal fun escalationsMoreLine(total: Int): String? {
    val more = total - ESCALATIONS_SHOWN
    return if (more > 0) "$more more in Inbox" else null
}

/**
 * What needs the person, as amber cards above Control's composer (the
 * desktop's `ControlEscalations.svelte`): Open shows the session, where its
 * question is answered; Later puts the card off until that session needs the
 * person again. Past [ESCALATIONS_SHOWN] the rest are counted, and the count
 * opens Needs you.
 */
@Composable
fun EscalationCards(
    all: List<Escalation>,
    onOpen: (Long) -> Unit,
    onLater: (String) -> Unit,
    onMore: () -> Unit,
) {
    if (all.isEmpty()) return
    val o = Fleet.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (e in all.take(ESCALATIONS_SHOWN)) {
            Surface(
                color = o.statusWaiting.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, o.statusWaiting),
                shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp),
                modifier = Modifier.fillMaxWidth().testTag(ESCALATION_CARD_TAG + e.key),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("!", style = Fleet.type.textSm, color = o.statusWaiting)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(e.line, style = Fleet.type.textSm, color = o.fg)
                        e.detail?.let {
                            Text(it, style = Fleet.type.textSm, color = o.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    OutlinedButton(
                        onClick = { onOpen(e.sessionId) },
                        modifier = Modifier.heightIn(min = 36.dp).testTag(ESCALATION_OPEN_TAG + e.key),
                    ) { Text("Open") }
                    TextButton(
                        onClick = { onLater(e.key) },
                        modifier = Modifier.testTag(ESCALATION_LATER_TAG + e.key),
                    ) { Text("Later") }
                }
            }
        }
        escalationsMoreLine(all.size)?.let { line ->
            TextButton(onClick = onMore, modifier = Modifier.testTag(ESCALATIONS_MORE_TAG)) {
                Text(line, style = Fleet.type.textSm, color = o.accent)
            }
        }
    }
}
