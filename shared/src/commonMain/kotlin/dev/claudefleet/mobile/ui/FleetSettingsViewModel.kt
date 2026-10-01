package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetSettingsActions
import dev.claudefleet.mobile.model.Page
import dev.claudefleet.mobile.model.SettingDescriptor
import dev.claudefleet.mobile.model.SettingProposal
import dev.claudefleet.mobile.model.editableOnPhone
import dev.claudefleet.mobile.model.offeredPages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A change that needs the person's yes first: the setting's own sentence. */
data class PendingConfirm(val key: String, val value: String, val label: String, val message: String)

/** What the fleet's settings pages draw. */
data class FleetSettingsUiState(
    val loading: Boolean = false,
    /** Loaded at least once: the pages and the values below are the hub's. */
    val loaded: Boolean = false,
    val pages: List<Page> = emptyList(),
    val descriptors: Map<String, SettingDescriptor> = emptyMap(),
    /** Every setting's effective value, from the hub. */
    val values: Map<String, String> = emptyMap(),
    val proposals: List<SettingProposal> = emptyList(),
    /**
     * This device may change the settings and decide proposals: a `full`
     * credential **and** the hub's `can_write` — the operator trusts it.
     */
    val canWrite: Boolean = false,
    /** The page on screen; null is the list of pages. */
    val openPage: String? = null,
    /** A write in flight, by key; a proposal decision by `#<id>`. */
    val busy: Set<String> = emptySet(),
    /** The hub's refusal of one field's write, by key. */
    val fieldErrors: Map<String, String> = emptyMap(),
    val confirm: PendingConfirm? = null,
    val error: String? = null,
) {
    val page: Page? get() = pages.firstOrNull { it.id == openPage }

    /** A field this device edits: it may write, the kind is one the phone
     *  draws a control for, and no other subsystem owns the key. */
    fun editable(key: String): Boolean {
        val d = descriptors[key] ?: return false
        return canWrite && !d.readOnlyHere && d.editableOnPhone
    }

    fun proposalFor(key: String): SettingProposal? = proposals.firstOrNull { it.key == key }
}

/**
 * The fleet's settings on the phone (claude-fleet declarative pages P6): the
 * hub's own page specs, drawn with the hub's own values.
 *
 * **Writes go through the hub and only there.** Nothing is changed on the
 * phone first: a switch shows the stored value until the hub answers with
 * every effective value, which then replaces the lot — so a value the hub
 * normalised (a trimmed text, a clamped number) is what the screen shows.
 * A refusal stays next to its field.
 *
 * **Who may write is the hub's to say.** A `readonly` credential never writes
 * (its token is not even offered `set_setting`); a `full` one writes only when
 * `setting_proposals` answers `can_write` — the hub's operator trusts this
 * device. Both are checked here so the screen never offers a control the hub
 * would refuse.
 */
class FleetSettingsViewModel(
    private val actions: FleetSettingsActions,
    private val scope: CoroutineScope,
    /** The credential's own permission ([dev.claudefleet.mobile.store.Credentials.canWrite]). */
    private val credentialCanWrite: Boolean,
) {
    private val _state = MutableStateFlow(FleetSettingsUiState())
    val state: StateFlow<FleetSettingsUiState> = _state.asStateFlow()

    /** Read the pages, the settings and what waits for review. */
    fun load(): Job = scope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try {
            // `coroutineScope { }` is what makes the catch below able to
            // contain a failure. Without it the `launch` job is the `async`
            // children's parent, so a failed `pages()` or `describe()`
            // cancels it — and the scope is `rememberCoroutineScope`'s plain
            // Job, not a SupervisorJob, with no CoroutineExceptionHandler
            // anywhere in production — so one refused settings read tore down
            // the whole shared work scope and reached the uncaught handler.
            // Same shape as TicketsViewModel's own fan-out.
            val (pages, descs, p) = coroutineScope {
                val pages = async { actions.pages() }
                val described = async { actions.describe() }
                // A readonly token may read proposals; a hub without the tool,
                // or one that refuses it, leaves the review empty and this
                // device read-only rather than failing the whole screen.
                val pending = async { runCatching { actions.pending() }.getOrNull() }
                // every await inside the boundary, so none of them can escape
                Triple(pages.await(), described.await(), pending.await())
            }
            _state.update {
                it.copy(
                    loading = false,
                    loaded = true,
                    pages = offeredPages(pages),
                    descriptors = descs.associateBy { d -> d.key },
                    values = descs.associate { d -> d.key to d.value },
                    proposals = p?.proposals.orEmpty(),
                    canWrite = credentialCanWrite && p?.canWrite == true,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _state.update { it.copy(loading = false, error = explain(t)) }
        }
    }

    fun open(pageId: String) {
        _state.update { it.copy(openPage = pageId, fieldErrors = emptyMap()) }
    }

    /** Back from a page to the list. False when already there. */
    fun back(): Boolean {
        if (_state.value.openPage == null) return false
        _state.update { it.copy(openPage = null, fieldErrors = emptyMap()) }
        return true
    }

    /**
     * Change [key] to [value]. A setting that needs confirming asks first
     * ([PendingConfirm]); a value it already has is not sent.
     */
    fun set(key: String, value: String) {
        val s = _state.value
        if (!s.editable(key) || key in s.busy) return
        if (s.values[key] == value) return
        val d = s.descriptors.getValue(key)
        if (d.danger.confirms && value != d.default) {
            _state.update { it.copy(confirm = PendingConfirm(key, value, d.label, d.danger.message.orEmpty())) }
            return
        }
        write(key, value)
    }

    fun confirm() {
        val c = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        write(c.key, c.value)
    }

    fun cancelConfirm() {
        _state.update { it.copy(confirm = null) }
    }

    /** Show [message] against [key] without sending anything: a number the
     *  phone could already tell was not one. */
    fun refuse(key: String, message: String) {
        _state.update { it.copy(fieldErrors = it.fieldErrors + (key to message)) }
    }

    private fun write(key: String, value: String) {
        _state.update { it.copy(busy = it.busy + key, fieldErrors = it.fieldErrors - key) }
        scope.launch {
            try {
                val all = actions.set(key, value)
                _state.update { it.copy(values = it.values + all, busy = it.busy - key) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update { it.copy(busy = it.busy - key, fieldErrors = it.fieldErrors + (key to explain(t))) }
            }
        }
    }

    /** Apply or reject one proposal, then read the values and the review again. */
    fun decide(proposalId: Long, apply: Boolean): Job? {
        val s = _state.value
        val tag = "#$proposalId"
        if (!s.canWrite || tag in s.busy) return null
        _state.update { it.copy(busy = it.busy + tag) }
        return scope.launch {
            try {
                val d = actions.decide(
                    accept = if (apply) listOf(proposalId) else emptyList(),
                    reject = if (apply) emptyList() else listOf(proposalId),
                )
                val failed = d.failed.firstOrNull()?.error
                val descs = if (d.applied.isNotEmpty()) actions.describe() else null
                val pending = runCatching { actions.pending() }.getOrNull()
                _state.update {
                    it.copy(
                        busy = it.busy - tag,
                        values = descs?.associate { x -> x.key to x.value } ?: it.values,
                        proposals = pending?.proposals ?: it.proposals.filterNot { p -> p.id == proposalId },
                        error = failed,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update { it.copy(busy = it.busy - tag, error = explain(t)) }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }
}
