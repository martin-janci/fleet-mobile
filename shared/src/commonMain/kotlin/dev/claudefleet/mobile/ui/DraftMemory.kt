package dev.claudefleet.mobile.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * What was typed into each session's box and not sent, for as long as the
 * app runs. Leaving a session to check another one used to empty its box:
 * the view model is made per visit, and the draft lived only in it.
 *
 * An instance rather than an object (unlike [ScrollMemory]) so each test gets
 * its own; the app holds one in `AppContainer`. In memory only — a draft is
 * not worth a disk write per keystroke, and a restart forgetting it is the
 * same as the scroll position.
 */
class DraftMemory {
    private val drafts = mutableMapOf<Long, String>()

    fun recall(sessionId: Long): String = drafts[sessionId].orEmpty()

    fun keep(sessionId: Long, draft: String) {
        if (draft.isEmpty()) drafts.remove(sessionId) else drafts[sessionId] = draft
    }

    private val _filled = MutableSharedFlow<Long>(extraBufferCapacity = 8)

    /** The sessions whose draft [fill] changed, so a box already open takes it up. */
    val filled: SharedFlow<Long> = _filled

    /**
     * Put [text] in a session's box after what is there, from outside it (a
     * lesson's suggested prompt, redesign 14.22). Never sent: the person sends
     * or edits it. A box that is open hears it through [filled]; one that is
     * not finds it on its next visit.
     */
    fun fill(sessionId: Long, text: String) {
        keep(sessionId, appendToDraft(recall(sessionId), text))
        _filled.tryEmit(sessionId)
    }
}
