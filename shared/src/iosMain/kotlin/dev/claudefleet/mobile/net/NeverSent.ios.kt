package dev.claudefleet.mobile.net

import io.ktor.client.engine.darwin.DarwinHttpRequestException

/**
 * Darwin reports a refused connection, a host it cannot find and no network
 * alike as a `DarwinHttpRequestException` around URLSession's `NSError`; its
 * domain and code say which ([nsUrlErrorNeverSent]).
 */
internal actual fun platformNeverSent(cause: Throwable): Boolean =
    cause is DarwinHttpRequestException && nsUrlErrorNeverSent(cause.origin.domain, cause.origin.code)
