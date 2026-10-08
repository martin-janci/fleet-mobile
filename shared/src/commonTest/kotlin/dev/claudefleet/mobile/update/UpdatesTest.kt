package dev.claudefleet.mobile.update

import dev.claudefleet.mobile.store.FakePrefs
import dev.claudefleet.mobile.ui.Navigator
import dev.claudefleet.mobile.ui.PhoneLayout
import dev.claudefleet.mobile.ui.Screen
import dev.claudefleet.mobile.ui.Tab
import dev.claudefleet.mobile.ui.hubMismatchWords
import dev.claudefleet.mobile.ui.kit.megabytes
import dev.claudefleet.mobile.ui.updateSteps
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone's own update (redesign 14.18, MobileUpdate). */
class UpdatesTest {

    private val release = ReleaseInfo(
        version = "0.9.5",
        apkUrl = "https://example.invalid/androidApp-release.apk",
        sizeBytes = 31_000_000,
        sha256 = null,
        notes = listOf("Quiet hours for Needs you", "Send later picks when idle", "Hosts show the last ping", "A fourth"),
        pageUrl = "https://github.com/martin-janci/fleet-mobile/releases/tag/v0.9.5",
    )

    private class FakeInstaller(
        var signature: SignatureCheck = SignatureCheck.Matches("martin-janci"),
        var allowed: Boolean = true,
        var sha: String = "ab".repeat(32),
    ) : AppInstaller {
        var gate: CompletableDeferred<Unit>? = null
        var downloads = 0
        var signatureChecks = 0
        var installs = 0
        var permissionOpened = 0
        var discarded = 0
        var resumedFrom = -1L
        var have = 0L

        override suspend fun download(release: ReleaseInfo, onProgress: (Long, Long) -> Unit): Downloaded {
            downloads++
            resumedFrom = have
            have = release.sizeBytes / 2
            onProgress(have, release.sizeBytes)
            gate?.await()
            have = release.sizeBytes
            onProgress(have, release.sizeBytes)
            return Downloaded(have, sha)
        }

        override suspend fun checkSignature(release: ReleaseInfo): SignatureCheck {
            signatureChecks++
            return signature
        }

        override fun canInstall() = allowed
        override fun openInstallPermission() { permissionOpened++ }
        override suspend fun install(release: ReleaseInfo) { installs++ }
        override fun discard(release: ReleaseInfo) { discarded++; have = 0 }
    }

    @Test
    fun versions_order_by_their_numbers_and_a_suffix_comes_first() {
        assertEquals(AppVersion(0, 9, 5), AppVersion.parse("v0.9.5"))
        assertTrue(AppVersion.parse("0.10.0")!! > AppVersion.parse("0.9.12")!!)
        assertTrue(AppVersion.parse("1.0.0-rc.1")!! < AppVersion.parse("1.0.0")!!)
        assertNull(AppVersion.parse("dev"))
        assertNull(AppVersion.parse(""))
    }

    @Test
    fun a_github_release_reads_as_the_apk_its_size_its_digest_and_plain_notes() {
        val body = """
            {"tag_name":"v0.5.4","html_url":"https://github.com/x/y/releases/tag/v0.5.4",
             "body":"## What's Changed\n* chore(contract): accept hub contract revision 10 by @martin-janci in https://github.com/x/y/pull/110\n* feat(missions): a Missions sheet — steps, cards, Pause all by @martin-janci in https://github.com/x/y/pull/109\n* feat: Orbit Fleet name and launcher icon by @martin-janci in https://github.com/x/y/pull/112\n\n**Full Changelog**: https://github.com/x/y/compare/v0.5.3...v0.5.4",
             "assets":[{"name":"other.txt","browser_download_url":"https://x/other","size":1},
                       {"name":"androidApp-release.apk","browser_download_url":"https://x/apk","size":31000000,
                        "digest":"sha256:${"AB".repeat(32)}"}]}
        """.trimIndent()
        val parsed = assertNotNull(parseRelease(body))
        assertEquals("0.5.4", parsed.version)
        assertEquals("https://x/apk", parsed.apkUrl)
        assertEquals(31_000_000, parsed.sizeBytes)
        assertEquals("ab".repeat(32), parsed.sha256)
        assertEquals(listOf("A Missions sheet — steps, cards, Pause all", "Orbit Fleet name and launcher icon"), parsed.notes)
    }

    @Test
    fun a_release_without_the_apk_is_not_an_update() {
        assertNull(parseRelease("""{"tag_name":"v0.5.4","assets":[]}"""))
        assertNull(parseRelease("""{"tag_name":"nightly","assets":[{"name":"androidApp-release.apk","browser_download_url":"u"}]}"""))
    }

