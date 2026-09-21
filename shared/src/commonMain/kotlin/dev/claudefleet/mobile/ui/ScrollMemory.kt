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

    /** Drops everything remembered. Mainly for tests: this object outlives any one of them. */
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
