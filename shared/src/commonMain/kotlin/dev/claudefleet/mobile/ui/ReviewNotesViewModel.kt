package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ReviewNoteActions
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.NoteAnchor
import dev.claudefleet.mobile.model.NoteDraft
import dev.claudefleet.mobile.model.REVIEW_NOTES_MAX
import dev.claudefleet.mobile.model.REVIEW_NOTE_BODY_MAX
import dev.claudefleet.mobile.model.ReviewNote
import dev.claudefleet.mobile.model.anchor
import dev.claudefleet.mobile.model.newNotesSendId
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A session's review notes on the phone: what was sent, what is drafted here, and a send on the wire. */
data class ReviewNotesUiState(
    val sent: List<ReviewNote> = emptyList(),
    val drafts: Map<NoteAnchor, NoteDraft> = emptyMap(),
    /** The line whose sheet is open. */
    val open: NoteAnchor? = null,
    /** The open line's quote, for a new draft. */
    val openQuote: String = "",
    val sending: Boolean = false,
    /** A resolve on the wire, by note. */
    val resolving: Set<Long> = emptySet(),
    val error: Friendly? = null,
    /** A failed Resolve, said where Resolve was pressed (the line's sheet, or the list above the diff). */
    val resolveError: Friendly? = null,
    /** What the last send did: typed now, or queued behind the agent's turn. */
    val notice: String? = null,
) {
    /** Sent notes by the line number they were written on; [sentOn] also checks the line still says what they quote. */
    val sentByAnchor: Map<NoteAnchor, List<ReviewNote>> get() =
        sent.mapNotNull { n -> n.anchor()?.let { it to n } }.groupBy({ it.first }, { it.second })

    /**
     * The sent notes that sit on the row at [anchor] whose text is [rowQuote]:
     * only where the row still says what the note quotes (review notes 4). After
     * the agent changed the file, line 12 is another line; its notes are listed
     * above the diff instead ([notesElsewhere]).
     */
    fun sentOn(anchor: NoteAnchor, rowQuote: String): List<ReviewNote> =
        sentByAnchor[anchor].orEmpty().filter { quoteMatches(it, rowQuote) }
}

/**
 * A send's refusal in plain words. `E_IN_FLIGHT` and `E_CONFLICT` are the
 * hub's (it is gaining them); the drafts stay either way.
 */
internal fun sendRefusal(e: HubError.Tool): Friendly = when (e.code) {
    "E_IN_FLIGHT" -> Friendly(
        "Still being typed",
        "The hub is typing these notes into the session now. Your drafts stay; send again in a moment to check.",
        isError = false,
        details = explain(e),
    )
    "E_CONFLICT" -> Friendly(
        "Notes changed since the last try",
        "The hub had an earlier send of different notes. Your drafts stay; send again to send them as they are now.",
        isError = true,
        details = explain(e),
    )
    else -> friendly(e)
}

/** A sent note belongs on a row whose text is the line it quoted. */
internal fun quoteMatches(n: ReviewNote, rowQuote: String): Boolean = n.quote.orEmpty().trimEnd() == rowQuote.trimEnd()

/**
 * Review notes on a session's diff (claude-fleet migration 223, `review_notes`):
 * tap a line to draft a note, Send types every draft into the session as one
 * numbered message. Drafts stay on this phone until sent, in [memory] (per hub
 * and session, across visits and a killed process), with the pending send's id.
 *
 * [canSend] is the token's write right; a viewer's share still drafts, and the
 * hub's refusal of the send is shown as the error.
 */
