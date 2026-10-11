package dev.claudefleet.mobile.data

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Pairing by signing in: the hub's single sign-on (Keycloak or another OpenID
 * Connect provider — claude-fleet `docs/hub.md` → *Sign in with Keycloak*).
 *
 * The app opens `<hub>/auth/oidc/start` in the system browser; the person signs
 * in there; the hub's last page carries the same `claudefleet:` link a printed
 * pair URL becomes ([PAIR_LINK_SCHEME]), and tapping it brings the code back.
 * So nothing new reaches the hub from the app: the browser holds the sign-in,
 * and the app redeems an ordinary pairing code at `POST /pair`, after a tap,
 * exactly as for a scanned one.
 *
 * **Why this gate exists.** Bringing the link back from a browser needs the
 * Android scheme to be `BROWSABLE`, which is also what lets *any* web page open
 * the app with a link of its choosing. A link only ever fills the Pair screen's
 * fields, but the smaller the set of things that can put a stranger's hub
 * there, the better — so a link that came from a web page is accepted only
 * while this phone is waiting for a sign-in it started itself, and only for the
 * hub it started it with. Every other browser link is refused on the screen.
 *
 * Process memory only: an app killed mid-sign-in forgets it, and the person
 * starts again, as they would after a hub restart.
 */
class SsoSignIn(
    private val ttl: Duration = 10.minutes,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    private var pending: Pair<String, TimeMark>? = null

    /** The hub a sign-in is in flight with, or null when none is (or it expired). */
    val pendingHub: String?
        get() = pending?.takeIf { it.second.elapsedNow() < ttl }?.first

    /**
     * Begin a sign-in with [base] (a [hubBase]-checked address) and return the
     * page to open in the browser.
     */
    fun start(base: String): String {
        pending = base to clock.markNow()
        return ssoStartUrl(base)
    }

    /** Whether a link from a web page naming [base] answers the sign-in in flight. */
    fun accepts(base: String?): Boolean {
        val want = pendingHub ?: return false
        return base != null && sameHub(base, want)
    }

    /** The sign-in is over: answered, or abandoned for another way in. */
    fun clear() {
        pending = null
    }
}

/** The hub's sign-in page. */
internal fun ssoStartUrl(base: String): String = "${base.trim().trimEnd('/')}/auth/oidc/start"

/** Two hub addresses are one hub: the scheme and host compared case-blind, the path as is. */
internal fun sameHub(a: String, b: String): Boolean {
    fun key(u: String): String {
        val t = u.trim().trimEnd('/')
        val sep = t.indexOf("://")
        if (sep < 0) return t
        val authority = t.substring(sep + 3).substringBefore('/')
        val path = t.substring(sep + 3 + authority.length)
        return t.substring(0, sep).lowercase() + "://" + authority.lowercase() + path
    }
    return key(a) == key(b)
}
