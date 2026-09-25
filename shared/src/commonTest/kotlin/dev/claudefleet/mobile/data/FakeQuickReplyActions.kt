package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.QuickReply

/**
 * A hub that holds a chip list, like the real one: a write replaces what is
 * held and the answer is what is held afterwards.
 *
 * That shape is the point — it lets a test pin *the hub's answer wins* rather
 * than *what we sent is what we see*, which is the difference the real hub's
 * normalisation (trim, drop a duplicate prompt, defaults for an empty list)
 * makes.
 */
class FakeQuickReplyActions(
    var held: List<QuickReply> = emptyList(),
    var fail: Boolean = false,
) : QuickReplyActions {
    /** How many times a list was written, so a test can pin "this made no call". */
    var writes: Int = 0

    override suspend fun quickReplies(set: List<QuickReply>?): List<QuickReply> {
        if (fail) throw IllegalStateException("hub is down")
        if (set != null) {
            writes++
            held = set
        }
        return held
    }
}
