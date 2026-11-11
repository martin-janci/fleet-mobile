package dev.claudefleet.mobile.net

/**
 * OkHttp's pre-send failures beyond the shared names: a TLS handshake that
 * failed, and a socket the OS refused because there is no network or no
 * route ("Network is unreachable", "Host is unreachable"). A reset or a
 * broken pipe is a `SocketException` too, and may come after the request
 * went out, so only the unreachable ones count.
 */
internal actual fun platformNeverSent(cause: Throwable): Boolean = when (cause) {
    is javax.net.ssl.SSLHandshakeException -> true
    is java.net.SocketException -> cause.message.orEmpty().contains("unreachable", ignoreCase = true)
    else -> false
}
