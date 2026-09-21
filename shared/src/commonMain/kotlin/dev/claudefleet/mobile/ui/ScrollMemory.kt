package dev.claudefleet.mobile.ui

/**
 * Where a reader left each open session's turn list scrolled to, kept for as
 * long as the app process runs.
 *
 * A plain object rather than a view model field: [SessionScreen] writes it
 * directly from a `DisposableEffect(sessionId)`'s `onDispose`, so no view
 * model needs to know about `LazyListState` at all, and this is testable with
 * no Compose runtime in the picture. It is app-lifetime and in-memory only —
 * a process restart forgets it, the same as which tab was open.
 *
 * Anchors are **index-based** ([ScrollAnchor.firstVisibleIndex] is a
 * `LazyColumn` item position, not a turn id — turns have none on the wire).
 * `SessionScreen` only ever shows the truncation-note item at index 0 when
 * [dev.claudefleet.mobile.model.Conversation.truncated] is set, so a session
 * that toggles that flag between the visit that stored an anchor and the one
 * that recalls it — the window sliding a turn off the hub's own tail while
 * the screen was away — can land the recall one turn off from where the
 * reader actually left it. That is the cost of an index rather than a turn
 * identity, and cheaper than the identity `Conversation.appending` already
 * has to guess at for the same reason.
 *
 * Main-thread-only: every caller is a composable's effect or a test on the
 * default (single) test dispatcher, and [anchors] is a bare `MutableMap`
 * with no lock of its own.
 */
internal object ScrollMemory {
    private val anchors = mutableMapOf<Long, ScrollAnchor>()

    /**
     * Remember a session's scroll position — UNLESS the reader was at the
     * bottom. An at-bottom anchor and "nothing remembered" mean the same
     * thing on [recall] (both send the reader to the newest turn), so
     * storing one would only cost a lookup that always gets filtered back
     * out; dropping any earlier anchor for this session at the same time
     * keeps a stale "scrolled up" position from outliving a return to the
     * bottom.
     */
    fun remember(sessionId: Long, anchor: ScrollAnchor) {
        if (anchor.atBottom) {
            anchors.remove(sessionId)
        } else {
            anchors[sessionId] = anchor
        }
    }

    /** The session's remembered position, or null when there is none — go to the newest. */
    fun recall(sessionId: Long): ScrollAnchor? = anchors[sessionId]

    /**
     * Drops everything remembered. Test support: this object outlives any one
     * test, so [ScrollMemoryTest] clears it in an `@AfterTest` rather than
     * letting one test's anchors leak into the next.
     */
    fun clear() {
        anchors.clear()
    }
}

/** One session's remembered scroll position, as [ScrollMemory] holds it. */
internal data class ScrollAnchor(
    val firstVisibleIndex: Int,
    val firstVisibleOffset: Int,
    val atBottom: Boolean,
)
