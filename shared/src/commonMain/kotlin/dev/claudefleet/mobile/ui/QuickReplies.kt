package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.QuickReplyActions
import dev.claudefleet.mobile.model.QuickReply
import dev.claudefleet.mobile.store.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * The chip row above the composer, and the draft history behind the composer
 * field's leading icon. Two lists that look alike and belong in different
 * places, which is the whole point of this class doc.
 *
 * # The chips belong to the fleet
 *
 * They used to be a device preference — a list of bare strings in [Prefs],
 * "never sent to the hub", as this file said. So the same person editing the
 * same row of buttons kept two unrelated lists (this one and the desktop's
 * `localStorage` presets), a phone reinstall lost its own silently, and a chip
 * written on the laptop was not there on the phone that needed it.
 *
 * Now the hub stores one list (`quick_replies`, `service::quick_replies`) and
 * every client reads and writes it. [refresh] pulls, every edit pushes the
 * whole list, and the hub's answer — not the list that was sent — becomes
 * [chips], because the hub normalises (trim, drop a duplicate prompt, restore
 * the built-in defaults for an empty list).
 *
 * [Prefs] keeps its role, demoted to one job: a cache of the last list the hub
 * served, so the row draws the buttons you know on launch and while a read is
 * in flight rather than appearing empty and filling in. An edit is never
 * "saved" by writing it there.
 *
 * A first launch that cannot reach the hub therefore has no chips, and that is
 * deliberate: the defaults live on the hub so a later release can improve one
 * without every installed app pinning a copy of the old set. Nothing is lost
 * by it — a phone that cannot reach the hub cannot send a prompt either.
 *
 * # The history stays on the device
 *
 * [history] is what was typed *here*, and [remember] is called from exactly
 * one place: `SessionViewModel.deliver`, the single path `send()`,
 * `sendCommand()` and `sendQuick()` all go through once a prompt has been
 * accepted by the hub. A key press (`answer(Answer.Enter)`, …) or an answer
 * typed from the blocked card never goes through `deliver`, so neither lands
 * here — this is a history of what this phone *composed*, not of everything
 * this screen ever sent, and it is nobody else's business.
 */
class QuickReplies(
    private val prefs: Prefs,
    private val actions: QuickReplyActions,
) {
    private val _chips = MutableStateFlow(readCache())

    /** The chip row, in the order the hub stores it. */
    val chips: StateFlow<List<QuickReply>> = _chips.asStateFlow()

    /**
     * Read the fleet's list and adopt it.
     *
     * Throws whatever the transport threw — the caller decides whether a
     * failure is worth saying anything about (opening a session screen: no;
     * the edit sheet: yes) — and leaves [chips] on the cached list either way,
     * so a failed read never empties a row that was drawing fine.
     */
    suspend fun refresh() {
        adopt(actions.quickReplies())
    }

    /** Add a chip, ignoring a blank one or a prompt already on the list. */
    suspend fun add(chip: QuickReply) {
        val trimmed = QuickReply(label = chip.label.trim(), text = chip.text.trim())
        if (trimmed.text.isEmpty() || _chips.value.any { it.text == trimmed.text }) return
        push(_chips.value + trimmed)
    }

    /** Remove a chip. A no-op if it is not there. */
    suspend fun remove(chip: QuickReply) {
        val updated = _chips.value.filterNot { it.text == chip.text }
        if (updated.size == _chips.value.size) return
        push(updated)
    }

    /**
     * Replace [original] with [edited], in place rather than at the end: an
     * edited chip that jumped to the end of the row would be a different
     * button as far as muscle memory is concerned.
     */
    suspend fun replace(original: QuickReply, edited: QuickReply) {
        val chip = QuickReply(label = edited.label.trim(), text = edited.text.trim())
        if (chip.text.isEmpty() || chip == original) return
        val at = _chips.value.indexOfFirst { it.text == original.text }
        if (at < 0) return
        push(_chips.value.toMutableList().also { it[at] = chip })
    }

    /**
     * Send a list and adopt the hub's answer, showing it immediately and
     * putting it back on failure.
     *
     * Optimistic, because a chip tap-to-edit that waited on a round trip
     * before redrawing would feel broken on a phone; reverted on failure,
     * because the alternative is a row showing a chip the fleet does not have
     * — which the next [refresh] would silently take away again.
     */
    private suspend fun push(list: List<QuickReply>) {
        val before = _chips.value
        _chips.value = list
        try {
            adopt(actions.quickReplies(list))
        } catch (t: Throwable) {
            _chips.value = before
            throw t
        }
    }

    private fun adopt(list: List<QuickReply>) {
        _chips.value = list
        prefs.putStringList(CHIPS_KEY, list.map { Json.encodeToString(QuickReply.serializer(), it) })
    }

    /**
     * The cached list.
     *
     * Lenient by necessity: a device that ran a build older than the hub-backed
     * list has bare prompt strings under this key, not JSON. They are read as
     * label-less chips rather than discarded, so an upgrade keeps drawing the
     * chips that were there until the first [refresh] replaces them with the
     * fleet's.
     */
    private fun readCache(): List<QuickReply> =
        prefs.getStringList(CHIPS_KEY).mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.startsWith("{")) {
                try {
                    Json.decodeFromString(QuickReply.serializer(), trimmed)
                } catch (_: Throwable) {
                    null
                }
            } else {
                QuickReply.of(trimmed).takeIf { it.text.isNotEmpty() }
            }
        }

    /** The draft history, most recent first — read fresh from [Prefs] every call. */
    fun history(): List<String> = prefs.getStringList(HISTORY_KEY)

    /**
     * Record a delivered prompt: moved to the front if it repeats rather than
     * duplicated, and the list capped at [HISTORY_CAP] — see the class doc for
     * the one call site this is meant to have.
     */
    fun remember(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val updated = (listOf(t) + history().filter { it != t }).take(HISTORY_CAP)
        prefs.putStringList(HISTORY_KEY, updated)
    }

    companion object {
        const val HISTORY_CAP = 20

        /**
         * The cache key, unchanged from when it held bare strings so that an
         * upgrade inherits that device's chips — see [readCache].
         */
        private const val CHIPS_KEY = "quick_reply_chips"
        private const val HISTORY_KEY = "draft_history"
    }
}
