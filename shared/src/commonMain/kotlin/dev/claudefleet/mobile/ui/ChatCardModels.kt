package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ChatFormActions
import dev.claudefleet.mobile.data.FleetSettingsActions
import dev.claudefleet.mobile.model.FieldProblem
import dev.claudefleet.mobile.model.FormPick
import dev.claudefleet.mobile.model.FormView
import dev.claudefleet.mobile.model.ReplyForm
import dev.claudefleet.mobile.model.SettingDescriptor
import dev.claudefleet.mobile.model.SettingProposal
import dev.claudefleet.mobile.model.carryDraftAnswers
import dev.claudefleet.mobile.model.fieldMissing
import dev.claudefleet.mobile.model.fieldProblems
import dev.claudefleet.mobile.model.formAnswers
import dev.claudefleet.mobile.model.formDefaults
import dev.claudefleet.mobile.model.formPicks
import dev.claudefleet.mobile.model.inWords
import dev.claudefleet.mobile.model.readAskForm
import dev.claudefleet.mobile.model.visibleFields
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/*
 * The state behind two cards that act on the hub rather than on the composer
 * (step 10.8): a session's waiting chat form, and a settings change an agent
 * proposed. Plain models so they test without Compose; the cards in
 * `components/RichCards.kt` and `ChatFormCard.kt` draw them.
 */

/** What a chat form card draws. */
data class AskFormState(
    val loading: Boolean = true,
    val form: FormView? = null,
    /** The spec, read; null while loading, or when the phone cannot draw it. */
    val spec: ReplyForm? = null,
    val values: Map<String, JsonElement> = emptyMap(),
    val busy: Boolean = false,
    /** The hub's refusal of an answer, field by field. */
    val problems: List<FieldProblem> = emptyList(),
    val error: String? = null,
    /** The form could not be read: its title and body are drawn with Try again (re-runs [AskFormModel.load]). */
    val loadFailure: Friendly? = null,
    val declining: Boolean = false,
    val note: String = "",
    /** Each select's proposed option ([formPicks]), until the person changes it. */
    val picks: Map<String, FormPick> = emptyMap(),
) {
    val pending: Boolean get() = form?.state == "pending"

    /** Every shown required field has an answer. */
    val ready: Boolean
        get() = spec != null && visibleFields(spec, values).flatMap { it.second }.none { fieldMissing(it, values[it.name]) }

    fun problemFor(field: String): String? = problems.firstOrNull { it.field == field }?.problem
}

/**
 * One chat form, read with `ask { get }` and decided with `ask { answer }` or
 * `ask { decline }`. [canAnswer] is false where this device may not drive the
 * session: the form is still shown, with nothing to press.
 *
 * [seed] is what the person filled in while the agent was still writing the
 * form (`ask { draft }`, `model/FormDraft.kt`): it starts the form's answers
 * over the defaults, where it still fits ([carryDraftAnswers]).
 */
class AskFormModel(
    private val actions: ChatFormActions,
    private val formId: String,
    private val scope: CoroutineScope,
    private val seed: Map<String, JsonElement> = emptyMap(),
) {
    private val _state = MutableStateFlow(AskFormState())
    val state: StateFlow<AskFormState> = _state.asStateFlow()

    fun load(): Job = scope.launch {
        _state.update { it.copy(loading = true, error = null, loadFailure = null) }
        try {
            val form = actions.get(formId)
            val spec = readAskForm(form.spec)
            _state.update {
                it.copy(
                    loading = false,
                    form = form,
                    spec = spec,
                    picks = spec?.let { sp -> formPicks(sp, form.proposal) }.orEmpty(),
                    values = spec?.let { sp -> startValues(sp, formPicks(sp, form.proposal)) }.orEmpty(),
                    error = if (spec == null && form.state == "pending") "This form cannot be drawn on the phone; answer it on the desktop." else null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _state.update { it.copy(loading = false, loadFailure = friendly(t)) }
        }
    }

    fun set(name: String, value: JsonElement?) {
        _state.update {
            it.copy(
                values = if (value == null) it.values - name else it.values + (name to value),
                problems = it.problems.filter { p -> p.field != name },
            )
        }
    }

    fun answer(): Job? {
        val s = _state.value
        val spec = s.spec ?: return null
        if (s.busy || !s.pending || !s.ready) return null
        _state.update { it.copy(busy = true, error = null, problems = emptyList()) }
        return scope.launch {
            try {
                val form = actions.answer(formId, formAnswers(spec, s.values))
                _state.update { it.copy(busy = false, form = form, values = withoutSecrets(spec, it.values)) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val problems = (t as? HubError.Tool)?.let { fieldProblems(it.details) }.orEmpty()
                _state.update {
                    it.copy(
                        busy = false,
                        // A secret is typed again rather than kept in memory past its one send.
                        values = withoutSecrets(spec, it.values),
                        problems = problems,
                        error = if (problems.isEmpty()) friendly(t).body else unplaced(spec, problems),
                    )
                }
            }
        }
    }

    /**
     * Change on a proposed choice: the proposal goes, and with it the pick
     * it made, so the person chooses. It does not come back for this card.
     */
    fun changePick(name: String) {
        _state.update {
            val pick = it.picks[name] ?: return@update it
            val chosen = (it.values[name] as? JsonPrimitive)?.content == pick.value
            it.copy(picks = it.picks - name, values = if (chosen) it.values - name else it.values)
        }
    }

    /** The defaults, then each proposal where the field has none, then what was typed while the form was drafted. */
    private fun startValues(spec: ReplyForm, picks: Map<String, FormPick>): Map<String, JsonElement> {
        val defaults = formDefaults(spec)
        val proposed = picks.values.filter { it.field !in defaults }.associate { it.field to (JsonPrimitive(it.value) as JsonElement) }
        return defaults + proposed + carryDraftAnswers(spec, seed)
    }

    fun startDecline() = _state.update { it.copy(declining = true) }
    fun keep() = _state.update { it.copy(declining = false) }
    fun note(text: String) = _state.update { it.copy(note = text.take(500)) }

    fun decline(): Job? {
        val s = _state.value
        if (s.busy || !s.pending) return null
        _state.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try {
                val form = actions.decline(formId, s.note.trim().ifEmpty { null })
                _state.update { it.copy(busy = false, declining = false, form = form) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, error = friendly(t).body) }
            }
        }
    }

    private fun withoutSecrets(spec: ReplyForm, values: Map<String, JsonElement>): Map<String, JsonElement> {
        val secrets = spec.steps.flatMap { it.fields }.filter { it.type == "secret" }.map { it.name }.toSet()
        return values - secrets
    }

    /** Problems for fields not on the form, which no field can show. */
    private fun unplaced(spec: ReplyForm, problems: List<FieldProblem>): String? {
        val names = spec.steps.flatMap { it.fields }.map { it.name }.toSet()
        return problems.filter { it.field !in names }.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.field}: ${it.problem}" }
    }
}

