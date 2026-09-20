package dev.claudefleet.mobile.android

import android.security.NetworkSecurityPolicy
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * What Android actually does with the cleartext this app says it permits.
 *
 * `permitsCleartext` in `data/HubAddress.kt` is the app's transport policy, and
 * it allows plain `http` to loopback and to a LAN address — that is the point
 * of it, because a hub on the dev machine or on the LAN has no certificate.
 * But the app's policy is only half the answer: an app targeting SDK 28 or
 * later gets a *platform* network-security policy that blocks cleartext by
 * default, and that applies before any of this app's code is consulted.
 *
 * So either the platform lets loopback through and the documented setup works,
 * or it does not and `permitsCleartext` has been approving destinations OkHttp
 * then refuses — meaning pairing to a local hub, the documented way to set up a
 * dev machine, has never worked on Android. Nothing in the repository knew
 * which, and it cannot be known off-device: this is the platform's answer about
 * this APK's merged configuration.
 *
 * `NetworkSecurityPolicy` is that answer directly, with no request to make and
 * no exception type to guess at.
 */
class CleartextPolicyTest {

    private val policy: NetworkSecurityPolicy = NetworkSecurityPolicy.getInstance()

    /**
     * The addresses `permitsCleartext` lets plain http reach and that a person
     * setting up a dev machine actually types.
     *
     * `10.0.2.2` is the emulator's alias for the host loopback, which is where
     * a hub run by `scripts/bootstrap.sh` is listening.
     */
    @Test
    fun the_platform_permits_cleartext_where_the_app_does() {
        for (host in listOf("127.0.0.1", "localhost", "10.0.2.2")) {
            assertTrue(
                "the app permits plain http to $host, but the platform blocks it, so a local " +
                    "hub cannot be paired over http — see res/xml/network_security_config.xml",
                policy.isCleartextTrafficPermitted(host),
            )
        }
    }

    /**
     * And it stops there.
     *
     * The wrong fix for the test above is `cleartextTrafficPermitted="true"` in
     * a base-config, which would pass it and put the bearer token on the open
     * internet in the clear. `AndroidHostTest` greps the manifest for that;
     * this asks the platform, which is the thing that would actually be doing
     * it.
     */
    @Test
    fun the_platform_still_blocks_cleartext_to_the_public_internet() {
        for (host in listOf("example.com", "fleet.example.com", "8.8.8.8")) {
            assertFalse(
                "plain http to $host must stay blocked; a blanket cleartext exception would " +
                    "put the bearer token on the wire in the clear",
                policy.isCleartextTrafficPermitted(host),
            )
        }
    }

    /** The blanket switch, asked of the platform rather than of the manifest. */
    @Test
    fun cleartext_is_not_permitted_by_default() {
        assertFalse(
            "the app must not permit cleartext to every destination",
            policy.isCleartextTrafficPermitted,
        )
    }
}
