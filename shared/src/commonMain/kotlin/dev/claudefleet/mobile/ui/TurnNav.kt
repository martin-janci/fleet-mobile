package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.ConvTurn

/**
 * One turn as the conversation list draws it: the turn, and the key the
 * `LazyColumn` knows it by.
 */
internal data class TurnRow(val key: String, val turn: ConvTurn)

/**
 * The turns in the order the conversation list holds them: **newest first**.
 *
 * The list is a `reverseLayout` `LazyColumn`, so item 0 — the newest turn —
 * is the one drawn against the bottom edge, and a screen that opens on a
 * fresh `LazyListState` (index 0, offset 0) is already showing it. There is
 * no "scroll to the last item once it has loaded" step left to see happen,
 * which is what used to draw the oldest turns first and then jump.
 *
 * Turns have no id on the wire, so the key is built from the identity
 * `Conversation.appending` already splices on — `at` and the prompt — plus
 * how many earlier turns share it, since a `LazyColumn` key must be unique and
 * two headless turns in one second share both. It used to be the turn's
 * position (`"turn-$index"`), which moved under every turn the moment the
 * oldest one was dropped (`MAX_RETAINED_TURNS`): the list then kept "item
 * turn-150" in view while a different turn had become turn-150. An identity
 * key is what lets the list hold a scrolled-up reader still while turns
 * arrive at the newest end, and would let older history land at the far end
 * without moving the viewport either.
 */
internal fun newestFirst(turns: List<ConvTurn>): List<TurnRow> {
    val seen = HashMap<String, Int>()
    val keyed = turns.map { turn ->
        val identity = "${turn.at}|${turn.prompt?.hashCode()}"
        val n = seen[identity] ?: 0
        seen[identity] = n + 1
        TurnRow(key = "turn:$identity#$n", turn = turn)
    }
    return keyed.asReversed()
}

/**
 * The **LazyColumn item index** of the turn adjacent to the one the reader
 * is on, or null when there is none in that direction.
 *
 * `delta` is `-1` for "previous turn" (older) and `+1` for "next turn"
 * (newer). The list is newest-first ([newestFirst]): item `i` holds turn
 * `turnCount - 1 - i`, so an older turn is a *higher* item index. The
 * truncation note, when there is one, is the item after the oldest turn —
 * drawn at the top — so it shifts no turn's index; a `firstVisibleIndex`
 * sitting on it reads as "before the first turn", which refuses "previous"
 * and lands "next" on the oldest turn.
 *
 * [firstVisibleIndex] is `LazyListState.firstVisibleItemIndex`, which in a
 * `reverseLayout` list is the item at the **bottom** of the viewport.
 *
 * A pure function because nothing in this repository's JVM tests can render a
 * `LazyColumn`, and an off-by-one here is invisible right up until a device
 * shows it.
 */
internal fun adjacentTurn(firstVisibleIndex: Int, turnCount: Int, delta: Int): Int? {
    if (turnCount <= 0) return null
    val target = firstVisibleIndex - delta
    if (target < 0 || target > turnCount - 1) return null
    return target
}
