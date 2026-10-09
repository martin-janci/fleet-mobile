package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.relativeAgo
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/** One answer the question card offers, as the words on its row. */
data class QuestionAnswer(val answer: Answer, val label: String)

/**
 * The answers the New layout's question card draws, in the agent's own order.
 *
 * A question with numbered answers shows those and nothing else. Its raw
 * Enter and Esc are left to the agent tab, where they say what they do: on a
 * permission question Enter picks whichever answer is highlighted — it
 * approves — so a bare "Enter" beside "1 · Yes" is a second Approve that does
 * not look like one. A prompt with no numbers (the trust prompt, "press Enter",
 * an older hub) has only keys, and each says what it does.
 *
 * Nothing here is a default: the card draws every row the same, with none
 * pre-selected and none focused (the never-list in the manual's `ai.md`).
 */
fun questionAnswers(card: BlockedCard, stuckKind: String?): List<QuestionAnswer> {
    val options = card.answers.filterIsInstance<Answer.Option>()
    if (options.isNotEmpty()) return options.map { QuestionAnswer(it, "${it.n}  ${it.label}") }
    return card.answers.mapNotNull { answer ->
        when (answer) {
            Answer.Enter -> QuestionAnswer(answer, "${keyAnswerWords(stuckKind, enter = true)} · Enter")
            Answer.Escape -> QuestionAnswer(answer, "${keyAnswerWords(stuckKind, enter = false)} · Esc")
            else -> null
        }
    }
}

private fun keyAnswerWords(stuckKind: String?, enter: Boolean): String = when (stuckKind) {
    "trust_prompt" -> if (enter) "Trust this folder" else "Exit Claude Code"
    "press_enter" -> if (enter) "Continue" else "Cancel"
    else -> if (enter) "The highlighted answer" else "Cancel"
}

/**
 * The answer that says no and lets the person say what to do instead — Claude
 * Code's "No, and tell Claude what to do differently". It is what "Answer in
 * your own words…" presses before the words go out, and never one that allows:
 * a card without such an answer offers no own-words reply at all.
 */
fun declineOption(card: BlockedCard): Answer.Option? =
    card.answers.filterIsInstance<Answer.Option>().lastOrNull { it.label.trimStart().startsWith("No", ignoreCase = true) }

/**
 * The question a waiting session asks, as the New layout draws it (redesign
 * 14.4, MobileSession): the exact command, then the agent's own answers as
 * full-width rows, all alike — Approve is never pre-selected. Under them,
 * "Answer in your own words…" (when the question has a no-and-say-why answer)
 * puts the cursor in the composer, and "Show in <agent>" opens the agent tab
 * where the raw keys are.
 *
 * Whether an answer may go out at all is still [SessionViewModel.answer]'s
 * call — the card only draws [SessionUiState.canAnswer].
 */
@Composable
internal fun QuestionCard(
    card: BlockedCard,
    state: SessionUiState,
    agent: String,
    asking: ConvItem.Tool?,
    onAnswer: (Answer) -> Unit,
    onAnswerInWords: (() -> Unit)?,
    onShowAgent: () -> Unit,
    onRestart: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val stuck = card.offerRestart
    val line = if (stuck) Fleet.colors.failedLine else Fleet.colors.waitingLine
    val shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp)
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        color = if (stuck) Fleet.colors.failedSoft else Fleet.colors.waitingSoft,
        contentColor = Fleet.colors.fg,
        shape = shape,
        border = BorderStroke(1.dp, line),
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(card.headline, style = Fleet.type.textLg, modifier = Modifier.weight(1f))
                relativeAgo(state.session?.lastActivityAt, state.nowSeconds)?.let {
                    Text("asked $it", style = Fleet.type.textXs, color = Fleet.colors.fgMuted)
                }
            }
            asking?.let { tool ->
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        text = tool.target?.takeIf { it.isNotBlank() } ?: tool.summary,
                        style = Fleet.type.code,
                        color = Fleet.colors.code,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            card.explain?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = Fleet.type.textMd, color = Fleet.colors.fg2)
            }
            if (state.stillWaiting) {
                Spacer(Modifier.height(4.dp))
                Text("Sent. Still waiting for $agent to move on…", style = Fleet.type.textXs, color = Fleet.colors.fgMuted)
            }
            val answers = questionAnswers(card, state.session?.stuckKind).filter { state.mayAnswerWith(it.answer) }
            if (answers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (answer in answers) {
                        AnswerRow(
                            label = answer.label,
                            enabled = state.canAnswer,
                            onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onAnswer(answer.answer) },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!state.readOnly && onAnswerInWords != null && declineOption(card) != null) {
                    TextButton(onClick = onAnswerInWords, enabled = state.canAnswer) { Text("Answer in your own words…") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onShowAgent) { Text("Show in $agent") }
            }
            if (stuck && !state.readOnly && onRestart != null) {
                TextButton(onClick = onRestart, enabled = state.connected) { Text("Restart") }
            }
        }
    }
}

/**
 * One answer, the card's full width and at least a thumb tall. Every row is
 * this one composable with no selected or primary variant, so no answer can
 * be drawn as the default.
 */
@Composable
private fun AnswerRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(OrbitTokens.radius("radius-lg").dp)
    Text(
        text = label,
        style = Fleet.type.textMd,
        color = if (enabled) Fleet.colors.fg else Fleet.colors.fgMuted,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTokens.spacing("touch-min").dp)
            .clip(shape)
            .border(1.dp, Fleet.colors.controlBorder, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    )
}
