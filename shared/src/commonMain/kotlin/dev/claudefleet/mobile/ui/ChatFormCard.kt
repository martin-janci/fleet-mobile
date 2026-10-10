package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ChatFormActions
import dev.claudefleet.mobile.model.PendingForm
import dev.claudefleet.mobile.model.formOutcome
import dev.claudefleet.mobile.model.visibleFields
import dev.claudefleet.mobile.ui.components.FieldView
import dev.claudefleet.mobile.ui.components.Note
import dev.claudefleet.mobile.ui.components.parseNumber
import kotlinx.serialization.json.JsonElement

internal const val FORM_CARD = "form-card"
internal const val FORM_ANSWER = "form-answer"
internal const val FORM_DECLINE = "form-decline"
internal const val FORM_OUTCOME = "form-outcome"

/**
 * The chat form a session's agent waits on (`ask`, step 10.8), where the
 * answer goes: between the conversation and the composer. Who asks, why,
 * the fields, Answer and Decline, as the desktop's `forms/FormCard.svelte`.
 *
 * [actions] is null where the hub does not list `ask` for this device (a
 * readonly pairing): the card then only says a form waits.
 */
@Composable
fun ChatFormCard(
    pending: PendingForm,
    sessionName: String,
    actions: ChatFormActions?,
    /** This device may drive the session; false draws the form with nothing to press. */
    canAnswer: Boolean,
    /**
     * False once the row no longer carries the form (answered here or on
     * the desktop, withdrawn, expired): the card reads it once more and
     * says how it ended until [onDismiss].
     */
    open: Boolean = true,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * The New bar's form (redesign 14.7, `OrbitChatForm.kt`): sized to the
     * form, paged one step at a time, folded to one line once decided.
     */
    orbit: Boolean = false,
    /** Ask the agent for an expired form again (New bar); null offers nothing. */
    onAskAgain: (() -> Unit)? = null,
    /**
     * What the person filled in while the agent still wrote the form
     * (`ChatFormDraftCard`): the form starts from it where it still fits.
     * Read once, when the card first reads the form.
     */
    seed: Map<String, JsonElement> = emptyMap(),
    /** What Control's chat lends its forms ([FormContext]): the work an answer started, Jev's host; null lends none. */
    formContext: FormContext? = null,
) {
    if (orbit && actions != null) {
        OrbitChatFormCard(pending, sessionName, actions, canAnswer, open, onDismiss, onAskAgain, modifier, seed, formContext)
        return
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag(FORM_CARD),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (actions == null) {
                if (!open) return@Column
                Text(pending.title.ifBlank { "A form" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Note("$sessionName waits on this form. Answer it on a device that may write to the session.")
                return@Column
            }
            val scope = rememberCoroutineScope()
            val model = remember(pending.formId, actions) { AskFormModel(actions, pending.formId, scope, seed) }
            LaunchedEffect(model, open) { model.load() }
            val s by model.state.collectAsState()
            var numberText by remember(pending.formId) { mutableStateOf(mapOf<String, String>()) }
            val form = s.form
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    form?.title ?: pending.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (open) {
                    Text("asked by $sessionName", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                } else {
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
            if (form == null) {
                if (s.loading) {
                    Note("Reading the form…")
                } else {
                    s.loadFailure?.let { f ->
                        Text(f.title, style = MaterialTheme.typography.bodyMedium, color = colors.error)
                        Text(f.body, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { model.load() }) { Text("Try again") }
                    }
                }
            } else if (!s.pending) {
                Text("Form ${formOutcome(form)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(FORM_OUTCOME))
            } else {
                form.why?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                val spec = s.spec
                if (spec != null) {
                    spec.intro?.let { Note(it) }
                    val enabled = canAnswer && !s.busy
                    for ((step, fields) in visibleFields(spec, s.values)) {
                        if (spec.steps.size > 1) Text(step.title.uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        step.intro?.let { Note(it) }
                        for (f in fields) {
                            FieldView(
                                f,
                                s.values[f.name],
                                numberText[f.name],
                                enabled = enabled,
                                onNumberText = { t ->
                                    numberText = numberText + (f.name to t)
                                    model.set(f.name, if (t.isBlank()) null else parseNumber(f, t))
                                },
                                onChange = { model.set(f.name, it) },
                            )
                            s.problemFor(f.name)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
                        }
                    }
                    if (!canAnswer) {
                        Note("This device cannot answer for this session.")
                    } else if (s.declining) {
                        OutlinedTextField(
                            value = s.note,
                            onValueChange = model::note,
                            label = { Text("Why not (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { model.decline() }, enabled = !s.busy, modifier = Modifier.testTag(FORM_DECLINE)) { Text("Decline") }
                            TextButton(onClick = model::keep, enabled = !s.busy) { Text("Keep") }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { model.answer() },
                                enabled = !s.busy && s.ready,
                                modifier = Modifier.testTag(FORM_ANSWER),
                            ) { Text(spec.submit ?: "Send answers") }
                            TextButton(onClick = model::startDecline, enabled = !s.busy) { Text("Decline") }
                        }
                    }
                }
            }
            s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        }
    }
}