    @Test
    fun an_unreachable_github_is_no_update_and_no_error() = runTest {
        val source = GitHubReleases(HttpClient(MockEngine { respondError(HttpStatusCode.Forbidden) }))
        assertNull(source.latest())
        val ok = GitHubReleases(
            HttpClient(MockEngine {
                respond(
                    """{"tag_name":"v0.9.5","assets":[{"name":"androidApp-release.apk","browser_download_url":"u","size":5}]}""",
                    headers = headersOf("Content-Type", "application/json"),
                )
            }),
        )
        assertEquals("0.9.5", ok.latest()?.version)
    }

    @Test
    fun only_a_newer_release_is_offered() = runTest {
        val vm = UpdateViewModel({ release }, FakeInstaller(), FakePrefs(), "0.9.4", backgroundScope)
        vm.check()
        assertEquals(release, vm.state.value.available)

        val same = UpdateViewModel({ release }, FakeInstaller(), FakePrefs(), "0.9.5", backgroundScope)
        same.check()
        assertNull(same.state.value.available)
    }

    @Test
    fun nothing_is_offered_when_updates_are_off_or_the_platform_updates_elsewhere() = runTest {
        val prefs = FakePrefs()
        val vm = UpdateViewModel({ release }, FakeInstaller(), prefs, "0.9.4", backgroundScope)
        vm.setMode(UpdateMode.OFF)
        vm.check()
        assertNull(vm.state.value.available)
        // Remembered.
        assertEquals(UpdateMode.OFF, UpdateViewModel({ release }, FakeInstaller(), prefs, "0.9.4", backgroundScope).state.value.mode)

        val ios = UpdateViewModel({ release }, null, FakePrefs(), "0.9.4", backgroundScope)
        ios.check()
        assertNull(ios.state.value.available)
        assertEquals(false, ios.state.value.supported)
    }

    @Test
    fun a_matching_signature_reaches_ready_and_install_hands_it_to_android() = runTest {
        val installer = FakeInstaller()
        val prefs = FakePrefs()
        val vm = UpdateViewModel({ release }, installer, prefs, "0.9.4", backgroundScope)
        vm.check()
        vm.download()
        runCurrent()
        assertEquals(UpdatePhase.Ready("martin-janci", needsPermission = false), vm.state.value.phase)
        vm.install()
        runCurrent()
        assertEquals(1, installer.installs)
        assertEquals(UpdatePhase.Handed, vm.state.value.phase)
        assertEquals(listOf("0.9.5") + release.notes, prefs.getStringList(PENDING_NOTES_PREF))
    }

    /** The step's acceptance test: a bad signature stops the install. */
    @Test
    fun a_bad_signature_stops_the_install() = runTest {
        val installer = FakeInstaller(signature = SignatureCheck.Refused("It is not signed with the key this app was installed with."))
        val vm = UpdateViewModel({ release }, installer, FakePrefs(), "0.9.4", backgroundScope)
        vm.check()
        vm.download()
        runCurrent()
        assertIs<UpdatePhase.Refused>(vm.state.value.phase)
        assertEquals(1, installer.discarded, "the refused file is deleted")
        vm.install()
        runCurrent()
        assertEquals(0, installer.installs, "install does nothing after a refusal")
        assertEquals(0, installer.permissionOpened)
    }

    @Test
    fun a_download_that_does_not_match_its_published_checksum_is_refused_before_the_signature() = runTest {
        val installer = FakeInstaller(sha = "00".repeat(32))
        val vm = UpdateViewModel({ release.copy(sha256 = "ab".repeat(32)) }, installer, FakePrefs(), "0.9.4", backgroundScope)
        vm.check()
        vm.download()
        runCurrent()
        assertIs<UpdatePhase.Refused>(vm.state.value.phase)
        assertEquals(0, installer.signatureChecks)
        vm.install()
        assertEquals(0, installer.installs)
    }

    @Test
    fun the_first_install_asks_android_and_waits_for_a_second_tap() = runTest {
        val installer = FakeInstaller(allowed = false)
        val vm = UpdateViewModel({ release }, installer, FakePrefs(), "0.9.4", backgroundScope)
        vm.check()
        vm.download()
        runCurrent()
        assertEquals(UpdatePhase.Ready("martin-janci", needsPermission = true), vm.state.value.phase)
        vm.install()
        runCurrent()
        assertEquals(1, installer.permissionOpened)
        assertEquals(0, installer.installs, "allowing is not installing")
        installer.allowed = true
        vm.recheckPermission()
        assertEquals(UpdatePhase.Ready("martin-janci", needsPermission = false), vm.state.value.phase)
        vm.install()
        runCurrent()
        assertEquals(1, installer.installs)
    }

