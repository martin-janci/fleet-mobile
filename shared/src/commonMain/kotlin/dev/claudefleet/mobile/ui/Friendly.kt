package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.net.HubError

/** What a banner shows: a title, a sentence, and the raw hub text behind a Details expander. */
data class Friendly(val title: String, val body: String, val isError: Boolean, val details: String? = null)

const val NO_TRANSCRIPT = "E_NO_TRANSCRIPT"

/**
 * Plain language in front, the hub's own words behind "Details". Built on
 * [explain], which already decides what may be repeated at all, so the token
 * rule holds here by construction — and `details` is [explain]'s output and
 * nothing else, in every branch, which is what keeps that true.
 */
fun friendly(t: Throwable): Friendly {
    val raw = explain(t)
    return when (t) {
        is HubError.Tool -> when (t.code) {
            NO_TRANSCRIPT -> Friendly("Nothing has been said yet", "Send a prompt to start.", isError = false, details = raw)
            "E_NOTFOUND" -> Friendly("This session is gone", "It was killed or the fleet no longer lists it.", isError = true, details = raw)
            "E_FORBIDDEN" -> Friendly("The hub refused that", t.message, isError = true, details = raw)
            "E_CONFIRM_REQUIRED" -> Friendly("Needs a confirmation on the desktop", "Approve it there; this screen will follow.", isError = true, details = raw)
            "E_BG_SESSION" -> Friendly("Runs outside tmux", "This session has no terminal to type into.", isError = true, details = raw)
            else -> Friendly("The hub refused that", t.message, isError = true, details = raw)
        }
        // Every branch carries `details`, and these four used to carry none —
        // so the four failures a person can do least about were the four with
        // nothing behind the Details button to take to an operator, while a
        // tool refusal (the one kind that explains itself in its own message)
        // had it. The body is now the short thing to DO and `raw` is the
        // evidence, which is also what stops `explain`'s longer sentences
        // (`HubError.Http` carries up to a thousand characters of a proxy's
        // error page) from being the banner's own summary line.
        is HubError.Unauthorized -> Friendly(
            "This device was signed out",
            "Pair this device again to reach the hub.",
            isError = true,
            details = raw,
        )
        is HubError.Transport -> Friendly(
            "Cannot reach the hub",
            "Check the network and the hub address.",
            isError = true,
            details = raw,
        )
        is HubError -> Friendly(
            "The hub answered oddly",
            "The hub's reply is under Details.",
            isError = true,
            details = raw,
        )
        // `raw` for an unexpected throwable is `explain`'s fallback plus the
        // class name and never its message, so Details stays inside the token
        // rule here exactly as it does above.
        else -> Friendly(
            "Something went wrong",
            "The failure is under Details.",
            isError = true,
            details = raw,
        )
    }
}

/**
 * Wraps a plain-string error for a screen not yet migrated to carry [Friendly]
 * itself — `PairUiState`/`SettingsUiState` still hold `String?`, so this is
 * where their value becomes a [Friendly] only at the point `ErrorBanner` needs
 * one, without pulling those view models into this migration.
 */
internal fun String.asGenericFriendly(): Friendly = Friendly(title = "Something went wrong", body = this, isError = true)
