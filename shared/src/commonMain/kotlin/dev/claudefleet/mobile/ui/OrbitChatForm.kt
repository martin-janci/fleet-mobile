package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ChatFormActions
import dev.claudefleet.mobile.model.FieldProblem
import dev.claudefleet.mobile.model.FormView
import dev.claudefleet.mobile.model.PendingForm
import dev.claudefleet.mobile.model.ReplyField
import dev.claudefleet.mobile.model.ReplyForm
import dev.claudefleet.mobile.model.ReplyStep
import dev.claudefleet.mobile.model.fieldMissing
import dev.claudefleet.mobile.model.visibleFields
import dev.claudefleet.mobile.ui.components.FieldView
import dev.claudefleet.mobile.ui.components.Note
import dev.claudefleet.mobile.ui.components.parseNumber
import dev.claudefleet.mobile.ui.kit.Atom
import dev.claudefleet.mobile.ui.kit.StepBars
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import kotlinx.serialization.json.JsonElement

/*
 * A chat form on the New bar (redesign 14.7, the MobileChatForms board).
 * Nothing runs until the last button. How much room it takes follows its
 * size: one step is a card in the conversation, two or three short steps a
 * sheet, anything longer (or anything holding a secret) the whole screen,
 * one step at a time with "Step x of y" and Back. Once decided it folds to
 * one line: answered, declined with the note, or expired with Ask again.
 * While the form is read it shows Building: the Atom over a skeleton.
 */

/** How much of the screen a form takes. */
enum class FormSize { Inline, Sheet, Full }

/** Fields a step may hold and still count as short, for a sheet. */
private const val SHORT_STEP_FIELDS = 3

fun formSize(spec: ReplyForm): FormSize {
    val fields = spec.steps.flatMap { it.fields }
    return when {
        fields.any { it.type == "secret" } -> FormSize.Full
        spec.steps.size <= 1 -> FormSize.Inline
        spec.steps.size <= 3 && spec.steps.all { it.fields.size <= SHORT_STEP_FIELDS } -> FormSize.Sheet
        else -> FormSize.Full
    }
}

/** The pages a paged form walks: its shown steps, given the answers so far. */
fun formPages(spec: ReplyForm, values: Map<String, JsonElement>): List<Pair<ReplyStep, List<ReplyField>>> =
    visibleFields(spec, values).filter { (step, fields) -> fields.isNotEmpty() || step.intro != null }

/** The first field on a page that still needs an answer. */
fun firstMissing(fields: List<ReplyField>, values: Map<String, JsonElement>): ReplyField? =
    fields.firstOrNull { fieldMissing(it, values[it.name]) }

/** The page's forward button: what is missing, Next, or the form's own last word. */
fun pageButton(missing: ReplyField?, last: Boolean, submit: String?): String = when {
    missing != null -> "Fill in ${missing.label}"
    last -> submit ?: "Send answers"
    else -> "Next"
}

/** The page holding the first field the hub refused, so the answer goes back where it is wrong. */
fun pageOfProblem(pages: List<Pair<ReplyStep, List<ReplyField>>>, problems: List<FieldProblem>): Int? {
    val names = problems.map { it.field }.toSet()
    return pages.indexOfFirst { (_, fields) -> fields.any { it.name in names } }.takeIf { it >= 0 }
}

/** A decided form in one line, as the board folds it. */
fun formOutcomeLine(form: FormView): String {
    val title = form.title.ifBlank { "Form" }
    val by = form.answeredBy?.let { " by $it" }.orEmpty()
    return when (form.state) {
        "answered" -> "✓ $title · answered$by"
        "declined" -> "✕ $title · declined$by"
        "expired" -> "◷ $title · expired"
        "cancelled" -> "✕ $title · withdrawn by the agent"
        else -> "$title · ${form.state}"
    }
}

