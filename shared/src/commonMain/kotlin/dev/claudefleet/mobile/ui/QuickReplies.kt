package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.store.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The chip row above the composer, and the draft history behind the
 * composer field's leading icon — both kept on the device through [Prefs],
 * never sent to the hub.
 *
 * [chips] starts at [DEFAULT_CHIPS] on a device that has never touched it,
 * and [add]/[remove] persist the edited list immediately so a later instance
 * built over the same [Prefs] — a fresh app launch — reads back exactly what
 * was left, rather than the defaults again.
 *
 * [remember] is the write side of the history, and it is called from exactly
 * one place: `SessionViewModel.deliver`, the single path `send()`,
 * `sendCommand()` and `sendQuick()` all go through once a prompt has actually
 * been accepted by the hub. A key press (`answer(Answer.Enter)`, …) or an
 * answer typed from the blocked card never goes through `deliver`, so neither
 * ever lands here — this is a history of what was *composed*, not of every
 * character this screen ever sent.
 */
class QuickReplies(private val prefs: Prefs) {
    private val _chips: MutableStateFlow<List<String>>

    /** The chip row, most recently changed still in whatever order [add] appended it. */
    val chips: StateFlow<List<String>>

    init {
        val stored = prefs.getStringList(CHIPS_KEY)
        val initial = stored.ifEmpty { DEFAULT_CHIPS }
        // A first-run device has nothing stored yet: write the defaults back
        // so a *second* instance over the same `Prefs` (the next launch) sees
        // the same list this one started with, rather than reading empty and
        // re-deriving the defaults independently — which would look the same
        // today but would silently diverge the moment `DEFAULT_CHIPS` changes
        // between app versions on a device that already has one.
        if (stored.isEmpty()) prefs.putStringList(CHIPS_KEY, DEFAULT_CHIPS)
        _chips = MutableStateFlow(initial)
        chips = _chips.asStateFlow()
    }

    /** Add a chip, ignoring a blank or already-present one. Persists at once. */
    fun add(text: String) {
        val t = text.trim()
        if (t.isEmpty() || t in _chips.value) return
        val updated = _chips.value + t
        _chips.value = updated
        prefs.putStringList(CHIPS_KEY, updated)
    }

    /** Remove a chip. A no-op if it is not there. Persists at once. */
    fun remove(text: String) {
        val updated = _chips.value - text
        if (updated == _chips.value) return
        _chips.value = updated
        prefs.putStringList(CHIPS_KEY, updated)
    }

    /** The draft history, most recent first — read fresh from [Prefs] every call. */
    fun history(): List<String> = prefs.getStringList(HISTORY_KEY)

    /**
     * Record a delivered prompt: moved to the front if it repeats rather than
     * duplicated, and the list capped at [HISTORY_CAP] — see the class doc
     * for the one call site this is meant to have.
     */
    fun remember(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val updated = (listOf(t) + history().filter { it != t }).take(HISTORY_CAP)
        prefs.putStringList(HISTORY_KEY, updated)
    }

    companion object {
        const val HISTORY_CAP = 20
        val DEFAULT_CHIPS: List<String> = listOf("go on", "yes", "run the tests", "commit and push", "/clear", "/compact")
        private const val CHIPS_KEY = "quick_reply_chips"
        private const val HISTORY_KEY = "draft_history"
    }
}
