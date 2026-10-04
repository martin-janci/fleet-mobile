package dev.claudefleet.mobile.ui

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
}
