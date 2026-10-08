package dev.claudefleet.mobile.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetTheme
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The phone's states and full-screen loaders (redesign step 14.12), driven
 * by the frame clock: the time a scene is rendered at is the time the
 * composition sees, so "nothing before 400 ms" and "offline after 6 s" are
 * checked at the millisecond, not slept through.
 *
 * Also draws every state in both themes to `shared/build/kit-previews/`
 * (CI's `kit-previews` artifact), next to 14.1's kit, to hold against the
 * MobileStates and MobileFullscreenLoaders boards.
 */
class PhoneStatesUiTest {

    private val out = File(System.getProperty("user.dir"), "build/kit-previews").apply { mkdirs() }
    private val scale = 2.625f
    private val widthDp = 360

    private fun ms(millis: Long): Long = millis * 1_000_000L

    /** A themed scene; [frames] are the times (ms) it is rendered at, and the last frame is returned as a bitmap. */
    private fun render(
        heightDp: Int,
        frames: List<Long>,
        dark: Boolean = true,
        reduced: Boolean = false,
        content: @Composable () -> Unit,
    ): Bitmap {
        val scene = ImageComposeScene(
            width = (widthDp * scale).toInt(),
            height = (heightDp * scale).toInt(),
            density = Density(scale),
            content = {
                FleetTheme(dark = dark) {
                    CompositionLocalProvider(LocalReducedMotion provides reduced) {
                        Box(Modifier.fillMaxSize().background(Fleet.colors.bg)) { content() }
                    }
                }
            },
        )
        try {
            var image = scene.render(ms(frames.first()))
            for (t in frames.drop(1)) image = scene.render(ms(t))
            return Bitmap.makeFromImage(image)
        } finally {
            scene.close()
        }
    }

    private fun Bitmap.at(xDp: Int, yDp: Int): Int = getColor((xDp * scale).toInt(), (yDp * scale).toInt())

    private fun ground(dark: Boolean): Int = OrbitTokens.argb("bg", dark).toInt()

    /** Renders [content] in both themes after its loader is due, checks the ground, and writes the PNGs. */
    private fun shot(name: String, heightDp: Int, reduced: Boolean = false, content: @Composable () -> Unit) {
        for (dark in listOf(true, false)) {
            val scene = ImageComposeScene(
                width = (widthDp * scale).toInt(),
                height = (heightDp * scale).toInt(),
                density = Density(scale),
                content = {
                    FleetTheme(dark = dark) {
                        CompositionLocalProvider(LocalReducedMotion provides reduced) {
                            Column(Modifier.fillMaxSize().background(Fleet.colors.bg).padding(vertical = 16.dp)) { content() }
                        }
                    }
                },
            )
            try {
                var image = scene.render(0)
                for (t in listOf(16L, 500L, 900L, 1_300L)) image = scene.render(ms(t))
                val bitmap = Bitmap.makeFromImage(image)
                assertEquals(
                    Integer.toHexString(ground(dark)),
                    Integer.toHexString(bitmap.getColor(2, 2)),
                    "$name (${if (dark) "dark" else "light"}) is not drawn on the theme's bg",
                )
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("could not encode $name")
                File(out, "$name-${if (dark) "dark" else "light"}.png").writeBytes(data.bytes)
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun no_loader_shows_before_400_ms() {
        var shown = false
        val scene = ImageComposeScene(width = 10, height = 10, density = Density(1f)) {
            shown = rememberLoaderVisible(waiting = true)
        }
        try {
            for (t in listOf(0L, 16L, 200L, 399L)) {
                scene.render(ms(t))
                assertFalse(shown, "a loader showed at $t ms")
            }
            for (t in listOf(400L, 800L, 816L)) scene.render(ms(t))
            assertTrue(shown, "the loader never showed after 800 ms of waiting")
        } finally {
            scene.close()
        }
    }

    @Test
    fun a_wait_that_ends_hides_its_loader_at_once() {
        var shown = false
        val waiting = mutableStateOf(true)
        val scene = ImageComposeScene(width = 10, height = 10, density = Density(1f)) {
            shown = rememberLoaderVisible(waiting = waiting.value)
        }
        try {
            for (t in listOf(0L, 500L, 900L)) scene.render(ms(t))
            assertTrue(shown)
            waiting.value = false
            scene.render(ms(916))
            assertFalse(shown, "the loader outlived its wait")
        } finally {
            scene.close()
        }
    }

    @Test
    fun a_reconnect_becomes_offline_after_6_s_and_live_again_when_it_connects() {
        var seen: PhoneConnection? = null
        val status = mutableStateOf<ConnectionStatus>(ConnectionStatus.Reconnecting(2, "the network dropped"))
        val scene = ImageComposeScene(width = 10, height = 10, density = Density(1f)) {
            seen = rememberPhoneConnection(status.value)
        }
        try {
            for (t in listOf(0L, 16L, 3_000L, 5_990L)) scene.render(ms(t))
            assertEquals(PhoneConnection.Reconnecting(2), seen)
            for (t in listOf(6_100L, 6_200L, 6_216L)) scene.render(ms(t))
            assertEquals(PhoneConnection.Offline("the network dropped"), seen)
            status.value = ConnectionStatus.Connected("0.5.4")
            scene.render(ms(6_300))
            assertEquals(PhoneConnection.Live, seen)
        } finally {
            scene.close()
        }
    }

    @Test
    fun the_reconnecting_banner_draws_nothing_before_400_ms() {
        val banner: @Composable () -> Unit = { HubBanner(PhoneConnection.Reconnecting(2)) }
        val early = render(heightDp = 120, frames = listOf(0, 16, 300), content = banner)
        assertEquals(ground(true), early.at(330, 14), "the reconnecting banner drew before 400 ms")
        val late = render(heightDp = 120, frames = listOf(0, 16, 500, 900), content = banner)
        assertNotEquals(ground(true), late.at(330, 14), "the reconnecting banner never drew")
    }

    @Test
    fun the_offline_banner_draws_at_once_because_it_is_a_state_not_a_wait() {
        val bitmap = render(heightDp = 120, frames = listOf(0, 16)) {
            HubBanner(PhoneConnection.Offline("the hub did not answer"), asOf = "14:52", onRetry = {})
        }
        assertNotEquals(ground(true), bitmap.at(330, 14), "the offline banner did not draw")
    }

    @Test
    fun a_live_hub_draws_no_banner() {
        val bitmap = render(heightDp = 120, frames = listOf(0, 16, 900)) { HubBanner(PhoneConnection.Live) }
        assertEquals(ground(true), bitmap.at(330, 14))
        assertEquals(ground(true), bitmap.at(40, 30))
    }

    @Test
    fun full_screen_loaders_wait_400_ms_too() {
        val fleetCheck: @Composable () -> Unit = {
            FullscreenLoader(FullscreenWait.FleetCheck, title = "Checking your fleet", onExit = {})
        }
        val early = render(heightDp = 640, frames = listOf(0, 16, 390), content = fleetCheck)
        assertEquals(ground(true), early.at(180, 275), "the Hex field drew before 400 ms")
        val late = render(heightDp = 640, frames = listOf(0, 16, 500, 900), content = fleetCheck)
        assertNotEquals(ground(true), late.at(180, 275), "the Hex field never drew")
    }

    @Test
    fun previews_of_the_states() {
        shot("state-hub-offline", heightDp = 130) {
            HubBanner(PhoneConnection.Offline(null), asOf = "14:52", onRetry = {})
        }
        shot("state-hub-reconnecting", heightDp = 110) { HubBanner(PhoneConnection.Reconnecting(3)) }
        shot("state-hub-refused", heightDp = 110) {
            HubBanner(PhoneConnection.Refused("This hub speaks contract 9; the app needs 10 or newer."))
        }
        shot("state-reconnecting-panel", heightDp = 420) { ReconnectingPanel("fleet.janci.dev", onShowLastKnown = {}) }
        shot("state-pull-to-refresh", heightDp = 220) {
            PullOrbit(progress = 0.55f, refreshing = false)
            PullOrbit(progress = 1f, refreshing = false)
            PullOrbit(progress = 1f, refreshing = true)
        }
        shot("state-conversation-loading", heightDp = 360) { ConversationLoading(waiting = true) }
    }

    @Test
    fun previews_of_the_full_screen_loaders() {
        shot("fullscreen-fleet-check", heightDp = 640) {
            FullscreenLoader(
                FullscreenWait.FleetCheck,
                title = "Checking your fleet",
                meta = "fleet.janci.dev · first look from this phone",
                onExit = {},
                steps = listOf(
                    LoaderStep("Hub answers", StepState.Done, "42 ms"),
                    LoaderStep("5 hosts", StepState.Done, "4 reachable"),
                    LoaderStep("22 sessions, 9 worktrees", StepState.Running),
                    LoaderStep("Accounts and limits", StepState.Pending),
                    LoaderStep("Notifications", StepState.Pending),
                ),
            )
        }
        shot("fullscreen-repair", heightDp = 640) {
            FullscreenLoader(
                FullscreenWait.Repair,
                title = "Repairing Api tenant resolution",
                meta = "oci-arm · claude/tenant-fix",
                onExit = {},
                steps = listOf(
                    LoaderStep("Worktree is a clean git checkout", StepState.Done),
                    LoaderStep("tmux pane 4 restarted", StepState.Done),
                    LoaderStep("Re-attaching the conversation", StepState.Running),
                    LoaderStep("Checking for uncommitted files", StepState.Pending),
                ),
                note = "You can leave; the result lands in the conversation.",
            )
        }
        shot("fullscreen-repair-reduced-motion", heightDp = 640, reduced = true) {
            FullscreenLoader(
                FullscreenWait.Repair,
                title = "Repairing Api tenant resolution",
                meta = "oci-arm · claude/tenant-fix",
                onExit = {},
                steps = listOf(
                    LoaderStep("Worktree is a clean git checkout", StepState.Done),
                    LoaderStep("Re-attaching the conversation", StepState.Running),
                ),
            )
        }
        shot("fullscreen-find-hosts", heightDp = 640) {
            FullscreenLoader(
                FullscreenWait.FindHosts,
                title = "Looking for hosts",
                meta = "On your network and in ~/.ssh/config on mercury. Found hosts appear below as they answer.",
                onExit = {},
                blips = listOf(
                    radarSpot(0).let { (x, y) -> RadarBlip(x, y) },
                    radarSpot(1).let { (x, y) -> RadarBlip(x, y, ready = false) },
                ),
                secondary = "Enter an address by hand" to {},
            ) {
                FoundHostRow("nas.local", "SSH · tmux 3.3a · claude 2.1.4", "Add") {}
                FoundHostRow("pi-garage", "SSH · no tmux", "Details") {}
            }
        }
        shot("fullscreen-first-import", heightDp = 640) {
            FullscreenLoader(
                FullscreenWait.FirstImport,
                title = "Building your fleet",
                meta = "The hub found 3 hosts and is importing 14 Claude conversations it has never seen. This happens once.",
                onExit = {},
                progress = LoaderProgress(7, 14, "imported"),
            )
        }
    }

    @Test
    fun previews_of_the_mark_motions() {
        shot("loader-marks", heightDp = 120) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (m in MarkMotion.entries) OrbitMarkLoader(m, size = 56.dp)
            }
        }
    }
}
