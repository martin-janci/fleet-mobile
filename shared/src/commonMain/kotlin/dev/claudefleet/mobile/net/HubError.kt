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
     * allowlist. Carries the body because only the hub can say which name it
     * expects.
     */
    data class Forbidden(val body: String) :
        HubError("the hub refused the request (403): $body")

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
