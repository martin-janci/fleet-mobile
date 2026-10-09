package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.NotAPairingCode
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.store.SecretsUnavailable

/**
 * What a screen says when something failed.
 *
 * [HubError.Tool] keeps its `E_*` code: those messages are written for a person
 * and the code is what an operator greps the hub's log for, so the design's
 * "shown as-is" means both halves. Every other [HubError] already carries a
 * message built to be shown — scrubbed of the bearer token, and in
 * [HubError.Transport]'s case deliberately saying only the failure's *type*.
 *
 * Two of the app's own exceptions are shown as well, and only because each one
 * promises in writing that its message is written for a person and carries
 * nothing that came from outside: [NotAPairingCode] never repeats the input it
 * refused, and [SecretsUnavailable] names what the store would not do and never
 * the value involved. Adding a third means making the same promise and meaning
 * it.
 *
 * Anything else does **not** get its message repeated. The token-hygiene rule
 * that `HubError` is written around — no wire text reaches a screen, a log or a
 * crash report — is worth nothing if an unexpected throwable can walk its
 * message straight onto the page instead.
 */
internal fun explain(t: Throwable): String = when (t) {
    is HubError.Tool -> if (t.code.isBlank()) t.message else "${t.code}: ${t.message}"
    is HubError -> t.message ?: FALLBACK
    is NotAPairingCode -> t.message ?: FALLBACK
    is SecretsUnavailable -> t.message ?: FALLBACK
    else -> "$FALLBACK (${t::class.simpleName ?: "failure"})"
}

private const val FALLBACK = "something went wrong"

/**
 * [explain] for a pairing attempt, the very first thing a new user does: the
 * common refusals in words, with what to do next. The hub's own answer (the
 * HTTP status, the transport's kind) is [pairDetails]'s, for a Details line —
 * a person reading "HTTP 404: invalid code" learns nothing they can act on.
 */
internal fun explainPair(t: Throwable): String = when {
    t is HubError.Http && t.status == 404 ->
        "That code didn't work. A code works once and expires a few minutes after it is made — " +
            "run `fleet-hub pair` for a new one."
    t is HubError.Http && t.status == 429 ->
        "Too many tries in a row. Wait a minute, then try again."
    t is HubError.Transport ->
        "Can't reach that address. Check it, and that this phone can reach the hub's network."
    t is HubError.Http -> "The hub turned this pairing down. Try a new code, and check the hub's log if it happens again."
    // Written for a person, and promised to carry nothing from outside.
    t is NotAPairingCode || t is SecretsUnavailable -> explain(t)
    else -> "Couldn't pair with that hub. What went wrong is under Details."
}

/** The technical half of a pairing failure, for Details; null when [explainPair] already said all of it. */
internal fun pairDetails(t: Throwable): String? = when (t) {
    is NotAPairingCode, is SecretsUnavailable -> null
    else -> explain(t)
}
