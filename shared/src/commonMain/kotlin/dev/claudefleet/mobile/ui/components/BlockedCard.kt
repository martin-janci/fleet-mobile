package dev.claudefleet.mobile.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.Answer
import dev.claudefleet.mobile.ui.BlockedCard
import dev.claudefleet.mobile.ui.theme.LocalStatusColors
import dev.claudefleet.mobile.ui.theme.StatusTone

/**
 * The card a blocked or stuck session gets, above the composer.
 *
 * It draws and nothing else. Which chips exist, what the headline says,
 * whether a restart is worth offering and whether the terminal is still a way
 * through are all decided by [BlockedCard] — `blockedCard` in `Blocked.kt`,
 * the one place that mapping lives — and everything about whether an answer
 * may be sent at all is decided by `SessionViewModel`. This file chooses
 * colours, spacing and the words on a button.
 *
 * Named `BlockedCardView` rather than `BlockedCard` only because the data it
 * draws already has that name; the file is named for the thing, as the brief
 * asks.
 *
 * @param readOnly a device paired `readonly` may not call `send_prompt` in
 *   either form, so it gets no chip and no Restart — the same rule that darkens
 *   the composer, applied one screen element earlier. The headline, the
 *   explanation and the terminal are reads and stay.
 * @param onRestart null hides the Restart button. Task 4 supplies the action;
 *   until then a card that offers a restart simply does not draw one.
 */
@Composable
fun BlockedCardView(
    card: BlockedCard,
    answering: Boolean,
    stillWaiting: Boolean,
    terminal: String?,
    readOnly: Boolean,
    onAnswer: (Answer) -> Unit,
    onShowTerminal: () -> Unit,
    onHideTerminal: () -> Unit,
    onRestart: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // The same amber/red triage the status chip and the row dot draw, from the
    // same source: a card that picked its own `tertiaryContainer` would be the
    // second place in the app deciding what "blocked" looks like, and the two
    // would drift. A card offering a restart is one nobody can answer from
    // here — the stuck end of the triage — and reads red.
    val tone = if (card.offerRestart) StatusTone.STUCK else StatusTone.BLOCKED
    val colors = LocalStatusColors.current(tone)
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        color = colors.container,
        contentColor = colors.onContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.headline,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (answering) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.onContainer,
                    )
                }
            }
            card.explain?.let {
                Spacer(Modifier.height(4.dp))
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
            if (stillWaiting) {
                Spacer(Modifier.height(4.dp))
                Text(
                    // Delivered, not refused — so it belongs on the card
                    // rather than in the error banner.
                    text = "Sent — still waiting for the agent to move on…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!readOnly && card.answers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (answer in card.answers) {
                        AssistChip(
                            onClick = { onAnswer(answer) },
                            enabled = !answering,
                            label = { Text(answerLabel(answer)) },
                            // The chip sits ON the status container, so its
                            // label has to be that container's own `on`
                            // colour rather than the scheme's `onSurface` —
                            // including while disabled, where the default
                            // would fade an already-dark-on-amber label to
                            // something that cannot be read at all.
                            colors = AssistChipDefaults.assistChipColors(
                                labelColor = colors.onContainer,
                                disabledLabelColor = colors.onContainer.copy(alpha = 0.5f),
                            ),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card.terminalAvailable) {
                    // Keyed on the capture rather than on a separate "shown"
                    // flag, which also makes the button a retry: a capture
                    // that failed says so in the banner and leaves Show
                    // terminal there to be tapped again.
                    TextButton(onClick = if (terminal == null) onShowTerminal else onHideTerminal) {
                        Text(if (terminal == null) "Show terminal" else "Hide terminal")
                    }
                }
                if (card.offerRestart && !readOnly && onRestart != null) {
                    TextButton(onClick = onRestart) { Text("Restart") }
                }
            }
            if (terminal != null) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = terminal,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        // A pane is fixed-width text: wrapping it would break
                        // every box-drawn prompt in it, so it scrolls sideways
                        // instead.
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                    )
                }
            }
        }
    }
}

/**
 * The words on one answer's chip.
 *
 * A numbered option is drawn as the REPL draws it — the number first, because
 * the number is what is actually sent. `Escape` is abbreviated because the key
 * on a phone keyboard is not called Escape at all and "Esc" is what the prompt
 * itself says. A free-text answer (the trust prompt's `y`/`n`) is its own text:
 * inventing a friendlier word for it here would be a second place deciding
 * what a trust prompt offers.
 */
internal fun answerLabel(answer: Answer): String = when (answer) {
    is Answer.Option -> "${answer.n} · ${answer.label}"
    Answer.Enter -> "Enter"
    Answer.Escape -> "Esc"
    Answer.Interrupt -> "Interrupt"
    is Answer.Text -> answer.text
}
