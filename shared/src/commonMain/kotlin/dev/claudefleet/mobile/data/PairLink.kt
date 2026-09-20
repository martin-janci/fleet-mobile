package dev.claudefleet.mobile.data

/**
 * The URL scheme that hands this app a pairing code without a camera.
 *
 * A `claudefleet:` link carries **the same string the QR encodes**, with the
 * scheme in front of it:
 *
 * ```
 * claudefleet:https://fleet.example.com/pair#ABCDEFGH
 * ```
 *
 * That shape is deliberate. The obvious alternative —
 * `claudefleet://pair?hub=…&code=…` — needs its own parser, its own
 * percent-decoding, and its own set of rules about which halves may be missing;
 * and this file would then be the second place that decides what a pairing code
 * is. Prefixing the existing URL means [PairTarget.parse] does all of it, so a
 * link is accepted exactly when the same text would be accepted off a camera,
 * including the userinfo, cleartext and alphabet rules that took four attempts
 * to get right. Nothing here validates anything.
 *
 * It is also the easier thing to *produce*, which matters because the point of
 * the scheme is that something other than a person can drive it: the hub
 * already prints the pair URL, and a link is that URL with seven characters in
 * front.
 *
 * ```bash
 * # Android
 * adb shell am start -a android.intent.action.VIEW \
 *   -d "claudefleet:$(fleet-hub pair --name phone --quiet)"
 * # iOS simulator
 * xcrun simctl openurl booted "claudefleet:https://fleet.example.com/pair#ABCDEFGH"
 * ```
 *
 * **A link never pairs a release build on its own.** It fills the two fields on
 * the Pair screen and stops; somebody taps. The reason is that a link is
 * something anyone can send: it cannot reach an existing credential, but a
 * silent pair would re-point the app at a hub of the sender's choosing, and the
 * next prompt typed would go there. A debug build does submit, because the
 * whole point of a debug build here is that an agent can set it up with no
 * hands — see `AppContainer.autoPairFromLink`.
 */
internal const val PAIR_LINK_SCHEME: String = "claudefleet"

/**
 * The pair URL inside a [PAIR_LINK_SCHEME] link, or null if [uri] is not one.
 *
 * Returns the text unexamined: whether it is a *usable* pairing code is
 * [PairTarget.parse]'s question, and asking it twice is how two answers start
 * disagreeing. All this decides is "was this link addressed to us".
 *
 * Both `claudefleet:` and `claudefleet://` are accepted. The second is what a
 * person writes by habit and what some tooling produces when it normalises a
 * URI, and refusing it would be a puzzle rather than a safeguard — the string
 * after it is identical either way.
 */
internal fun pairLinkPayload(uri: String): String? {
    val trimmed = uri.trim()
    val prefix = "$PAIR_LINK_SCHEME:"
    if (!trimmed.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return null
    return trimmed.substring(prefix.length).removePrefix("//").trim().takeIf { it.isNotEmpty() }
}
