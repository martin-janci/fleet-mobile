package dev.claudefleet.mobile.ui

/**
 * The **LazyColumn item index** of the turn adjacent to whatever is currently
 * the first visible item, or null when there is none in that direction.
 *
 * `delta` is `-1` for "previous turn" and `+1` for "next turn"; any other
 * value steps that many turns at once, which nothing here needs today but
 * costs nothing extra to allow.
 *
 * Turns are one LazyColumn item apiece (`"turn-$index"`, see
 * [dev.claudefleet.mobile.ui.turnItems]), so `firstVisibleIndex` maps onto a
 * turn index by subtracting the truncation note's offset — the same
 * reasoning [newestItemIndex] uses for the opposite direction: the note
 * occupies item 0 whenever [truncated] is set, so every turn index is pushed
 * one item to the right of where it would otherwise sit. A `firstVisibleIndex`
 * sitting on the note itself maps to turn `-1` — "before the first turn" —
 * which correctly refuses a "previous" step and correctly lands "next" on
 * turn 0.
 *
 * A pure function for the same reason [newestItemIndex] is one: nothing in
 * this repository can render a `LazyColumn`, and an off-by-one here is as
 * invisible to a JVM test run as the one that function's KDoc describes,
 * right up until a device shows it.
 */
internal fun adjacentTurn(firstVisibleIndex: Int, turnCount: Int, truncated: Boolean, delta: Int): Int? {
    if (turnCount <= 0) return null
    val offset = if (truncated) 1 else 0
    val currentTurn = firstVisibleIndex - offset
    val target = currentTurn + delta
    if (target < 0 || target > turnCount - 1) return null
    return target + offset
}
