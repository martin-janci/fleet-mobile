package dev.claudefleet.mobile.net

import kotlinx.serialization.json.JsonElement

/**
 * How deeply nested a JSON document off the wire may be.
 *
 * **The problem this solves is that the parser fails in a way nothing here
 * catches, and on one platform it does not fail at all — it takes the app
 * with it.** `kotlinx.serialization` parses by recursive descent, so nesting
 * depth is stack depth. Measured on a background dispatcher, which is where
 * this app actually parses (Ktor delivers on one, and a secondary thread gets
 * a fraction of a main thread's stack):
 *
 *  - **JVM / Android.** A thousand levels parse. Ten thousand throw
 *    `StackOverflowError` — an `Error`, not an `Exception`, and every parse
 *    site in this app guarded with `catch (e: Exception)`. Those catches were
 *    not wrong, they were aimed at one half of `Throwable`, so the throw walked
 *    straight past `parseObject`, `payloadOf`, `frameToEvent` and
 *    `decodeCredentials` alike.
 *  - **Kotlin/Native / iOS.** There is nothing to catch. The process is killed
 *    with **signal 10, `SIGBUS`** — confirmed in CI, where it took the whole
 *    test binary down mid-run and Gradle could only report "test running
 *    process exited unexpectedly".
 *
 * The asymmetry is worth keeping in mind for anything else that recurses over
 * wire data: on Android a stack that runs out is an error someone could in
 * principle handle, and on iOS it is the app disappearing while an agent waits
 * for an answer. It is also invisible from a main-thread test — Native parses
 * ten thousand levels quite happily *there*, which is precisely the measurement
 * that would have said this was fine.
 *
 * Widening the catches to `Throwable` would therefore have fixed nothing.
 * Catching `StackOverflowError` is unreliable even where it is possible, since
 * the stack that would run the handler is the one that just ran out, and on
 * Native the signal never becomes a Kotlin exception at all.
 *
 * So the check happens **before** the parser is handed anything: one linear
 * pass over the text, no recursion, no allocation. It cannot itself overflow,
 * and it costs a scan of a document that was already scanned to be read.
 *
 * **Why 64.** The deepest thing the hub actually sends is a conversation —
 * `{turns:[{items:[{…}]}]}` — which is about five levels, under a JSON-RPC
 * envelope of three more, and even those do not stack: fleet's tools serialize
 * their answer as a JSON *string* inside the envelope, so the two documents are
 * parsed separately. Ten would be generous. Sixty-four is not a guess at the
 * deepest legitimate reply; it is a value nothing legitimate comes close to and
 * no stack, on any platform, has trouble with.
 */
internal const val MAX_JSON_DEPTH: Int = 64

/** What an over-nested document is called on the banner. See [HubError.TooLarge]. */
internal const val WIRE_JSON = "the nesting in the hub's reply"

/**
 * Whether [raw] nests no deeper than [limit].
 *
 * A single pass, and deliberately **not** a parser: it does not validate the
 * document, only count the brackets that could make one recurse. Anything
 * malformed still fails later, in the real parser, exactly as it did before —
 * this only ensures the real parser is never asked to recurse further than it
 * can.
 *
 * String literals are skipped, and escapes inside them: a `"{{{{"` in a
 * `current_activity` or a prompt is text, not structure, and counting it would
 * let ordinary content refuse an ordinary reply. That is the one thing a naive
 * bracket count gets wrong, and it gets it wrong in the direction that breaks
 * working hubs rather than the direction that lets an attack through.
 */
internal fun nestsWithin(raw: String, limit: Int): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in raw) {
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{', '[' -> {
                depth += 1
                if (depth > limit) return false
            }
            '}', ']' -> depth -= 1
        }
    }
    return true
}

/**
 * Refuse [raw] if it nests past [MAX_JSON_DEPTH], naming it [what] if it does.
 *
 * Call this immediately before handing wire text to the parser, not after: the
 * whole point is that the parser never sees it.
 */
internal fun requireShallow(raw: String, what: String = WIRE_JSON) {
    if (!nestsWithin(raw, MAX_JSON_DEPTH)) throw HubError.TooLarge(what, MAX_JSON_DEPTH)
}

/**
 * Parse text that came off the wire, with [requireShallow] run first.
 *
 * The one entry point for turning wire text into a [JsonElement], so that
 * "check the depth first" is a property of the function every caller already
 * uses rather than a line each of them has to remember.
 */
internal fun parseWire(raw: String, what: String = WIRE_JSON): JsonElement {
    requireShallow(raw, what)
    return json.parseToJsonElement(raw)
}
