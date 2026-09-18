package dev.claudefleet.mobile.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** The scanner's own files — the camera is theirs and nobody else's. */
private fun File.isScanner(): Boolean = "${File.separator}scan${File.separator}" in path

/**
 * The camera permission is requested when the scanner opens, and at no other
 * moment — above all not when the app starts.
 *
 * **Android only.** Every sweep below walks [Repo.shipped], which is `.kt` files
 * under `shared/src` and `androidApp/src`. The iOS host is Swift and outside
 * both roots, so none of this covers it; the same property for iOS is in
 * [IosHostTest].
 *
 * A fresh install opens on Pair, because there is no credential yet. If the
 * scanner were composed as that screen appeared, the first thing a new user saw
 * would be a camera prompt from an app that has not yet said what it is for;
 * and someone who meant to type the eight characters would have to dismiss a
 * dialog about hardware they were never going to use. On Android 11+ two
 * reflexive dismissals deny the permission permanently — at which point the
 * manual field is not a convenience, it is the only way in.
 *
 * The behaviour this guards structurally is tested behaviourally in
 * `PairViewModelTest`: the screen opens with `scanning = false`, and the whole
 * typed path runs without it ever going true.
 */
class TheCameraIsAskedForOnlyWhenTheScannerOpensTest {

    @Test
    fun only_the_scanner_names_the_camera_permission() {
        val offenders = Repo.shipped
            .filterNot { it.isScanner() }
            .filter { "Manifest.permission.CAMERA" in it.readText() || "android.permission.CAMERA" in it.readText() }
            .map { it.name }

        assertEquals(emptyList(), offenders, "the camera permission is the scanner's to ask for")
    }

    @Test
    fun only_the_scanner_launches_a_permission_request() {
        val offenders = Repo.shipped
            .filterNot { it.isScanner() }
            .filter { "rememberLauncherForActivityResult" in it.readText() }
            .map { it.name }

        assertEquals(emptyList(), offenders, "a permission launcher outside the scanner")
    }

    /**
     * The activity itself must not touch the camera. It is the file a new
     * permission would most plausibly be added to — "ask for everything up
     * front" is the shape most apps have — so it is named rather than left to
     * the sweep above.
     */
    @Test
    fun the_activity_does_not_touch_the_camera() {
        val text = Repo.file("androidApp/src/main/kotlin/dev/claudefleet/mobile/android/MainActivity.kt").readText()

        assertTrue("CAMERA" !in text, "MainActivity must not ask for the camera")
        assertTrue("QrScanner" !in text, "MainActivity must not reach the scanner directly")
    }

    /**
     * And the one call site is inside the `scanning` branch, so composing the
     * Pair screen does not compose the camera.
     *
     * Checked by brace depth rather than by a regex over the two lines: the
     * scanner sits inside a `Box` inside the `if`, and a check that only passes
     * while those two happen to be adjacent is a check that stops meaning
     * anything the first time someone adds a frame around the viewfinder.
     */
    @Test
    fun the_only_call_site_is_guarded_by_the_scanning_flag() {
        val callers = Repo.shipped
            .filterNot { it.isScanner() }
            .filter { "QrScannerView(" in it.readText() }
        val screen = callers.singleOrNull()
            ?: fail("expected exactly one screen to compose the scanner, found ${callers.map { it.name }}")
        assertEquals("PairScreen.kt", screen.name)

        val text = screen.readText()
        val call = text.indexOf("QrScannerView(")
        val guard = text.lastIndexOf("if (state.scanning)", startIndex = call)
        assertTrue(guard >= 0, "the scanner is composed with no `if (state.scanning)` above it")

        var depth = 0
        for (i in guard until call) {
            when (text[i]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    assertTrue(depth > 0, "the scanner is outside the `if (state.scanning)` block")
                }
            }
        }
        assertTrue(depth > 0, "the scanner is not inside the `if (state.scanning)` block")
    }
}

/**
 * The manifest says what it said when the credential rules were written.
 *
 * `allowBackup="false"` and the data-extraction rules arrived as a security fix:
 * the only thing this app persists is the hub credential, in
 * `EncryptedSharedPreferences`, and the Keystore master key that decrypts it is
 * not restorable with the file. A restored copy is a credential outside the
 * device it was issued to *and* a file the app cannot open. `allowBackup` alone
 * does not stop the API 31+ device-to-device transfer, which is why there are
 * two mechanisms here rather than one.
 *
 * The permission list is an allow-list, not a list of things someone thought to
 * forbid: a permission added without a line here fails this test.
 */
class TheAndroidManifestTest {

    private val manifest: String by lazy { Repo.file("androidApp/src/main/AndroidManifest.xml").readText() }

    @Test
    fun the_credential_is_not_backed_up_or_transferred() {
        assertTrue(
            """android:allowBackup="false"""" in manifest,
            "allowBackup must stay false — it covers Auto Backup on pre-31 releases",
        )
        assertTrue(
            """android:dataExtractionRules="@xml/data_extraction_rules"""" in manifest,
            "the API 31+ rules must stay wired up — allowBackup does not stop a device transfer",
        )
    }

