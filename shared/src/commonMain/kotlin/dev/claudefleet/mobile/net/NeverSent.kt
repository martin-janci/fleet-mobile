package dev.claudefleet.mobile.net

/**
 * The transport failures every engine names the same way that happen before
 * a request leaves the phone: no name, no route, no connection. Matched by
 * the class's simple name, since commonMain cannot see `java.net`.
 */
private val NEVER_SENT_KINDS = setOf(
    "ConnectException",
    "ConnectTimeoutException",
    "UnknownHostException",
    "UnresolvedAddressException",
    "NoRouteToHostException",
)

/**
 * Whether [cause] failed before the request left the phone, so a write that
 * failed with it was certainly not delivered (review of #300). Any other
 * transport failure may have come after the hub had the request: a read
 * timeout, a reset mid-answer, an answer it could not parse.
 *
 * Each engine reports this in its own types — OkHttp as `java.net`
 * exceptions, Darwin as one `DarwinHttpRequestException` around an `NSError`
 * — so [platformNeverSent] holds the per-engine part.
 */
internal fun failedBeforeSending(cause: Throwable): Boolean =
    cause::class.simpleName in NEVER_SENT_KINDS || platformNeverSent(cause)

/** The engine-specific half of [failedBeforeSending]. */
internal expect fun platformNeverSent(cause: Throwable): Boolean

/**
 * The `NSURLErrorDomain` codes URLSession reports before a request went out:
 * cannot find host (-1003), cannot connect to host (-1004), DNS lookup failed
 * (-1006), not connected to the internet (-1009), App Transport Security
 * refused the connection (-1022) and the TLS handshake failed (-1200).
 * `NSURLErrorTimedOut` (-1001) is not one: Ktor's Darwin engine reports it as
 * a `SocketTimeoutException`, and a timeout may come after the hub had the
 * request, as on the JVM. Neither is -1005, a connection lost mid-request.
 *
 * Common so the table is tested on every host, not only on a Mac.
 */
private val NS_URL_NEVER_SENT_CODES = setOf(-1003L, -1004L, -1006L, -1009L, -1022L, -1200L)

internal const val NS_URL_ERROR_DOMAIN = "NSURLErrorDomain"

/** Whether an `NSError` of [domain] and [code] means the request never left the phone. */
internal fun nsUrlErrorNeverSent(domain: String?, code: Long): Boolean =
    domain == NS_URL_ERROR_DOMAIN && code in NS_URL_NEVER_SENT_CODES
