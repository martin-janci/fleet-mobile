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
    val error: String? = null,
    /** What the last send did: typed now, or queued behind the agent's turn. */
    val notice: String? = null,
) {
    /** Sent notes by the line they sit on. */
    val sentByAnchor: Map<NoteAnchor, List<ReviewNote>> get() =
        sent.mapNotNull { n -> n.anchor()?.let { it to n } }.groupBy({ it.first }, { it.second })
}

/**
 * Review notes on a session's diff (claude-fleet migration 223, `review_notes`):
 * tap a line to draft a note, Send types every draft into the session as one
 * numbered message. Drafts live in this view model, on this phone, until sent.
 *
 * [canSend] is the token's write right; a viewer's share still drafts, and the
 * hub's refusal of the send is shown as the error.
 */
class ReviewNotesViewModel(
    private val sessionId: Long,
    private val actions: ReviewNoteActions,
    private val scope: CoroutineScope,
    private val now: () -> Long = { epochSeconds() },
) {
    private val _state = MutableStateFlow(ReviewNotesUiState())
    val state: StateFlow<ReviewNotesUiState> = _state.asStateFlow()

    /** The id of a send that got no answer: a retry reuses it, so the hub types nothing twice. */
    private var pendingSendId: String? = null

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
        _state.update {
            val drafts = if (text.isBlank()) {
                it.drafts - anchor
            } else {
                it.drafts + (anchor to NoteDraft(anchor, it.drafts[anchor]?.quote ?: s.openQuote, text))
            }
            it.copy(drafts = drafts, open = null, openQuote = "")
        }
    }

    fun deleteDraft(anchor: NoteAnchor) {
        _state.update { it.copy(drafts = it.drafts - anchor, open = if (it.open == anchor) null else it.open) }
    }

    fun send(): Job? {
        val s = _state.value
        if (s.sending || s.drafts.isEmpty()) return null
        if (s.drafts.size > REVIEW_NOTES_MAX) {
            _state.update { it.copy(error = "At most $REVIEW_NOTES_MAX notes go in one message; delete some first.") }
            return null
        }
        val id = pendingSendId ?: newNotesSendId(now()).also { pendingSendId = it }
        val drafts = s.drafts.values.toList()
        _state.update { it.copy(sending = true, error = null, notice = null) }
        return scope.launch {
            try {
                val out = actions.send(sessionId, id, drafts)
                pendingSendId = null
                _state.update { st ->
                    // Drafts written while the send was out stay.
                    val left = st.drafts.filterKeys { k -> drafts.none { it.anchor == k && st.drafts[k] == it } }
                    st.copy(
                        sending = false,
                        drafts = left,
                        sent = (out.notes + st.sent.filterNot { o -> out.notes.any { it.id == o.id } }),
                        notice = if (out.delivered || out.repeat) null else "Queued: it goes in when the agent's turn ends.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HubError.Tool) {
                // A refusal is an answer: the next try is a new send.
                pendingSendId = null
                _state.update { it.copy(sending = false, error = e.message ?: "The hub refused the notes.") }
            } catch (e: Throwable) {
                // No answer: keep the id, so Send again cannot type them twice.
                _state.update { it.copy(sending = false, error = e.message ?: "Could not send the notes. Try again.") }
            }
        }
    }

    fun resolve(note: ReviewNote, resolved: Boolean = !note.resolved): Job? {
        if (note.id in _state.value.resolving) return null
        _state.update { it.copy(resolving = it.resolving + note.id, error = null) }
        return scope.launch {
            try {
                val out = actions.resolve(sessionId, note.id, resolved)
                _state.update { st -> st.copy(resolving = st.resolving - note.id, sent = st.sent.map { if (it.id == out.id) out else it }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(resolving = it.resolving - note.id, error = e.message ?: "Could not change the note.") }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }
}