internal const val FORM_OPEN = "form-open"
internal const val FORM_BUILDING = "form-building"
internal const val FORM_PAGE = "form-page"
internal const val FORM_NEXT = "form-next"
internal const val FORM_BACK = "form-back"
internal const val FORM_ASK_AGAIN = "form-ask-again"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitChatFormCard(
    pending: PendingForm,
    sessionName: String,
    actions: ChatFormActions,
    canAnswer: Boolean,
    open: Boolean,
    onDismiss: () -> Unit,
    /** Ask the agent for an expired form again; null offers nothing. */
    onAskAgain: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val o = Fleet.colors
    val scope = rememberCoroutineScope()
    val model = remember(pending.formId, actions) { AskFormModel(actions, pending.formId, scope) }
    LaunchedEffect(model, open) { model.load() }
    val s by model.state.collectAsState()
    var numberText by remember(pending.formId) { mutableStateOf(mapOf<String, String>()) }
    var paging by remember(pending.formId) { mutableStateOf(false) }
    val form = s.form
    val spec = s.spec

    Surface(
        color = o.bgPane,
        border = BorderStroke(1.dp, if (s.pending) o.accent else o.controlBorder),
        shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag(FORM_CARD),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                form == null -> {
                    if (s.loading) {
                        FormBuilding(pending.title, sessionName)
                    } else {
                        Text(pending.title.ifBlank { "A form" }, style = Fleet.type.textMd, color = o.fg)
                        s.loadFailure?.let { f ->
                            Text(f.title, style = Fleet.type.textSm, color = o.danger)
                            Text(f.body, style = Fleet.type.textSm, color = o.fg2)
                            TextButton(onClick = { model.load() }) { Text("Try again") }
                        }
                    }
                }
                !s.pending -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formOutcomeLine(form), style = Fleet.type.textSm, color = o.fg2, modifier = Modifier.weight(1f).testTag(FORM_OUTCOME))
                        if (form.state == "expired" && onAskAgain != null) {
                            TextButton(onClick = onAskAgain, modifier = Modifier.testTag(FORM_ASK_AGAIN)) { Text("Ask again") }
                        }
                        if (!open) TextButton(onClick = onDismiss) { Text("Dismiss") }
                    }
                    form.note?.takeIf { form.state == "declined" }?.let { Note("“$it”") }
                }
                else -> {
                    Text(form.title.ifBlank { pending.title }, style = Fleet.type.textMd, color = o.fg)
                    Text("asked by $sessionName", style = Fleet.type.textSm, color = o.fgMuted)
                    form.why?.let { Text(it, style = Fleet.type.textSm, color = o.fg2) }
                    if (spec != null) {
                        val size = formSize(spec)
                        if (size == FormSize.Inline) {
                            val enabled = canAnswer && !s.busy
                            spec.intro?.let { Note(it) }
                            for ((step, fields) in visibleFields(spec, s.values)) {
                                step.intro?.let { Note(it) }
                                FormFields(fields, s, numberText, enabled, onNumberText = { n, t -> numberText = numberText + (n to t) }, model = model)
                            }
                            FormActions(s, canAnswer, model) {
                                Button(
                                    onClick = { model.answer() },
                                    enabled = !s.busy && s.ready,
                                    modifier = Modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp).testTag(FORM_ANSWER),
                                ) { Text(if (s.busy) "Sending…" else spec.submit ?: "Send answers") }
                            }
                        } else {
                            val count = formPages(spec, s.values).size
                            Note(if (count == 1) "One step" else "$count steps")
                            FormActions(s, canAnswer, model) {
                                Button(
                                    onClick = { paging = true },
                                    enabled = canAnswer && !s.busy,
                                    modifier = Modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp).testTag(FORM_OPEN),
                                ) { Text("Open form") }
                            }
                            if (paging && s.pending) {
                                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                                ModalBottomSheet(onDismissRequest = { paging = false }, sheetState = sheetState) {
                                    PagedForm(
                                        spec = spec,
                                        s = s,
                                        model = model,
                                        numberText = numberText,
                                        onNumberText = { n, t -> numberText = numberText + (n to t) },
                                        full = size == FormSize.Full,
                                        onClose = { paging = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            s.error?.let { Text(it, style = Fleet.type.textSm, color = o.danger) }
        }
    }
}

/**
 * Building: the Atom and the title while the form is read, over a skeleton
 * of fields, as the desktop's `ChatForm` draws a spec still being written
 * (10.12). The phone gets the spec whole from `ask { get }`, so this is the
 * wait for that read rather than a stream.
 */
@Composable
private fun FormBuilding(title: String, from: String) {
    val o = Fleet.colors
    // A quick read draws the title alone; the loader only once the wait is long enough to see.
    if (!rememberLoaderVisible(true)) {
        Text(title.ifBlank { "A form" }, style = Fleet.type.textMd, color = o.fg)
        return
    }
    Column(modifier = Modifier.testTag(FORM_BUILDING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Atom()
            Text("Building a form · from $from", style = Fleet.type.textSm, color = o.fgMuted)
        }
        if (title.isNotBlank()) Text(title, style = Fleet.type.textMd, color = o.fg)
        for (w in listOf(0.3f, 1f, 0.3f, 1f)) {
            Spacer(
                Modifier
                    .fillMaxWidth(w)
                    .height(if (w < 1f) 10.dp else 22.dp)
                    .background(o.fgMuted.copy(alpha = 0.14f), RoundedCornerShape(4.dp)),
            )
        }
    }
}

/** Decline… with its note, or the row of the form's own buttons. */
@Composable
private fun FormActions(s: AskFormState, canAnswer: Boolean, model: AskFormModel, primary: @Composable () -> Unit) {
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
            primary()
            TextButton(onClick = model::startDecline, enabled = !s.busy) { Text("Decline…") }
        }
    }
}

