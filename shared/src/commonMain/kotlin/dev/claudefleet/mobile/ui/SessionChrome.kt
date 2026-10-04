package dev.claudefleet.mobile.ui

/**
 * How much of a session screen's chrome — the header above the conversation,
 * the quick replies and composer under it — is drawn, so that on a phone the
 * agent's output gets the height.
 *
 * Full and compact only, never a height that tracks the finger: the chrome is
 * a variable number of rows (a ticket chip, a retire chip, banners), and a
 * list whose viewport changed height on every frame of a drag would move
 * under the reader's finger. Compose animates the switch between the two.
 */
internal enum class Chrome { Full, Compact }

/**
 * What decides [Chrome] for the header and for the footer. Every field is a
 * plain fact about the screen right now; [headerChrome] and [footerChrome]
 * are the whole policy, so they are what the tests pin.
 */
internal data class ChromeInputs(
    /** The newest turn is on screen (see `SessionScreen`'s `atBottom`). */
    val atBottom: Boolean = true,
    /** The reader's last drag went towards older turns ([ReadingDirection]). */
    val readingUp: Boolean = false,
    /** The person asked for the whole screen (a double tap on the conversation). */
    val immersive: Boolean = false,
    /** The prompt field has focus: someone is typing. */
    val composing: Boolean = false,
    /** Something is in the draft. */
    val hasDraft: Boolean = false,
    /** The agent is waiting on an answer: the blocked card is up. */
    val needsAnswer: Boolean = false,
)

/** Reading back through the conversation, as opposed to sitting at its newest turn. */
private val ChromeInputs.reading: Boolean get() = readingUp && !atBottom

/**
 * The header folds to one line while the reader reads back, asked for the
 * whole screen, or types: in all three what they want is the conversation,
 * and the header's status line and chips come back with one tap on it.
 */
internal fun headerChrome(inputs: ChromeInputs): Chrome =
    if (inputs.immersive || inputs.composing || inputs.reading) Chrome.Compact else Chrome.Full

/**
 * The footer folds to a one-line pill under the same reading / immersive
 * conditions — but never while it is the thing in use: the field has focus,
 * a draft is half written (folding it would hide the words about to be
 * sent), or the agent is waiting on an answer, which the footer is where to
 * give.
 */
internal fun footerChrome(inputs: ChromeInputs): Chrome = when {
    inputs.needsAnswer || inputs.composing || inputs.hasDraft -> Chrome.Full
    inputs.immersive || inputs.reading -> Chrome.Compact
    else -> Chrome.Full
}

/**
 * Which way the reader is scrolling, from the drag deltas the conversation's
 * nested scroll reports, with a dead band so a finger's wobble does not flip
 * the chrome back and forth.
 *
 * The list is `reverseLayout`, so a positive delta — the finger moving down —
 * pulls older turns into view: reading up. Deltas in one direction add up;
 * one against the current run starts a new run from zero. The direction
 * flips once a run reaches [thresholdPx].
 */
internal class ReadingDirection(private val thresholdPx: Float) {
    var readingUp: Boolean = false
        private set
    private var run = 0f

    /** Feed one drag delta; returns the (possibly new) [readingUp]. */
    fun onDrag(deltaY: Float): Boolean {
        if (deltaY == 0f) return readingUp
        run = if (run == 0f || (run > 0f) == (deltaY > 0f)) run + deltaY else deltaY
        if (run >= thresholdPx) readingUp = true
        if (run <= -thresholdPx) readingUp = false
        return readingUp
    }

    /** Back at the newest turn, or sent there: no longer reading up. */
    fun reset() {
        readingUp = false
        run = 0f
    }
}