    @Test
    fun the_extraction_rules_exclude_everything_and_include_nothing() {
        val rules = Repo.file("androidApp/src/main/res/xml/data_extraction_rules.xml").readText()

        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = rules.substringAfter("<$section>", "").substringBefore("</$section>", "")
            assertTrue(body.isNotBlank(), "<$section> is missing from the extraction rules")
            for (domain in listOf("root", "database", "sharedpref", "external")) {
                assertTrue(
                    """<exclude domain="$domain" />""" in body,
                    "<$section> no longer excludes $domain",
                )
            }
        }
        assertTrue("<include" !in rules, "an include would put app data back into a backup")
    }

    /**
     * What the app asks for **of its own accord**. Two: the network, and — to
     * scan a QR — the camera.
     *
     * It is deliberately not a claim about the installed app, because that is a
     * different and larger list, measured on the assembled APK rather than
     * guessed at:
     *
     * ```
     * $ aapt2 dump permissions androidApp-debug.apk
     * uses-permission: name='android.permission.INTERNET'
     * uses-permission: name='android.permission.CAMERA'
     * permission:      dev.claudefleet.mobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
     * uses-permission: name='dev.claudefleet.mobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
     * uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
     * ```
     *
     * The self-signature permission is `androidx.core`'s own, for its internal
     * receiver registration. `ACCESS_NETWORK_STATE` is merged in by
     * `androidx.media3:media3-common:1.9.0`, which arrives through
     * `camera-view -> camera-video -> media3` — video recording this app does
     * not do. Both are install-time permissions and neither is shown to the
     * person, but the app does appear in the store listing asking for them.
     * See the Task 7 report for the size that chain also costs.
     */
    @Test
    fun the_app_declares_exactly_two_permissions_of_its_own() {
        val asked = PERMISSION.findAll(manifest).map { it.groupValues[1] }.toSet()

        assertEquals(
            setOf("android.permission.INTERNET", "android.permission.CAMERA"),
            asked,
            "a phone client needs the network and, to scan a QR, the camera — nothing else",
        )
    }

    /**
     * The camera is optional hardware. Without this the Play Store hides the app
     * from every device with no camera, and `uses-permission CAMERA` implies
     * `required="true"` unless it is said otherwise.
     */
    @Test
    fun the_app_installs_on_a_device_with_no_camera() {
        val features = FEATURE.findAll(manifest).associate { it.groupValues[1] to it.groupValues[2] }

        assertEquals("false", features["android.hardware.camera"], "the camera must not be required")
        assertEquals("false", features["android.hardware.camera.autofocus"], "autofocus must not be required")
    }

    /**
     * Without an `android:icon` the launcher draws the platform's default green
     * Android, which is how a debug build looks like something half-finished on
     * a home screen.
     */
    @Test
    fun the_launcher_icon_is_declared() {
        assertTrue("""android:icon="@mipmap/ic_launcher"""" in manifest, "no launcher icon")
        assertTrue("""android:roundIcon="@mipmap/ic_launcher_round"""" in manifest, "no round icon")
        for (path in listOf(
            "androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
            "androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
            "androidApp/src/main/res/drawable/ic_launcher_foreground.xml",
        )) {
            Repo.file(path)
        }
    }

    /**
     * No blanket cleartext exception — the Android counterpart of the iOS test
     * that keeps `NSAllowsArbitraryLoads` out of the Info.plist.
     *
     * `android:usesCleartextTraffic="true"` permits plain HTTP to **every**
     * destination, which on an app whose only stored secret is a bearer token
     * means that token can go over the open internet in the clear. It is the
     * obvious thing to reach for when a LAN hub over `http://` does not work,
     * and it is the wrong one: the narrow fix is a network security
     * configuration naming the host actually used.
     *
     * `targetSdk` is 35, so the platform default is already cleartext-blocked
     * and this test is about the app not opting back out of it.
     */
    @Test
    fun the_app_does_not_allow_cleartext_to_every_host() {
        assertTrue(
            """android:usesCleartextTraffic="true"""" !in manifest,
            "a blanket cleartext exception would put the bearer token on the wire in the clear",
        )

        // A network security config is allowed — it is the narrow fix — but if
        // one is ever added it must not simply re-enable cleartext for
        // everything through a base-config, which is the same mistake wearing a
        // different hat.
        val configured = Regex("""android:networkSecurityConfig="@xml/([A-Za-z0-9_]+)"""")
            .find(manifest)?.groupValues?.get(1)
        if (configured != null) {
            val xml = Repo.file("androidApp/src/main/res/xml/$configured.xml").readText()
            val base = xml.substringAfter("<base-config", "").substringBefore(">", "")
            assertTrue(
                "cleartextTrafficPermitted=\"true\"" !in base,
                "the base-config re-enables cleartext for every destination",
            )
        }
    }

    private companion object {
        val PERMISSION = Regex("""<uses-permission\s+android:name="([^"]+)"""")
        val FEATURE = Regex("""<uses-feature\s+android:name="([^"]+)"\s+android:required="([^"]+)"""")
    }
}
