package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.TuneAnswer
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.SheetAction
import dev.claudefleet.mobile.ui.theme.Fleet

/**
 * The Tune prompt sheet's state (`session_prompt_tune`): what to improve,
 * whether a tune runs, what went wrong, and the model's proposal with the
 * person's edit of it ([rewrite]). Nothing in here is ever sent by itself.
 */
data class PromptTune(
    val ask: String = "",
    val running: Boolean = false,
    val error: String? = null,
    val answer: TuneAnswer? = null,
    /** The rewrite as the person has edited it; starts as [TuneAnswer.prompt]. */
    val rewrite: String = "",
)

/** The quick asks under the field: a tap tunes with that one. */
val TUNE_ASKS: List<String> = listOf("Shorter", "More specific", "Add what done looks like", "Split into steps")

/**
 * How many of the session's earlier prompts go along as context: the hub's
 * `HISTORY_MAX_ITEMS`, as the desktop sends (it cuts the oldest to fit).
 */
internal const val TUNE_HISTORY = 40

/**
 * The ask a quick chip runs with: the chip's words after what the person
 * typed, so a tap never throws their words away. A field that is empty or
 * holds only a chip's words takes the tapped chip's instead.
 */
internal fun tuneAskWith(typed: String, chip: String): String {
    val t = typed.trim()
    return if (t.isEmpty() || t in TUNE_ASKS) chip else "$t; $chip"
}

/** The session's last prompts on screen, oldest first, for the tuner's context. */
internal fun tuneHistory(conversation: Conversation, max: Int = TUNE_HISTORY): List<String> =
    conversation.turns.mapNotNull { t -> t.prompt?.trim()?.takeIf { it.isNotEmpty() } }.takeLast(max)

/**
 * A tune's failure in the sheet. A hub refusal is written for a person
 * ("Prompt tuner is off. Turn it on in Settings › …"), so its own words;
 * anything else in [friendly]'s.
 */
internal fun tuneErrorText(t: Throwable): String = when (t) {
    is HubError.Tool -> t.message
    else -> friendly(t).let { "${it.title}. ${it.body}" }
}

/** "Drafted · by claude-haiku on mercury": who wrote the rewrite, where. */
internal fun tuneDraftedLine(answer: TuneAnswer): String = buildString {
    append("Drafted")
    val by = answer.model.takeIf { it.isNotBlank() }
    val on = answer.hostAlias.takeIf { it.isNotBlank() }
    if (by != null || on != null) append(" ·")
    by?.let { append(" by $it") }
    on?.let { append(" on $it") }
}

/** What the session screen hands the Tune prompt sheet. The defaults do nothing. */
class PromptTuneHost(
    val onOpen: () -> Unit = {},
    val onAsk: (String) -> Unit = {},
    /** Run a tune: a quick ask's words, or null for what the field holds. */
    val onTune: (String?) -> Unit = {},
    val onRewrite: (String) -> Unit = {},
    val onAccept: () -> Unit = {},
    val onDiscard: () -> Unit = {},
)

const val TUNE_OPEN_TAG = "session.composer.tune"
const val TUNE_SHEET_TAG = "session.tune"
const val TUNE_ASK_TAG = "session.tune.ask"
const val TUNE_RUN_TAG = "session.tune.run"
const val TUNE_REWRITE_TAG = "session.tune.rewrite"
const val TUNE_CHIP_TAG = "session.tune.chip."

/**
 * Tune prompt: a model rewrites the composer's draft into a clearer prompt
 * for the agent, as a proposal. *Use this* puts the (editable) rewrite in the
 * composer in place of the draft; *Discard* leaves the draft as it was.
 * Neither sends anything: the person still taps Send.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PromptTuneSheet(state: SessionUiState, tune: PromptTune, host: PromptTuneHost) {
    val o = Fleet.colors
    val answer = tune.answer
    val model = answer?.model?.takeIf { it.isNotBlank() } ?: "A model"
    val mayRun = !tune.running && state.canTune
    BottomSheet(
        title = "Tune prompt",
        meta = "$model rewrites your draft for the agent. Nothing is sent.",
        onDismiss = host.onDiscard,
        modifier = Modifier.testTag(TUNE_SHEET_TAG),
        primary = if (answer != null && !answer.unchanged) {
            SheetAction("Use this", enabled = !tune.running && tune.rewrite.isNotBlank(), onClick = host.onAccept)
        } else {
            null
        },
        cancelLabel = "Discard",
        scrollable = true,
    ) {
        OutlinedTextField(
            value = tune.ask,
            onValueChange = host.onAsk,
            label = { Text("What should it improve?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(TUNE_ASK_TAG),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (ask in TUNE_ASKS) {
                SuggestionChip(
                    onClick = { host.onTune(ask) },
                    label = { Text(ask) },
                    enabled = mayRun,
                    modifier = Modifier.testTag(TUNE_CHIP_TAG + ask),
                )
            }
        }
        OutlinedButton(onClick = { host.onTune(null) }, enabled = mayRun, modifier = Modifier.testTag(TUNE_RUN_TAG)) {
            Text(
                when {
                    tune.running -> "Tuning…"
                    answer != null -> "Tune again"
                    else -> "Tune"
                },
            )
        }
        tune.error?.let {
            Text(it, style = Fleet.type.textSm, color = o.danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (answer != null) {
            if (answer.unchanged) {
                Text("Your draft already reads clearly; nothing was changed.", style = Fleet.type.textSm, color = o.fg)
            } else {
                answer.notes.takeIf { it.isNotBlank() }?.let { Text(it, style = Fleet.type.textSm, color = o.fgMuted) }
                OutlinedTextField(
                    value = tune.rewrite,
                    onValueChange = host.onRewrite,
                    label = { Text("Rewrite") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag(TUNE_REWRITE_TAG),
                )
            }
            Text("✎ ${tuneDraftedLine(answer)}", style = Fleet.type.textXs, color = o.fgMuted)
        }
    }
}
