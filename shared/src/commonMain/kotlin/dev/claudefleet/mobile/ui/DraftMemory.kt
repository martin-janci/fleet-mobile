package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.store.Prefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * What was typed into each session's box and not sent. Leaving a session to
 * check another one used to empty its box: the view model is made per visit,
 * and the draft lived only in it.
 *
 * An instance rather than an object (unlike [ScrollMemory]) so each test gets
 * its own; the app holds one in `AppContainer`. Given [prefs], the drafts are
 * also written there, so a box survives the process being killed while the
 * person was in another app (Android reclaims a cached process freely, iOS
 * likewise). Kept small on purpose: at most [MAX_SESSIONS] boxes, each cut to
 * [MAX_CHARS]; a sent draft is emptied by the composer and dropped here.
 */
class DraftMemory(private val prefs: Prefs? = null) {
    private val drafts: MutableMap<Long, String> = linkedMapOf<Long, String>().apply { putAll(load(prefs)) }

    fun recall(sessionId: Long): String = drafts[sessionId].orEmpty()

    fun keep(sessionId: Long, draft: String) {
        val before = drafts[sessionId]
        if (draft.isEmpty()) {
            drafts.remove(sessionId)
        } else {
            // Re-inserted so the most recently touched box is the last one evicted.
            drafts.remove(sessionId)
            drafts[sessionId] = draft
            while (drafts.size > MAX_SESSIONS) drafts.remove(drafts.keys.first())
        }
        if (before != drafts[sessionId]) save()
    }

    /** The hub these drafts were typed for; null before the first pairing this build saw. */
    private var hub: String? = prefs?.getStringList(HUB_KEY)?.firstOrNull()

    /**
     * The drafts belong to [hubUrl] from now on. Pairing with another hub
     * drops the last one's: session ids are per hub, so #12 on hub B must not
     * open with what was typed for hub A's #12 (review w22-5). Drafts kept
     * before this build recorded a hub are taken to be the current hub's.
     */
    fun forHub(hubUrl: String) {
        val was = hub
        if (was == hubUrl) return
        hub = hubUrl
        prefs?.putStringList(HUB_KEY, listOf(hubUrl))
        if (was == null) return
        drafts.clear()
        unsent.clear()
        save()
    }

    private fun save() {
        val p = prefs ?: return
        p.putStringList(KEY, drafts.flatMap { (id, text) -> listOf(id.toString(), text.take(MAX_CHARS)) })
    }

    /**
     * The prompt the hub refused ("Not sent" on the New bar), by session: the
     * box was already emptied, so leaving the screen used to lose it (review
     * w22-4). Kept for this run only, beside the drafts.
     */
    private val unsent: MutableMap<Long, String> = linkedMapOf()

    fun recallUnsent(sessionId: Long): String? = unsent[sessionId]

    fun keepUnsent(sessionId: Long, text: String?) {
        unsent.remove(sessionId)
        if (!text.isNullOrEmpty()) {
            unsent[sessionId] = text
            while (unsent.size > MAX_SESSIONS) unsent.remove(unsent.keys.first())
        }
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

    companion object {
        /** The prefs key: a flat list of session id, draft, session id, draft, … */
        const val KEY = "composer_drafts"
        /** The prefs key of the hub the drafts were typed for ([forHub]). */
        const val HUB_KEY = "composer_drafts_hub"
        const val MAX_SESSIONS = 10
        const val MAX_CHARS = 4_000

        private fun load(prefs: Prefs?): Map<Long, String> {
            val flat = prefs?.getStringList(KEY) ?: return emptyMap()
            return flat.chunked(2)
                .mapNotNull { pair -> pair.getOrNull(0)?.toLongOrNull()?.let { id -> pair.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { id to it } } }
                .takeLast(MAX_SESSIONS)
                .toMap()
        }
    }
}