    @Test
    fun pause_keeps_the_bytes_and_download_carries_on_from_them() = runTest {
        val installer = FakeInstaller().apply { gate = CompletableDeferred() }
        val vm = UpdateViewModel({ release }, installer, FakePrefs(), "0.9.4", backgroundScope)
        vm.check()
        vm.download()
        runCurrent()
        assertEquals(UpdatePhase.Downloading(15_500_000, 31_000_000), vm.state.value.phase)
        vm.pause()
        runCurrent()
        assertEquals(UpdatePhase.Paused(15_500_000, 31_000_000), vm.state.value.phase)
        installer.gate = null
        vm.download()
        runCurrent()
        assertEquals(15_500_000, installer.resumedFrom)
        assertIs<UpdatePhase.Ready>(vm.state.value.phase)
    }

    @Test
    fun cancel_forgets_the_download_and_keeps_the_offer() = runTest {
        val installer = FakeInstaller().apply { gate = CompletableDeferred() }
        val vm = UpdateViewModel({ release }, installer, FakePrefs(), "0.9.4", backgroundScope)
        vm.check()
        vm.download()
        runCurrent()
        vm.cancel()
        runCurrent()
        assertEquals(UpdatePhase.Idle, vm.state.value.phase)
        assertEquals(1, installer.discarded)
        assertEquals(release, vm.state.value.available)
    }

    @Test
    fun whats_new_shows_once_after_an_update_and_never_on_a_fresh_install() {
        val prefs = FakePrefs()
        assertNull(takeWhatsNew(prefs, "0.9.4"), "a fresh install has nothing to compare with")
        assertNull(takeWhatsNew(prefs, "0.9.4"))
        prefs.putStringList(PENDING_NOTES_PREF, listOf("0.9.5", "One", "Two", "Three", "Four"))
        assertEquals(WhatsNew("0.9.5", listOf("One", "Two", "Three")), takeWhatsNew(prefs, "0.9.5"))
        assertNull(takeWhatsNew(prefs, "0.9.5"), "once")
        assertEquals(emptyList(), prefs.getStringList(PENDING_NOTES_PREF))
        // Updated some other way: the wordmark still plays, with no notes.
        assertEquals(WhatsNew("0.9.6", emptyList()), takeWhatsNew(prefs, "0.9.6"))
    }

    /** The step's acceptance test: the hub-older banner shows against an older hub. */
    @Test
    fun the_banner_names_which_side_is_behind() {
        val older = hubMismatch("0.9.5", "0.9.3")
        assertEquals(HubMismatch.HubOlder(hub = "0.9.3", app = "0.9.5"), older)
        assertTrue(hubMismatchWords(older!!).second.contains("owner updates it from the desktop"))
        assertEquals(HubMismatch.HubNewer(hub = "0.10.0", app = "0.9.5"), hubMismatch("0.9.5", "v0.10.0"))
        assertNull(hubMismatch("0.9.5", "0.9.5"))
        assertNull(hubMismatch("0.9.5", null))
        assertNull(hubMismatch("0.9.5", "unknown"))
        assertNull(hubMismatch("dev", "0.9.3"))
    }

    @Test
    fun the_ring_says_the_real_size() {
        assertEquals("12.4 of 31 MB", megabytes(12_400_000, 31_000_000))
        assertEquals("0 of 120 MB", megabytes(0, 120_000_000))
        assertEquals("31 MB", megabytes(31_000_000, 0))
    }

    @Test
    fun the_steps_follow_the_phase() {
        assertEquals(listOf(false, null, null), updateSteps(UpdatePhase.Downloading(1, 2)).map { it.second })
        assertEquals(listOf(true, false, null), updateSteps(UpdatePhase.Checking).map { it.second })
        assertEquals(listOf(true, true, null), updateSteps(UpdatePhase.Ready("x", false)).map { it.second })
        assertEquals(listOf(true, true, false), updateSteps(UpdatePhase.Handed).map { it.second })
    }

    @Test
    fun the_updating_screen_sits_over_more_and_back_returns_there() {
        val nav = Navigator(PhoneLayout.New)
        nav.select(Tab.More)
        nav.openUpdate()
        assertEquals(Screen.Update, nav.screen.value)
        assertEquals(Tab.More, nav.tab.value)
        assertTrue(nav.back())
        assertEquals(Screen.More, nav.screen.value)
    }
}
