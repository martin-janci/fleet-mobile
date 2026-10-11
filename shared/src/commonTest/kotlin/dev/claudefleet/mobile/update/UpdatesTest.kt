package dev.claudefleet.mobile.update

import dev.claudefleet.mobile.store.FakePrefs
import dev.claudefleet.mobile.ui.Navigator
import dev.claudefleet.mobile.ui.PhoneLayout
import dev.claudefleet.mobile.ui.Screen
import dev.claudefleet.mobile.ui.Tab
import dev.claudefleet.mobile.ui.hubMismatchWords
import dev.claudefleet.mobile.ui.kit.megabytes
import dev.claudefleet.mobile.ui.updateCheckLine
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

    /** Review r13 (P13-12): a stopped download says so in words, and Try again downloads again. */
    @Test
    fun try_again_runs_the_download_again_and_the_message_is_a_sentence() = runTest {
        val installer = object : AppInstaller by FakeInstaller() {
            var fail = true
            var tries = 0
            override suspend fun download(release: ReleaseInfo, onProgress: (Long, Long) -> Unit): Downloaded {
                tries++
                if (fail) throw IllegalStateException("socket closed reading https://example.invalid/x")
                return Downloaded(release.sizeBytes, "ab".repeat(32))
            }
        }
        val vm = UpdateViewModel({ release }, installer, FakePrefs(), "0.9.4", backgroundScope)
        vm.check()
        vm.download()
        runCurrent()
        val failed = assertIs<UpdatePhase.Failed>(vm.state.value.phase)
        assertEquals(DOWNLOAD_STOPPED, failed.message)
        assertTrue("IllegalStateException" in failed.details.orEmpty(), "${failed.details}")
        assertTrue("socket" !in failed.message && "socket" !in failed.details.orEmpty())

        installer.fail = false
        vm.retry()
        runCurrent()
        assertEquals(2, installer.tries)
        assertIs<UpdatePhase.Ready>(vm.state.value.phase)
    }

    @Test
    fun versions_order_by_their_numbers_and_a_suffix_comes_first() {
        assertEquals(AppVersion(0, 9, 5), AppVersion.parse("v0.9.5"))
        assertTrue(AppVersion.parse("0.10.0")!! > AppVersion.parse("0.9.12")!!)
        assertTrue(AppVersion.parse("1.0.0-rc.1")!! < AppVersion.parse("1.0.0")!!)
        assertNull(AppVersion.parse("dev"))
        assertNull(AppVersion.parse(""))
    }

    /** SemVer 2.0 §11: a phone on one dev build updates to the next one of the same X.Y.Z. */
    @Test
    fun pre_releases_order_by_their_identifiers() {
        fun v(s: String) = AppVersion.parse(s)!!
        assertTrue(v("0.6.3-dev.131.desktop.g65f0edd") < v("0.6.3-dev.144.desktop.g27d9f57"))
        // Numeric, not lexical.
        assertTrue(v("0.6.3-dev.9") < v("0.6.3-dev.10"))
        assertTrue(v("0.6.3-dev.144.desktop.g27d9f57") < v("0.6.3"))
        assertTrue(v("0.6.2") < v("0.6.3-dev.1"))
        assertEquals(0, v("v0.6.3-dev.144").compareTo(v("0.6.3-dev.144")))
        assertEquals(0, v("0.6.3").compareTo(v("0.6.3")))
        // Numbers rank below words; a shorter list is smaller when the rest is equal.
        assertTrue(v("1.0.0-alpha.1") < v("1.0.0-alpha.beta"))
        assertTrue(v("1.0.0-alpha") < v("1.0.0-alpha.1"))
        assertTrue(v("1.0.0-alpha.beta") < v("1.0.0-beta"))
        assertTrue(v("1.0.0-rc.1") < v("1.0.0"))
        // Build metadata is not a version at all, so it never orders one.
        assertNull(AppVersion.parse("0.6.3+build.5"))
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

    /** A source that records whether each read asked for a refresh. */
    private class RecordingSource(var answer: ReleaseInfo?) : ReleaseSource {
        val refreshes = mutableListOf<Boolean>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun latest(): ReleaseInfo? = latest(false)
        override suspend fun latest(refresh: Boolean): ReleaseInfo? {
            refreshes += refresh
            gate?.await()
            return answer
        }
    }

    @Test
    fun check_now_asks_for_a_refresh_says_checking_then_what_it_found() = runTest {
        val source = RecordingSource(release)
        val prefs = FakePrefs()
        val vm = UpdateViewModel(source, FakeInstaller(), prefs, "v0.9.4", backgroundScope)
        // Tell me: a person's tap checks all the same.
        vm.setMode(UpdateMode.TELL)
        vm.check()
        assertEquals(listOf(false), source.refreshes, "a routine check never asks the hub to refresh")
        assertEquals(ManualCheck.Idle, vm.state.value.manual)

        source.gate = CompletableDeferred()
        val job = vm.checkNow()
        runCurrent()
        assertEquals(ManualCheck.Running, vm.state.value.manual)
        assertEquals("Checking…", updateCheckLine(ManualCheck.Running))
        // A second tap while it runs is the same check.
        assertTrue(vm.checkNow() === job)
        source.gate!!.complete(Unit)
        job.join()
        assertEquals(listOf(false, true), source.refreshes)
        assertEquals(ManualCheck.Done("0.9.4", "0.9.5"), vm.state.value.manual)
        assertEquals("v0.9.5 available", updateCheckLine(vm.state.value.manual))

        // The hub now offers nothing newer than what runs.
        source.gate = null
        source.answer = release.copy(version = "0.9.4")
        vm.checkNow().join()
        assertEquals(ManualCheck.Done("0.9.4", null), vm.state.value.manual)
        assertEquals("Up to date (v0.9.4)", updateCheckLine(vm.state.value.manual))
    }

    @Test
    fun check_now_does_nothing_when_updates_are_off_or_the_platform_updates_elsewhere() = runTest {
        val source = RecordingSource(release)
        val off = UpdateViewModel(source, FakeInstaller(), FakePrefs(), "0.9.4", backgroundScope)
        off.setMode(UpdateMode.OFF)
        off.checkNow().join()
        val ios = UpdateViewModel(source, null, FakePrefs(), "0.9.4", backgroundScope)
        ios.checkNow().join()
        assertEquals(emptyList(), source.refreshes)
        assertEquals(ManualCheck.Idle, off.state.value.manual)
        assertEquals(ManualCheck.Idle, ios.state.value.manual)
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

    private fun tell() = FakePrefs().apply { putStringList(UPDATE_MODE_PREF, listOf("tell")) }

    @Test
    fun updates_install_on_their_own_unless_the_person_said_otherwise() = runTest {
        assertEquals(UpdateMode.AUTO, UpdateViewModel({ release }, FakeInstaller(), FakePrefs(), "0.9.4", backgroundScope).state.value.mode)
        assertEquals(UpdateMode.TELL, UpdateViewModel({ release }, FakeInstaller(), tell(), "0.9.4", backgroundScope).state.value.mode)
    }

    @Test
    fun auto_downloads_on_check_and_installs_once_the_app_leaves_the_screen() = runTest {
        val installer = FakeInstaller()
        val vm = UpdateViewModel({ release }, installer, FakePrefs(), "0.9.4", backgroundScope)
        vm.setForeground(true)
        vm.check()
        runCurrent()
        assertEquals(1, installer.downloads, "found means fetched, with no tap")
        assertIs<UpdatePhase.Ready>(vm.state.value.phase)
        assertEquals(0, installer.installs, "never under someone's thumb")
        vm.setForeground(false)
        runCurrent()
        assertEquals(1, installer.installs)
        assertEquals(UpdatePhase.Handed, vm.state.value.phase)
    }

    @Test
    fun tell_waits_for_a_tap_at_every_step() = runTest {
        val installer = FakeInstaller()
        val vm = UpdateViewModel({ release }, installer, tell(), "0.9.4", backgroundScope)
        vm.check()
        runCurrent()
        assertEquals(0, installer.downloads)
        vm.download()
        runCurrent()
        vm.setForeground(false)
        runCurrent()
        assertIs<UpdatePhase.Ready>(vm.state.value.phase)
        assertEquals(0, installer.installs)
    }

    @Test
    fun the_background_job_checks_downloads_and_installs_only_off_screen() = runTest {
        val away = FakeInstaller()
        UpdateViewModel({ release }, away, FakePrefs(), "0.9.4", backgroundScope).runInBackground(onScreen = false)
        assertEquals(1, away.downloads)
        assertEquals(1, away.installs)

        val shown = FakeInstaller()
        UpdateViewModel({ release }, shown, FakePrefs(), "0.9.4", backgroundScope).runInBackground(onScreen = true)
        assertEquals(1, shown.downloads)
        assertEquals(0, shown.installs)

        val notAllowed = FakeInstaller(allowed = false)
        val vm = UpdateViewModel({ release }, notAllowed, FakePrefs(), "0.9.4", backgroundScope)
        vm.runInBackground(onScreen = false)
        assertEquals(0, notAllowed.installs, "Android's own allow is never skipped")
        assertEquals(UpdatePhase.Ready("martin-janci", needsPermission = true), vm.state.value.phase)

        val told = FakeInstaller()
        UpdateViewModel({ release }, told, tell(), "0.9.4", backgroundScope).runInBackground(onScreen = false)
        assertEquals(0, told.downloads)
    }

    @Test
    fun a_refused_release_is_not_fetched_again_on_its_own() = runTest {
        val installer = FakeInstaller(signature = SignatureCheck.Refused("not ours"))
        val vm = UpdateViewModel({ release }, installer, FakePrefs(), "0.9.4", backgroundScope)
        vm.check()
        runCurrent()
        assertIs<UpdatePhase.Refused>(vm.state.value.phase)
        vm.check()
        runCurrent()
        assertEquals(1, installer.downloads)
    }

    @Test
    fun a_failed_automatic_install_is_said_and_tried_again_at_the_next_check() = runTest {
        val installer = FakeInstaller()
        val vm = UpdateViewModel({ release }, installer, FakePrefs(), "0.9.4", backgroundScope)
        vm.runInBackground(onScreen = false)
        vm.installFailed("STATUS_FAILURE_ABORTED")
        assertEquals(UpdatePhase.Failed(INSTALL_REFUSED, "STATUS_FAILURE_ABORTED"), vm.state.value.phase)
        vm.runInBackground(onScreen = false)
        assertEquals(2, installer.installs)
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