@Composable
private fun FormFields(
    fields: List<ReplyField>,
    s: AskFormState,
    numberText: Map<String, String>,
    enabled: Boolean,
    onNumberText: (String, String) -> Unit,
    model: AskFormModel,
) {
    for (f in fields) {
        FieldView(
            f,
            s.values[f.name],
            numberText[f.name],
            enabled = enabled,
            onNumberText = { t ->
                onNumberText(f.name, t)
                model.set(f.name, if (t.isBlank()) null else parseNumber(f, t))
            },
            onChange = { model.set(f.name, it) },
        )
        s.problemFor(f.name)?.let { Text(it, style = Fleet.type.textSm, color = Fleet.colors.danger) }
    }
}

/** One step at a time: Step x of y, Back, and a forward button that names what is missing. */
@Composable
private fun PagedForm(
    spec: ReplyForm,
    s: AskFormState,
    model: AskFormModel,
    numberText: Map<String, String>,
    onNumberText: (String, String) -> Unit,
    full: Boolean,
    onClose: () -> Unit,
) {
    val o = Fleet.colors
    val pages = formPages(spec, s.values)
    var at by remember(spec) { mutableIntStateOf(0) }
    // A refused answer goes back to the page that holds it.
    LaunchedEffect(s.problems) { pageOfProblem(pages, s.problems)?.let { at = it } }
    val index = at.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val page = pages.getOrNull(index)
    val last = index >= pages.size - 1
    val missing = page?.let { firstMissing(it.second, s.values) }
    Column(
        modifier = (if (full) Modifier.fillMaxHeight() else Modifier)
            .fillMaxWidth()
            .padding(horizontal = OrbitTokens.spacing("phone-gutter").dp)
            .testTag(FORM_PAGE),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.title, style = Fleet.type.textMd, color = o.fg, modifier = Modifier.weight(1f))
            Text("Step ${index + 1} of ${pages.size}", style = Fleet.type.textSm, color = o.fgMuted)
        }
        StepBars(step = index + 1, total = pages.size, gutter = false)
        Column(
            modifier = (if (full) Modifier.weight(1f) else Modifier).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (index == 0) spec.intro?.let { Note(it) }
            if (page != null) {
                Text(page.first.title, style = Fleet.type.textSm, color = o.fg2)
                page.first.intro?.let { Note(it) }
                FormFields(page.second, s, numberText, enabled = !s.busy, onNumberText = onNumberText, model = model)
            }
        }
        s.error?.let { Text(it, style = Fleet.type.textSm, color = o.danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { if (index == 0) onClose() else at = index - 1 },
                enabled = !s.busy,
                modifier = Modifier.testTag(FORM_BACK),
            ) { Text(if (index == 0) "Close" else "Back") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { if (last) model.answer() else at = index + 1 },
                enabled = !s.busy && missing == null && (!last || s.ready),
                modifier = Modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp).testTag(FORM_NEXT),
            ) { Text(if (s.busy) "Sending…" else pageButton(missing, last, spec.submit)) }
        }
        Spacer(Modifier.padding(bottom = 12.dp))
    }
}
