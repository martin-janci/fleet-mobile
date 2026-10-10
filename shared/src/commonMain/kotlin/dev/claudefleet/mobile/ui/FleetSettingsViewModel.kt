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
import dev.claudefleet.mobile.model.SettingWrite
import dev.claudefleet.mobile.model.inWords
import dev.claudefleet.mobile.model.parseTyped

/** A change that needs the person's yes first: the setting's own sentence.
 *  [thenSave]: it is one of the Save bar's staged values, and yes saves them all. */
data class PendingConfirm(
    val key: String,
    val value: String,
    val label: String,
    val message: String,
    val thenSave: Boolean = false,
)

/** A switch (or other toggle) the hub just wrote: Undo puts [before] back. */
data class SettingUndo(val key: String, val before: String, val label: String, val words: String)

/**
 * The kinds a person types or picks one of, staged for the page's one Save
 * bar (claude-fleet G1.5, the desktop's `BATCHED` widgets: number, duration,
 * select, text). A switch and a set of ticks still write at once, with Undo.
 */
val BATCHED_KINDS: Set<String> = setOf("secs", "int", "text", "time_range", "choice")

/** "1 change", "3 changes": the Save bar's count. */
internal fun saveBarCount(n: Int): String = if (n == 1) "1 change" else "$n changes"

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
    /**
     * The settings could not be read: drawn on Settings home with Retry,
     * rather than leaving the hub's groups silently missing.
     */
    val loadError: Friendly? = null,
    /** A field offers its History (the hub serves `setting_history`). */
    val historyAvailable: Boolean = false,
    /** The setting whose history is open, and its writes (null while they load). */
    val history: Pair<String, List<SettingWrite>?>? = null,
    /** The open history could not be read: the dialog stays open and says so. */
    val historyError: Friendly? = null,
    /**
     * Typed and picked values not saved yet, in the order they were staged,
     * as the hub would store them (G1.5): the Save bar writes them together.
     */
    val staged: Map<String, String> = emptyMap(),
    /** A typed text that is not a value yet ("abc" in a number), by key: it
     *  counts as a change and holds the Save bar until it is fixed. */
    val stageProblems: Map<String, String> = emptyMap(),
    /** The Save bar is writing [staged]. */
    val saving: Boolean = false,
    /** Bumped per key when Reset or Discard puts a typed field back, so the
     *  field starts again from the value it now shows. */
    val fieldEpoch: Map<String, Int> = emptyMap(),
    /** The toggle just written, with Undo. */
    val undo: SettingUndo? = null,
) {
    val page: Page? get() = pages.firstOrNull { it.id == openPage }

    /** What the Save bar counts: "2 changes". */
    val changeCount: Int get() = (staged.keys + stageProblems.keys).size

    /** Save writes something and nothing typed is still not a value. */
    val canSave: Boolean get() = staged.isNotEmpty() && stageProblems.isEmpty() && !saving

    /** The value a row shows: the staged one while there is one, else the hub's. */
    fun shown(key: String): String = staged[key] ?: values[key] ?: descriptors[key]?.value.orEmpty()

    /** [key] goes through the Save bar rather than writing at once. */
    fun batched(key: String): Boolean = descriptors[key]?.kind?.type in BATCHED_KINDS

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
    /** The hub serves `setting_history` to this device. */
    historyAvailable: Boolean = false,
    /** Every effective value, each time the hub answers them: the notifier keeps the notify.* ones. */
    private val onValues: (Map<String, String>) -> Unit = {},
) {
    private val _state = MutableStateFlow(FleetSettingsUiState(historyAvailable = historyAvailable))

    /** One setting's writes, newest first, in a dialog until [closeHistory]. */
    fun showHistory(key: String): Job = scope.launch {
        if (!_state.value.historyAvailable) return@launch
        _state.update { it.copy(history = key to null, historyError = null) }
        try {
            val rows = actions.history(key)
            _state.update { if (it.history?.first == key) it.copy(history = key to rows) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Kept open with the failure in it: closing the dialog and
            // storing a sentence nothing draws read as "no history at all".
            _state.update { if (it.history?.first == key) it.copy(historyError = friendly(t)) else it }
        }
    }

    fun closeHistory() {
        _state.update { it.copy(history = null, historyError = null) }
    }

    /** Whether the hub serves `setting_history` — learned with its tool list, after this is made. */
    fun setHistoryAvailable(on: Boolean) {
        _state.update { it.copy(historyAvailable = on) }
    }
    val state: StateFlow<FleetSettingsUiState> = _state.asStateFlow()

    /** Read the pages, the settings and what waits for review. */
    fun load(): Job = scope.launch {
        _state.update { it.copy(loading = true, error = null, loadError = null) }
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
                    loadError = null,
                    pages = offeredPages(pages),
                    descriptors = descs.associateBy { d -> d.key },
                    values = descs.associate { d -> d.key to d.value },
                    proposals = p?.proposals.orEmpty(),
                    canWrite = credentialCanWrite && p?.canWrite == true,
                    // A staged value the hub now holds anyway, or for a
                    // setting it no longer describes, is no change to save.
                    staged = it.staged.filter { (k, v) -> descs.any { d -> d.key == k && d.value != v } },
                    stageProblems = it.stageProblems.filterKeys { k -> descs.any { d -> d.key == k } },
                )
            }
            onValues(_state.value.values)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _state.update { it.copy(loading = false, loadError = friendly(t)) }
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
        if (c.thenSave) saveNow() else write(c.key, c.value)
    }

    /**
     * Stage [value] for [key] on the Save bar (G1.5): nothing is sent until
     * [save]. Staging the hub's own value back takes the change away. A key
     * that writes at once ([FleetSettingsUiState.batched] false) is [set].
     */
    fun stage(key: String, value: String) {
        val s = _state.value
        if (!s.editable(key) || s.saving) return
        if (!s.batched(key)) return set(key, value)
        val stored = s.values[key] ?: s.descriptors.getValue(key).value
        _state.update {
            it.copy(
                staged = if (value == stored) it.staged - key else it.staged + (key to value),
                stageProblems = it.stageProblems - key,
                fieldErrors = it.fieldErrors - key,
            )
        }
    }

    /** Text typed into a field: staged when it is a value, else held as a
     *  problem under the field, which keeps Save from writing a half-typed one. */
    fun type(key: String, typed: String) {
        val s = _state.value
        if (!s.editable(key) || s.saving) return
        val d = s.descriptors.getValue(key)
        d.parseTyped(typed).fold(
            onSuccess = { stage(key, it) },
            onFailure = { e ->
                _state.update {
                    it.copy(
                        staged = it.staged - key,
                        stageProblems = it.stageProblems + (key to "${d.label}: ${e.message}"),
                        fieldErrors = it.fieldErrors - key,
                    )
                }
            },
        )
    }

    /** "changed from … · Reset": put the default back — staged for a typed
     *  value, written at once (with Undo) for a toggle. */
    fun reset(key: String) {
        val s = _state.value
        val d = s.descriptors[key] ?: return
        if (!s.editable(key)) return
        if (s.batched(key)) {
            stage(key, d.default)
            _state.update { it.copy(fieldEpoch = it.fieldEpoch + (key to (it.fieldEpoch[key] ?: 0) + 1)) }
        } else {
            set(key, d.default)
        }
    }

    /** The Save bar's Discard: every staged value and typed problem goes; the fields show the hub's values again. */
    fun discard() {
        val s = _state.value
        if (s.saving) return
        val keys = s.staged.keys + s.stageProblems.keys
        _state.update {
            it.copy(
                staged = emptyMap(),
                stageProblems = emptyMap(),
                fieldErrors = it.fieldErrors - keys,
                fieldEpoch = it.fieldEpoch + keys.associateWith { k -> (it.fieldEpoch[k] ?: 0) + 1 },
            )
        }
    }

    /**
     * The Save bar's Save: every staged value, in the order it was staged.
     * A written one leaves the bar; one the hub refuses stays staged with the
     * refusal under its field. One that needs confirming asks first, once,
     * for the lot.
     */
    fun save(): Job? {
        val s = _state.value
        if (!s.canSave || !s.canWrite) return null
        val ask = s.staged.entries.firstNotNullOfOrNull { (k, v) ->
            s.descriptors[k]?.takeIf { d -> d.danger.confirms && v != d.default }?.let { d -> k to (v to d) }
        }
        if (ask != null) {
            val (k, vd) = ask
            val (v, d) = vd
            _state.update { it.copy(confirm = PendingConfirm(k, v, d.label, d.danger.message.orEmpty(), thenSave = true)) }
            return null
        }
        return saveNow()
    }

    private fun saveNow(): Job {
        val batch = _state.value.staged
        _state.update { it.copy(saving = true, fieldErrors = it.fieldErrors - batch.keys) }
        return scope.launch {
            try {
                for ((key, value) in batch) {
                    try {
                        val all = actions.set(key, value)
                        _state.update {
                            it.copy(
                                values = it.values + all,
                                // Still the value that was sent: a newer one typed meanwhile stays.
                                staged = if (it.staged[key] == value) it.staged - key else it.staged,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        _state.update { it.copy(fieldErrors = it.fieldErrors + (key to friendly(t).body)) }
                    }
                }
            } finally {
                _state.update { it.copy(saving = false) }
            }
            onValues(_state.value.values)
        }
    }

    /** Put back the toggle just written. */
    fun undo() {
        val u = _state.value.undo ?: return
        _state.update { it.copy(undo = null) }
        if (_state.value.editable(u.key) && u.key !in _state.value.busy) write(u.key, u.before, offerUndo = false)
    }

    fun dismissUndo() {
        _state.update { it.copy(undo = null) }
    }

    fun cancelConfirm() {
        _state.update { it.copy(confirm = null) }
    }

    /** Show [message] against [key] without sending anything: a number the
     *  phone could already tell was not one. */
    fun refuse(key: String, message: String) {
        _state.update { it.copy(fieldErrors = it.fieldErrors + (key to message)) }
    }

    private fun write(key: String, value: String, offerUndo: Boolean = true) {
        val before = _state.value.values[key]
        _state.update { it.copy(busy = it.busy + key, fieldErrors = it.fieldErrors - key) }
        scope.launch {
            try {
                val all = actions.set(key, value)
                _state.update {
                    val d = it.descriptors[key]
                    val undo = if (offerUndo && before != null && d != null && !it.batched(key)) {
                        SettingUndo(key, before, d.label, d.inWords(all[key] ?: value))
                    } else {
                        it.undo?.takeIf { u -> u.key != key }
                    }
                    it.copy(values = it.values + all, busy = it.busy - key, undo = undo)
                }
                onValues(_state.value.values)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update { it.copy(busy = it.busy - key, fieldErrors = it.fieldErrors + (key to friendly(t).body)) }
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
                if (descs != null) onValues(_state.value.values)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update { it.copy(busy = it.busy - tag, error = friendly(t).body) }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** Put the failed-read banner away; Retry stays on Settings home until a read works. */
    fun dismissLoadError() {
        _state.update { it.copy(loadError = null) }
    }
}
