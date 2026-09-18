package dev.claudefleet.mobile.net

/**
 * Every way talking to a hub can fail, as one closed set the UI can branch on.
 *
 * The distinctions are the ones the screens act on differently:
 * [Unauthorized] means the token is gone or revoked, so the app drops it and
 * returns to Pair; [Forbidden] is a hub-side configuration problem the operator
 * must fix, so its body is shown verbatim; [Tool] is the hub answering a
 * question with a refusal written for a person, so it is shown as-is.
 */
sealed class HubError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** `401` — the bearer token is unknown or has been revoked. */
    data class Unauthorized(val body: String = "") :
        HubError("the hub did not accept the credential (401)")

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
     * Not in the design's sketch of this type, which elided the tail; added
     * rather than folding these into [Transport] (they are answers, not
     * failures to reach) or minting a fake `E_*` code for them.
     */
    data class Http(val status: Int, val body: String) :
        HubError("the hub answered HTTP $status: $body")

    /** The hub could not be reached, or answered something unintelligible. */
    data class Transport(override val cause: Throwable) :
        HubError("could not reach the hub: ${cause.message}", cause)
}
