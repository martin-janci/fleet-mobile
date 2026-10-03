package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetSettingsActions
import dev.claudefleet.mobile.model.Page
import dev.claudefleet.mobile.model.SettingDescriptor
import dev.claudefleet.mobile.model.SettingProposal
import dev.claudefleet.mobile.model.editableOnPhone
import dev.claudefleet.mobile.model.offeredPages
import dev.claudefleet.mobile.net.HubError
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

/**
 * A change that needs the person's yes first: the setting's own sentence.
 *
 * [proposalId] is set when the change is a PROPOSAL being applied rather than a
 * field being typed. The confirm gate lived in `set()` only, so `decide` — the
 * screen's second write path — applied a confirm-level change with no question
 * asked. The hub refuses to record a proposal for a confirm-level setting
 * (`Danger::Confirm` implies `AiPolicy::Never`), so the window is narrow: a
 * proposal left for a key to which the hub LATER adds `.danger(...)` survives
 * the upgrade, because only decided rows are pruned. Narrow is not none, and
 * the desktop's `ReviewApply` gates it.
 */
data class PendingConfirm(
    val key: String,
    val value: String,
    val label: String,
    val message: String,
    val proposalId: Long? = null,
)

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
     * Bumped whenever a write ENDED without changing `values` — a no-op the
     * view model dropped. The field's draft is keyed on it, so the text goes
     * back to the hub's value instead of keeping a spelling of it that is not.
     */
    val settled: Int = 0,
    /**
     * The values on screen may be behind the hub: a decision was applied and
     * the re-read of the settings failed. The screen says so rather than
     * showing a stale number as current.
     */
    val valuesStale: Boolean = false,
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
                // or one that REFUSES it, leaves the review empty and this
                // device read-only rather than failing the whole screen.
                //
                // Only a refusal: `HubError.Tool` (an unknown tool, or one
                // that answered `E_FORBIDDEN`) is the hub saying "not for
                // you". A timeout, a reset, a 401 or a 403 is not that, and
                // catching those here read as "the hub refuses review" — so a
                // trusted `full` device went read-only, showed the operator
                // the trust command they had already run, and (nothing
                // re-reads, G4) stayed that way for the life of the process.
                // Anything else reaches the outer catch and is reported.
                val pending = async {
                    try {
                        actions.pending()
                    } catch (e: HubError.Tool) {
                        null
                    }
                }
                // every await inside the boundary, so none of them can escape
                Triple(pages.await(), described.await(), pending.await())
            }
            val offered = offeredPages(pages)
            _state.update {
                it.copy(
                    loading = false,
                    loaded = true,
                    pages = offered,
                    // A reload that no longer offers the open page left the
                    // screen in page mode with no page: `PageList` is drawn
                    // inside that branch, so there was no back button and no
                    // Hub / Access / Forget rows either — the only way out was
                    // to open another page, or none at all if the hub came back
                    // with an empty list.
                    openPage = it.openPage?.takeIf { id -> offered.any { p -> p.id == id } },
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
        if (s.values[key] == value) {
            // A write the view model drops is still an ANSWER: `"60.0"` or
            // `"060"` for a stored `60` normalises to the value the hub already
            // has, and returning silently left Save live, the tap doing
            // nothing, and the field showing the person's text rather than the
            // hub's value. Bumping `settled` re-seeds the draft.
            _state.update { it.copy(settled = it.settled + 1, fieldErrors = it.fieldErrors - key) }
            return
        }
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
        val id = c.proposalId
        if (id == null) {
            write(c.key, c.value)
            return
        }
        val tag = "#$id"
        if (tag in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + tag) }
        applyDecision(id, apply = true, tag = tag)
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
                _state.update {
                    it.copy(
                        values = it.values + all,
                        busy = it.busy - key,
                        // The hub may normalise to what it already had (a
                        // trimmed text, a clamped number), so the draft is
                        // re-seeded here too.
                        settled = it.settled + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update { it.copy(busy = it.busy - key, fieldErrors = it.fieldErrors + (key to explain(t))) }
            }
        }
    }

    /**
     * Apply or reject one proposal, then read the values and the review again.
     *
     * An APPLY of a confirm-level change asks first, like typing one does.
     */
    fun decide(proposalId: Long, apply: Boolean): Job? {
        val s = _state.value
        val tag = "#$proposalId"
        if (!s.canWrite || tag in s.busy) return null
        if (apply) {
            val p = s.proposals.firstOrNull { it.id == proposalId }
            val d = p?.let { s.descriptors[it.key] }
            if (p != null && d != null && d.danger.confirms && p.value != d.default) {
                _state.update {
                    it.copy(
                        confirm = PendingConfirm(
                            p.key,
                            p.value,
                            d.label,
                            d.danger.message.orEmpty(),
                            proposalId = proposalId,
                        ),
                    )
                }
                return null
            }
        }
        _state.update { it.copy(busy = it.busy + tag) }
        return applyDecision(proposalId, apply, tag)
    }

    private fun applyDecision(proposalId: Long, apply: Boolean, tag: String): Job {
        return scope.launch {
            try {
                val d = actions.decide(
                    accept = if (apply) listOf(proposalId) else emptyList(),
                    reject = if (apply) emptyList() else listOf(proposalId),
                )
                val failed = d.failed.firstOrNull()?.error
                val refused = d.failed.any { f -> f.id == proposalId }
                // Both re-reads are non-fatal, and BOTH after the decision the
                // hub has already made: letting `describe()` throw out of here
                // reached the outer catch, which touches neither `values` nor
                // `proposals` — so the field showed the old value, the proposal
                // still offered Apply, and the hub had applied it. A failed
                // re-read marks the values stale instead.
                val descs = if (d.applied.isNotEmpty()) {
                    runCatching { actions.describe() }.getOrNull()
                } else {
                    null
                }
                val staleNow = d.applied.isNotEmpty() && descs == null
                val pending = runCatching { actions.pending() }.getOrNull()
                _state.update {
                    it.copy(
                        busy = it.busy - tag,
                        // Merged, not replaced: `decide` fetches a hub-wide
                        // snapshot, and a field write that finished while this
                        // was in flight would otherwise be rolled back on
                        // screen by a map read before it.
                        values = descs?.let { x -> it.values + x.associate { y -> y.key to y.value } } ?: it.values,
                        valuesStale = it.valuesStale || staleNow,
                        // A proposal the hub REFUSED is still the hub's: it was
                        // dropped locally whenever the `pending()` re-read also
                        // failed, so a proposal that is still there vanished
                        // from the screen.
                        proposals = pending?.proposals
                            ?: if (refused) it.proposals else it.proposals.filterNot { p -> p.id == proposalId },
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

    /** Clear the stale-values mark: the next successful read did answer. */
    fun clearStale() {
        _state.update { it.copy(valuesStale = false) }
    }
}
