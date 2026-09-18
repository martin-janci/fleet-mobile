package dev.claudefleet.mobile.net

import kotlinx.serialization.SerializationException

/**
 * Every way talking to a hub can fail, as one closed set the UI can branch on.
 *
 * The distinctions are the ones the screens act on differently:
 * [Unauthorized] means the token is gone or revoked, so the app drops it and
 * returns to Pair; [Forbidden] is a hub-side configuration problem the operator
 * must fix, so its body is shown verbatim; [Tool] is the hub answering a
 * question with a refusal written for a person, so it is shown as-is.
 *
 * **Nothing in here may repeat the bearer token.** A `HubError` is what a screen
 * shows and what a crash reporter records, and the token is issued once, in
 * exactly one response, and is never recoverable afterwards. Two rules keep that
 * true and both are load-bearing: any body that came off the wire goes through
 * [redacted] before it reaches one of these, and [Transport] never repeats its
 * cause's text.
 */
sealed class HubError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * `401` — the bearer token is unknown or has been revoked.
     *
     * [detail] is the app's own words, never a response body. fleet's own 401 is
     * bare, but the design puts a reverse proxy in front, and oauth2-proxy,
     * nginx's `auth_request` and most WAFs render an error page that quotes the
     * offending `Authorization` header. A 401 body is therefore the single most
     * likely place for the token to come *back* at us, and nothing reads it —
     * `AppSession.withClient` branches on the type — so it does not come in.
     */
    data class Unauthorized(val detail: String = "") :
        HubError(
            buildString {
                append("the hub did not accept the credential (401)")
                if (detail.isNotBlank()) append(": ").append(detail)
            },
        )

    /**
     * `403` — the `Host`/`Origin` the request arrived with is not on the hub's
     * allowlist.
     *
     * [body] is very often **empty**, and the explanation cannot lean on it:
     * fleet's `authorize` layer refuses with a bare `StatusCode`, which axum
     * renders with no body at all. Only something *in front* of the hub, like a
     * reverse proxy, tends to send one. So the message explains the condition
     * itself and names [hub] — the address this app used, which is the one
     * piece of the puzzle the app is certain of — and appends the body only
     * when there is one.
     */
    data class Forbidden(val body: String, val hub: String = "") :
        HubError(
            buildString {
                append("the hub refused the request (403). ")
                append("Its allowed-hosts list does not accept the address this app used")
                if (hub.isNotBlank()) append(" ($hub)")
                append(". Only the operator can change that, on the hub itself.")
                if (body.isNotBlank()) append(" The hub added: $body")
            },
        )

    /**
     * The tool ran and said no: an MCP result with `isError: true` carrying an
     * `E_*` code, or a JSON-RPC `error` object. Both are one thing to a caller.
     */
    data class Tool(val code: String, override val message: String) : HubError(message)

    /**
     * Any other HTTP status — `404` from a spent pairing code, `429` from the
     * pairing rate limit, a `502` from the reverse proxy.
     *
     * [body] has already been through [redacted]: capped, and with the token
     * taken out of it. A 502 page from a proxy is exactly what an operator needs
     * to see and exactly the kind of page that quotes the request's headers.
     */
    data class Http(val status: Int, val body: String) :
        HubError("the hub answered HTTP $status: $body")

    /**
     * The hub could not be reached, or answered something unintelligible.
     *
     * Deliberately **not** a `data class`, and deliberately silent about the
     * cause's text, both for the same reason: kotlinx.serialization appends the
     * *input document* to its own exception message, and the hub writes `token`
     * as the first field of the pair reply. A pair response cut short by a
     * dropped connection or a proxy buffer was enough to put the plaintext token
     * into `message`, into a generated `toString()`, and from there into a
     * screenshot and a crash service.
     *
     * So: the message names the failure's *type* and never its text, the
     * generated `toString()` that would have printed the cause is replaced, and
     * a cause that quotes its input is not kept at all — otherwise a stack trace
     * would print what the message would not.
     *
     * The cost is real and accepted: "could not reach the hub
     * (SocketTimeoutException)" says less than the underlying text would. The
     * alternative is a rule that holds only until someone wraps a new throwable
     * that happens to quote the wire, and fails silently when they do.
     */
    class Transport(cause: Throwable) : HubError(transportMessage(cause), safeCause(cause)) {
        /** The failure's type, which is all of it that is safe to repeat. */
        val kind: String = kindOf(cause)

        override fun toString(): String = "HubError.Transport(kind=$kind)"
    }
}

private fun kindOf(cause: Throwable): String =
    cause::class.simpleName?.takeIf { it.isNotBlank() } ?: "failure"

private fun transportMessage(cause: Throwable): String =
    "could not reach the hub (${kindOf(cause)})"

/**
 * The cause, unless keeping it would keep the wire text with it.
 *
 * `SerializationException` and everything under it quote the document they
 * failed on. Dropping the cause loses a stack trace; keeping it loses the token.
 */
private fun safeCause(cause: Throwable): Throwable? =
    if (generateSequence(cause) { it.cause }.any { it is SerializationException }) null else cause

/** How much of a response body an error may carry. */
private const val MAX_ERROR_BODY = 1_000

/**
 * Text that came off the wire, made safe to put in a [HubError]: with [secret]
 * — this client's bearer token — taken out of it, and then capped.
 *
 * **The order is the whole point, and it used to be the other way round.**
 * Capping first cuts a token that straddles the cut in half; the `replace` then
 * matches nothing, and the surviving prefix goes out verbatim. For a 64-hex
 * token cut at its last character that leaves sixteen possibilities. So: scrub
 * the whole thing, then cap what is left.
 *
 * **A correction to this comment's own reasoning.** It used to justify the
 * order by saying an `Http` failure's text reaches the reconnect banner. That
 * was not true when it was written — `ConnectionStatus.Reconnecting.reason` was
 * computed and never drawn — and the claim survived because nobody checked the
 * one composable that renders a status. It is true now, because that field was
 * wired into the banner rather than deleted. The fix was always right on the
 * strength of the paths that *were* live: a `Forbidden` body renders on the
 * error banner of five screens, and the `/pair` error goes straight to the
 * Pair screen. A right answer resting on a wrong reason is still worth
 * correcting, because the next person prunes what the reason no longer
 * supports.
 *
 * It is not a general sanitiser and cannot be. It removes the one secret this
 * app holds, in any letter case, which is the one a proxy's error page is liable
 * to echo back. A proxy that base64'd it, url-encoded it, or split it across
 * markup would still slip through, and no `replace` can fix that. Read the name
 * as "token-scrubbed", not "safe".
 */
internal fun redacted(body: String, vararg secrets: String?): String {
    // `ignoreCase` because a hub token is 32 bytes rendered as **lowercase hex**
    // (`mcp/mod.rs`), and an upper-cased echo of it is still the token — not a
    // re-encoding but the same 64 characters, which a case-sensitive `replace`
    // walks straight past. Plenty of proxies upper-case what they quote back in
    // a header dump. Measured before the fix: 64 of 64 characters survived.
    var scrubbed = body
    for (secret in secrets) {
        if (!secret.isNullOrBlank()) {
            scrubbed = scrubbed.replace(secret, "<redacted>", ignoreCase = true)
        }
    }
    return if (scrubbed.length > MAX_ERROR_BODY) {
        scrubbed.take(MAX_ERROR_BODY) + "… (truncated)"
    } else {
        scrubbed
    }
}
