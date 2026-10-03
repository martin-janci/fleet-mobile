package dev.claudefleet.mobile.data

import kotlinx.coroutines.CancellationException

/**
 * Which version the hub is running.
 *
 * Its own interface, next to [SessionActions] and [WorkActions], rather than a
 * method added to one of those: the Settings screen is not a session screen,
 * and the reason those interfaces are narrow is that a screen handed a
 * [dev.claudefleet.mobile.net.HubClient] could call anything the hub offers —
 * including a call that never passes through [AppSession.withClient], which is
 * where "a 401 drops the credential and returns to Pair" lives.
 *
 * The version is read, never cached in a store: it is the hub's, and a hub the
 * operator upgrades under a running app would otherwise go on being reported
 * at whatever it was when the phone first asked.
 */
interface VersionActions {
    /**
     * The hub's own version, or null when it did not say.
     *
     * Never throws, for the same reason [SessionActions.ping] does not: this
     * answers a label on a settings screen, and an unreachable hub is a dash
     * there rather than an error banner over a screen whose other fields
     * (which hub, under what name, with what rights) are all still true.
     */
    suspend fun hubVersion(): String?
}

/** [VersionActions] against the paired hub, through [AppSession.withClient]. */
class HubVersionActions(private val session: AppSession) : VersionActions {
    override suspend fun hubVersion(): String? = try {
        session.withClient { it.fleetHealth() }.version.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // `Throwable`, not `HubError`: a store that will not hand over the
        // credential, a payload that will not decode and anything a client
        // plugin raises all reach here, and none of them is worth more than
        // a dash on this one field.
        null
    }
}
