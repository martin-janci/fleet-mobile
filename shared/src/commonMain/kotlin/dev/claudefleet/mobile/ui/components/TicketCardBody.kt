package dev.claudefleet.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.TicketCard

/**
 * A ticket's context card (`work { action: card }`, claude-fleet M9.2 /
 * M10.5): its acceptance criteria, else the excerpt, and **Copy**.
 *
 * Read-only by design (decision D15): the phone copies a card and never
 * sends one into a session. Every string is the tracker's and is drawn as
 * plain [Text] — no markup, no link detection, nothing opens by itself.
 * Draws nothing for a card with no body.
 */
@Composable
fun TicketCardBody(card: TicketCard, modifier: Modifier = Modifier, lineLimit: Int = 3) {
    if (!card.hasBody) return
    val clipboard = LocalClipboardManager.current
    // Keyed on the card, so a copied mark never carries over to another ticket.
    var copied by remember(card) { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (card.acceptance.isNotEmpty()) {
            Text("Acceptance criteria", style = MaterialTheme.typography.labelMedium)
            for (line in card.acceptance) {
                Text("• $line", style = MaterialTheme.typography.bodySmall, maxLines = lineLimit, overflow = TextOverflow.Ellipsis)
            }
        } else {
            card.excerpt?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = lineLimit * 2, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(card.copyText))
                copied = true
            },
            modifier = Modifier.testTag(TICKET_CARD_COPY_TAG),
        ) { Text(if (copied) "Copied" else "Copy") }
    }
}

/** The card's Copy button, for UI tests. */
const val TICKET_CARD_COPY_TAG = "ticket-card-copy"