class ReviewNotesViewModel(
    private val sessionId: Long,
    private val actions: ReviewNoteActions,
    private val scope: CoroutineScope,
    private val now: () -> Long = { epochSeconds() },
    private val memory: NoteDraftMemory = NoteDraftMemory(),
) {
    private val _state = MutableStateFlow(ReviewNotesUiState(drafts = memory.drafts(sessionId)))
    val state: StateFlow<ReviewNotesUiState> = _state.asStateFlow()

    /** Change the drafts on screen and in [memory] together. */
    private fun updateDrafts(change: (ReviewNotesUiState) -> ReviewNotesUiState) {
        _state.update(change)
        memory.keepDrafts(sessionId, _state.value.drafts)
    }

    fun refresh(): Job = scope.launch {
        try {
            val sent = actions.list(sessionId)
            _state.update { it.copy(sent = sent) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: HubError.Tool) {
            // An older hub or a token the tool is not served to: nothing sent to show.
        } catch (_: Throwable) {
            // Keep what was shown: a dropped connection is not "no notes".
        }
    }

    fun openLine(anchor: NoteAnchor, quote: String) {
        _state.update { it.copy(open = anchor, openQuote = quote) }
    }

    fun close() {
        _state.update { it.copy(open = null, openQuote = "") }
    }

    /** Keep [body] as the open line's draft; a blank body deletes the draft. */
    fun saveDraft(body: String) {
        val s = _state.value
        val anchor = s.open ?: return
        val text = body.take(REVIEW_NOTE_BODY_MAX)
        updateDrafts {
            val drafts = if (text.isBlank()) {
                it.drafts - anchor
            } else {
                it.drafts + (anchor to NoteDraft(anchor, it.drafts[anchor]?.quote ?: s.openQuote, text))
            }
            it.copy(drafts = drafts, open = null, openQuote = "")
        }
    }

    fun deleteDraft(anchor: NoteAnchor) {
        updateDrafts { it.copy(drafts = it.drafts - anchor, open = if (it.open == anchor) null else it.open) }
    }

    fun send(): Job? {
        val s = _state.value
        if (s.sending || s.drafts.isEmpty()) return null
        if (s.drafts.size > REVIEW_NOTES_MAX) {
            _state.update { it.copy(error = Friendly("Too many notes", "At most $REVIEW_NOTES_MAX notes go in one message; delete some first.", isError = true)) }
            return null
        }
        // One send per session at a time, across visits: a second visit's
        // Send while the first is out would type the notes twice.
        if (!memory.startSend(sessionId)) {
            _state.update { it.copy(error = Friendly("Still sending", "These notes are already on their way; the drafts stay until the hub answers.", isError = false)) }
            return null
        }
        val drafts = s.drafts.values.toList()
        val fingerprint = notesFingerprint(drafts)
        // The id of a send that got no answer is reused only for the same
        // notes: the hub answers it `repeat` and types nothing twice. Kept in
        // [memory] before the call, so a visit after a lost answer reuses it.
        val id = memory.pending(sessionId)?.takeIf { it.fingerprint == fingerprint }?.id ?: newNotesSendId(now())
        memory.keepPending(sessionId, PendingNotesSend(id, fingerprint))
        _state.update { it.copy(sending = true, error = null, notice = null) }
        return scope.launch {
            try {
                val out = actions.send(sessionId, id, drafts)
                memory.keepPending(sessionId, null)
                updateDrafts { st ->
                    // Only the drafts this send carried go, `repeat` or not:
                    // one written or edited while it was out stays.
                    val left = st.drafts.filterKeys { k -> drafts.none { it.anchor == k && st.drafts[k] == it } }
                    st.copy(
                        drafts = left,
                        sent = (out.notes + st.sent.filterNot { o -> out.notes.any { it.id == o.id } }),
                        notice = if (out.delivered || out.repeat) null else "Queued: it goes in when the agent's turn ends.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HubError.Tool) {
                when (e.code) {
                    // The hub is still typing this very send: the id stays, so
                    // Send again later is answered as the same send.
                    "E_IN_FLIGHT" -> Unit
                    // A refusal is an answer: the next try is a new send.
                    else -> memory.keepPending(sessionId, null)
                }
                _state.update { it.copy(error = sendRefusal(e)) }
            } catch (e: Throwable) {
                // No answer: keep the id, so Send again cannot type them twice.
                _state.update { it.copy(error = friendly(e)) }
            } finally {
                memory.endSend(sessionId)
                _state.update { it.copy(sending = false) }
            }
        }
    }

    fun resolve(note: ReviewNote, resolved: Boolean = !note.resolved): Job? {
        if (note.id in _state.value.resolving) return null
        _state.update { it.copy(resolving = it.resolving + note.id, resolveError = null) }
        return scope.launch {
            try {
                val out = actions.resolve(sessionId, note.id, resolved)
                _state.update { st -> st.copy(resolving = st.resolving - note.id, sent = st.sent.map { if (it.id == out.id) out else it }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Said where Resolve was pressed: the sheet covers the diff's banner.
                _state.update { it.copy(resolving = it.resolving - note.id, resolveError = friendly(e)) }
            }
        }
    }

    fun dismissResolveError() = _state.update { it.copy(resolveError = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }
}