/** What a settings card draws. */
data class SettingCardState(
    val loaded: Boolean = false,
    /** The proposal while it waits; null once it was applied or rejected. */
    val proposal: SettingProposal? = null,
    val descriptor: SettingDescriptor? = null,
    /** This device may decide it: the hub's `can_write` and a full credential. */
    val canWrite: Boolean = false,
    val busy: Boolean = false,
    val confirming: Boolean = false,
    val failure: String? = null,
    /** The proposal could not be read: drawn as title and body with Try again (re-runs [SettingCardModel.load]). */
    val loadFailure: Friendly? = null,
    /** applied | later | null. */
    val done: String? = null,
    /** What this card applied, for the receipt. */
    val applied: Pair<String, String>? = null,
) {
    val label: String? get() = descriptor?.label ?: proposal?.key ?: applied?.first

    fun words(value: String): String = descriptor?.inWords(value) ?: value

    /** The value moved since the agent proposed it. */
    val moved: Boolean get() = proposal != null && proposal.current != proposal.before

    /** What Apply asks first: the setting's own warning when it has one. */
    val question: String
        get() {
            val p = proposal ?: return ""
            val change = "${label ?: p.key}: ${words(p.current)} → ${words(p.value)}."
            val d = descriptor
            return if (d != null && d.danger.confirms && d.danger.message != null) "$change ${d.danger.message}" else change
        }
}

/**
 * A `setting` block's proposal (claude-fleet step 10.3): the key and both
 * values come from `setting_proposals`, never from the block. Apply asks
 * first and decides the one proposal through `decide_setting_proposals`;
 * Not now writes nothing.
 */
class SettingCardModel(
    private val actions: FleetSettingsActions,
    private val proposalId: Long,
    private val scope: CoroutineScope,
    /** A full credential, and the hub lists `decide_setting_proposals`. */
    private val mayDecide: Boolean,
) {
    private val _state = MutableStateFlow(SettingCardState())
    val state: StateFlow<SettingCardState> = _state.asStateFlow()

    fun load(): Job = scope.launch {
        _state.update { it.copy(loaded = false, loadFailure = null) }
        try {
            val pending = actions.pending()
            val p = pending.proposals.firstOrNull { it.id == proposalId }
            val d = p?.let { prop ->
                try {
                    actions.describe().firstOrNull { it.key == prop.key }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    null
                }
            }
            _state.update { it.copy(loaded = true, proposal = p, descriptor = d, canWrite = mayDecide && pending.canWrite) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _state.update { it.copy(loaded = true, loadFailure = friendly(t)) }
        }
    }

    fun askApply() = _state.update { if (it.proposal != null && it.canWrite && !it.busy) it.copy(confirming = true) else it }
    fun cancel() = _state.update { it.copy(confirming = false) }
    fun later() = _state.update { it.copy(done = "later") }

    fun apply(): Job? {
        val s = _state.value
        val p = s.proposal ?: return null
        if (s.busy || !s.canWrite) return null
        _state.update { it.copy(confirming = false, busy = true, failure = null) }
        return scope.launch {
            try {
                val r = actions.decide(listOf(p.id), emptyList())
                val failed = r.failed.firstOrNull { it.id == p.id }
                _state.update {
                    if (failed != null) {
                        it.copy(busy = false, failure = failed.error)
                    } else {
                        it.copy(busy = false, done = "applied", applied = p.key to p.value, proposal = null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, failure = friendly(t).body) }
            }
        }
    }
}

/** "an agent (mercury)": who proposed a change, the desktop's `whoWords`. */
fun whoWords(kind: String, detail: String?): String {
    val base = when (kind) {
        "agent" -> "an agent"
        "person" -> "a person"
        else -> "fleet"
    }
    return if (detail.isNullOrBlank()) base else "$base ($detail)"
}
