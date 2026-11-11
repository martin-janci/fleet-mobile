package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.NoteAnchor
import dev.claudefleet.mobile.model.NoteDraft
import dev.claudefleet.mobile.model.orderedDrafts
import dev.claudefleet.mobile.store.Prefs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A send of review notes that got no answer yet: its `client_msg_id` and
 * which notes it carried ([fingerprint]), so a retry reuses the id only for
 * the same notes. An edited or added note is a new send with a new id; the
 * old id would make the hub answer `repeat` for notes it never had.
 */
data class PendingNotesSend(val id: String, val fingerprint: String)

/**
 * Review-note drafts and the pending send, by session, beside [DraftMemory]
 * (review notes 3 and 7): the notes view model is made per visit, so drafts
 * and the retry id kept only in it were gone on tapping back, and the next
 * send minted a new id for notes the hub may already have typed.
 *
 * Per hub, as [DraftMemory]: session ids are the hub's, so pairing with
 * another hub drops the last one's. Given [prefs], written there too, so a
 * killed process keeps them. Which sessions have a send on the wire is held
 * for this run only: two visits to one session must not send twice.
 */
class NoteDraftMemory(private val prefs: Prefs? = null) {
    private val entries: MutableMap<Long, Entry> = linkedMapOf<Long, Entry>().apply { putAll(load(prefs)) }
    private val sending: MutableSet<Long> = mutableSetOf()
    private var hub: String? = prefs?.getStringList(HUB_KEY)?.firstOrNull()

    fun drafts(sessionId: Long): Map<NoteAnchor, NoteDraft> =
        entries[sessionId]?.drafts.orEmpty().associate { d -> d.anchor() to NoteDraft(d.anchor(), d.quote, d.body) }

    fun keepDrafts(sessionId: Long, drafts: Map<NoteAnchor, NoteDraft>) {
        val stored = drafts.values.map { StoredDraft(it.anchor.path, it.anchor.line, it.anchor.side, it.quote, it.body) }
        update(sessionId) { it.copy(drafts = stored) }
    }

    fun pending(sessionId: Long): PendingNotesSend? =
        entries[sessionId]?.let { e -> e.sendId?.let { PendingNotesSend(it, e.sendFingerprint.orEmpty()) } }

    fun keepPending(sessionId: Long, send: PendingNotesSend?) {
        update(sessionId) { it.copy(sendId = send?.id, sendFingerprint = send?.fingerprint) }
    }

    /** A send for [sessionId] is on the wire, from any visit. */
    fun inFlight(sessionId: Long): Boolean = sessionId in sending

    /** Mark a send on the wire; false when one already is. */
    fun startSend(sessionId: Long): Boolean = sending.add(sessionId)

    fun endSend(sessionId: Long) {
        sending.remove(sessionId)
    }

    /** The drafts belong to [hubUrl] from now on; another hub's go (as [DraftMemory.forHub]). */
    fun forHub(hubUrl: String) {
        val was = hub
        if (was == hubUrl) return
        hub = hubUrl
        prefs?.putStringList(HUB_KEY, listOf(hubUrl))
        if (was == null) return
        entries.clear()
        sending.clear()
        save()
    }

    private fun update(sessionId: Long, change: (Entry) -> Entry) {
        val before = entries[sessionId]
        val after = change(before ?: Entry())
        entries.remove(sessionId)
        if (!after.isEmpty) {
            entries[sessionId] = after
            while (entries.size > MAX_SESSIONS) entries.remove(entries.keys.first())
        }
        if (before != entries[sessionId]) save()
    }

    private fun save() {
        val p = prefs ?: return
        p.putStringList(KEY, listOf(store.encodeToString(SERIALIZER, entries.mapKeys { it.key.toString() })))
    }

    @Serializable
    private data class StoredDraft(
        val path: String,
        val line: Long,
        val side: String,
        val quote: String = "",
        val body: String,
    ) {
        fun anchor() = NoteAnchor(path, line, side)
    }

    @Serializable
    private data class Entry(
        val drafts: List<StoredDraft> = emptyList(),
        @SerialName("send_id") val sendId: String? = null,
        @SerialName("send_fingerprint") val sendFingerprint: String? = null,
    ) {
        val isEmpty: Boolean get() = drafts.isEmpty() && sendId == null
    }

    companion object {
        const val KEY = "review_note_drafts"
        const val HUB_KEY = "review_note_drafts_hub"
        const val MAX_SESSIONS = 20

        private val store = Json { ignoreUnknownKeys = true }
        private val SERIALIZER = MapSerializer(String.serializer(), Entry.serializer())

        private fun load(prefs: Prefs?): Map<Long, Entry> {
            val raw = prefs?.getStringList(KEY)?.firstOrNull() ?: return emptyMap()
            return try {
                store.decodeFromString(SERIALIZER, raw).mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }.toMap()
            } catch (_: Exception) {
                // A store this build cannot read is dropped, not a crash on launch.
                emptyMap()
            }
        }
    }
}

/**
 * Which notes a send carries, in the order the message numbers them: a retry
 * with the same fingerprint is the same send. FNV-1a over the fields, so the
 * store keeps 16 hex characters rather than every note twice.
 */
fun notesFingerprint(drafts: Collection<NoteDraft>): String {
    var h = 0xcbf29ce484222325uL
    fun add(s: String) {
        for (c in s) {
            h = h xor c.code.toULong()
            h *= 0x100000001b3uL
        }
        h = h xor 0x1fuL
        h *= 0x100000001b3uL
    }
    for (d in orderedDrafts(drafts)) {
        add(d.anchor.path)
        add(d.anchor.line.toString())
        add(d.anchor.side)
        add(d.quote)
        add(d.body.trim())
    }
    return h.toString(16).padStart(16, '0')
}
