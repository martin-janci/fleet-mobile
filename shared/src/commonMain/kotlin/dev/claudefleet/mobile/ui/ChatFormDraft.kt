package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.DraftField
import dev.claudefleet.mobile.model.FormDraft
import dev.claudefleet.mobile.model.PartialForm
import dev.claudefleet.mobile.model.fieldHolds
import dev.claudefleet.mobile.model.partialForm
import dev.claudefleet.mobile.ui.components.FieldView
import dev.claudefleet.mobile.ui.components.Note
import dev.claudefleet.mobile.ui.components.parseNumber
import dev.claudefleet.mobile.ui.kit.Atom
import dev.claudefleet.mobile.ui.kit.StepBars
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import kotlinx.serialization.json.JsonElement

/*
 * A chat form while its agent still writes it (`ask { draft }`, the
 * MobileChatForms board's Building): the card appears as the JSON arrives —
 * the title, step bars, the first step's fields ready as soon as each is
 * whole, skeletons for the rest, and a small Atom saying what the agent
 * reads. The person may fill in step 1 meanwhile; nothing is sent from here,
 * and the answers go to the form once `ask { form }` opens it
 * (`ChatFormCard`'s `seed`). The desktop's `ChatWizards.svelte` +
 * `ChatForm` in its Building state.
 */

internal const val FORM_DRAFT = "form-draft"
internal const val FORM_DRAFT_TITLE = "form-draft-title"
internal const val FORM_DRAFT_READING = "form-draft-reading"
internal const val FORM_DRAFT_REST = "form-draft-rest"

/** The draft field's skeleton, `form-draft-<name>`. */
internal fun draftFieldTag(name: String) = "form-draft-$name"

/** The line by the Atom: "Writing the form · reading the Jira epic PD-3100". */
fun draftReadingLine(why: String?): String =
    "Writing the form" + (why?.trim()?.takeIf { it.isNotEmpty() }?.let { " · reading $it" } ?: "")

/** "step 1 of 3" over the bars, once the draft holds more than one step; null for one. */
fun draftStepLine(partial: PartialForm): String? =
    partial.steps.size.takeIf { it >= 2 }?.let { "step 1 of $it" }

/** Whether the first step is still being written: the rest of it is drawn as skeletons. */
fun draftStillArriving(partial: PartialForm): Boolean = !partial.complete && partial.steps.size <= 1

/**
 * The draft's card. [values] are the person's answers so far, kept by the
 * screen across draft writes so a new chunk never clears them; [onChange]
 * sets one. [canAnswer] false draws the fields with nothing to press.
 */
@Composable
fun ChatFormDraftCard(
    draft: FormDraft,
    values: Map<String, JsonElement>,
    onChange: (String, JsonElement?) -> Unit,
    canAnswer: Boolean,
    modifier: Modifier = Modifier,
) {
    val o = Fleet.colors
    val partial = remember(draft.draft) { partialForm(draft.draft) }
    var numberText by remember { mutableStateOf(mapOf<String, String>()) }
    val first = partial.steps.firstOrNull()
    Surface(
        color = o.bgPane,
        border = BorderStroke(1.dp, o.controlBorder),
        shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag(FORM_DRAFT),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    partial.title ?: "A form",
                    style = Fleet.type.textMd,
                    color = if (partial.title != null) o.fg else o.fgMuted,
                    modifier = Modifier.weight(1f).testTag(FORM_DRAFT_TITLE),
                )
                draftStepLine(partial)?.let { Text(it, style = Fleet.type.textSm, color = o.fgMuted) }
            }
            StepBars(step = 1, total = partial.steps.size, gutter = false)
            partial.intro?.let { Note(it) }
            first?.intro?.let { Note(it) }
            for (d in first?.fields.orEmpty()) {
                val f = d.field
                if (f == null) {
                    DraftSkeleton(d)
                } else if (fieldHolds(f.whenCond, values)) {
                    FieldView(
                        f,
                        values[f.name],
                        numberText[f.name],
                        enabled = canAnswer,
                        onNumberText = { t ->
                            numberText = numberText + (f.name to t)
                            onChange(f.name, if (t.isBlank()) null else parseNumber(f, t))
                        },
                        onChange = { onChange(f.name, it) },
                    )
                }
            }
            if (draftStillArriving(partial)) {
                Column(Modifier.testTag(FORM_DRAFT_REST), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SkeletonField(0.3f)
                    SkeletonField(0.4f)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag(FORM_DRAFT_READING),
            ) {
                Atom()
                Text(draftReadingLine(draft.why), style = Fleet.type.textSm, color = o.fgMuted)
            }
            if (canAnswer && partial.fillable.isNotEmpty()) {
                Note(if (partial.steps.size >= 2) "You can fill in step 1 while the rest arrives." else "You can fill this in while the rest arrives.")
            }
        }
    }
}

/** A field whose name, type and label have arrived but not the rest: its label over a blank box. */
@Composable
private fun DraftSkeleton(d: DraftField) {
    val o = Fleet.colors
    Column(Modifier.testTag(draftFieldTag(d.name)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(d.label, style = Fleet.type.textSm, color = o.fgMuted)
        Spacer(Modifier.fillMaxWidth().height(44.dp).background(o.fgMuted.copy(alpha = 0.14f), RoundedCornerShape(6.dp)))
    }
}

/** A field not written yet: a label bar of [labelWidth] over a box. */
@Composable
private fun SkeletonField(labelWidth: Float) {
    val tint = Fleet.colors.fgMuted.copy(alpha = 0.14f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.fillMaxWidth(labelWidth).height(12.dp).background(tint, RoundedCornerShape(6.dp)))
        Spacer(Modifier.fillMaxWidth().height(44.dp).background(tint, RoundedCornerShape(6.dp)))
    }
}
