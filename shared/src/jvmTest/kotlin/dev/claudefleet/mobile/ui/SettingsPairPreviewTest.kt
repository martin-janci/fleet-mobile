package dev.claudefleet.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.claudefleet.mobile.model.Page
import dev.claudefleet.mobile.model.SettingProposal
import dev.claudefleet.mobile.notify.BackgroundNotifier
import dev.claudefleet.mobile.notify.NotifyKind
import dev.claudefleet.mobile.notify.NotifyKinds
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetTheme
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** A notifier that can be offered, with notifications still off: what a fresh pair sees. */
private object OfferedNotifier : BackgroundNotifier {
    override val supported: Boolean = true
    override val enabled: StateFlow<Boolean> = MutableStateFlow(false)
    override fun setEnabled(on: Boolean) = Unit
}

/**
 * Pairing and Settings (redesign 14.11) drawn in both themes, to
 * `shared/build/kit-previews/`, which CI uploads as the `kit-previews`
 * artifact: the screens a reviewer holds next to the MobileSettings board.
 * Each shot also checks it drew on the theme's own ground.
 */
class SettingsPairPreviewTest {

    private val out = File(System.getProperty("user.dir"), "build/kit-previews").apply { mkdirs() }
    private val scale = 2.625f
    private val widthDp = 360

    private fun shot(name: String, heightDp: Int = 760, content: @Composable () -> Unit) {
        for (dark in listOf(true, false)) {
            val scene = ImageComposeScene(
                width = (widthDp * scale).toInt(),
                height = (heightDp * scale).toInt(),
                density = Density(scale),
                content = {
                    FleetTheme(dark = dark) {
                        Box(Modifier.fillMaxSize().background(Fleet.colors.bg)) { content() }
                    }
                },
            )
            try {
                val image = scene.render()
                val bitmap = Bitmap.makeFromImage(image)
                val ground = OrbitTokens.argb("bg", dark).toInt()
                assertEquals(
                    Integer.toHexString(ground),
                    Integer.toHexString(bitmap.getColor(2, (heightDp * scale).toInt() / 2)),
                    "$name (${if (dark) "dark" else "light"}) is not drawn on the theme's bg",
                )
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("could not encode $name")
                File(out, "$name-${if (dark) "dark" else "light"}.png").writeBytes(data.bytes)
            } finally {
                scene.close()
            }
        }
    }

    @Composable
    private fun PairShot(state: PairUiState) {
        PairScreen(
            state = state,
            onAddressChange = {},
            onCodeChange = {},
            onSubmit = {},
            onScanningChange = {},
            onScanned = {},
            onScannerUnavailable = {},
            onDismissError = {},
            onDismissReason = {},
        )
    }

    @Test
    fun pairing() {
        shot("pair-qr-first") { PairShot(PairUiState(cameraAvailable = true)) }
        shot("pair-signed-out-repair") {
            PairShot(
                PairUiState(
                    cameraAvailable = true,
                    manual = true,
                    address = "https://fleet.janci.dev",
                    reason = "you forgot this hub on this phone. Its token stays good on the hub until the operator cancels it there.",
                ),
            )
        }
        shot("pair-typed-error") {
            PairShot(
                PairUiState(
                    cameraAvailable = false,
                    manual = true,
                    address = "https://fleet.janci.dev",
                    code = "K7QF29XE",
                    error = "that code was not accepted. Codes last ten minutes; make a new one on the desktop.",
                ),
            )
        }
        shot("pair-contacting") {
            PairShot(
                PairUiState(
                    cameraAvailable = true,
                    manual = true,
                    address = "https://fleet.janci.dev",
                    code = "K7QF29XD",
                    pairing = true,
                    contacting = "fleet.janci.dev",
                ),
            )
        }
    }

    @Test
    fun paired() {
        val hub = PairedHub("https://fleet.janci.dev", "Pixel 9 Pro", "full")
        shot("paired-ask-notifications") { PairedScreen(hub, onContinue = {}, notifier = OfferedNotifier) }
        shot("paired-readonly") { PairedScreen(hub.copy(mode = "readonly"), onContinue = {}) }
    }

    private val settings = SettingsUiState(
        hub = "https://fleet.janci.dev",
        clientName = "Pixel 9 Pro",
        mode = "full",
        appVersion = "0.9.4",
        hubVersion = "0.9.4",
    )

    private val fleet = FleetSettingsUiState(
        loaded = true,
        canWrite = true,
        pages = listOf(
            Page("settings", "Settings", layout = "form"),
            Page("settings.automation", "Automation", intro = "Playbooks and garbage collection.", layout = "form"),
            Page("settings.limits", "Limits", intro = "Timeouts, how long lost sessions stay, the context threshold.", layout = "form"),
            Page("settings.projects", "Projects", intro = "Where projects live on each host.", layout = "form"),
            Page("settings.trackers", "Trackers", intro = "Jira, Linear and GitHub connections.", layout = "form"),
            Page("settings.work", "Work graph", intro = "Tracker sync, recent work, linking.", layout = "form"),
            Page("settings.decisions", "Decisions (Jev)", intro = "Off, shadow or assist; a model only pre-selects.", layout = "form"),
            Page("settings.hub", "Hub daemon", intro = "Read only here.", layout = "form"),
            Page("settings.control_api", "Control API", intro = "Read only here.", layout = "form"),
            Page("settings.updates", "Updates", intro = "Release track and per-component updates.", layout = "form"),
            Page("settings.review", "Proposed changes", intro = "Changes an agent or Jev proposed.", layout = "review_apply"),
            Page("settings.orgs", "Organisations", intro = "Members, roles, devices, budgets.", layout = "form"),
        ),
        proposals = listOf(SettingProposal(id = 1, key = "health.context_red_pct", value = "80")),
    )

    private val input = OrbitSettingsInput(settings, fleet, ThemeChoice.DARK, NotifyKinds().with(NotifyKind.FAILED, on = true))

    @Test
    fun settings() {
        val handlers = OrbitSettingsHandlers(onOpenUsage = {}, onOpenCompany = {})
        shot("settings-home", heightDp = 900) { OrbitSettingsScreen(SettingsPlace.Home, input, handlers) }
        shot("settings-this-phone") { OrbitSettingsScreen(SettingsPlace.ThisPhone, input, handlers) }
        shot("settings-general") { OrbitSettingsScreen(SettingsPlace.Group(SettingsGroup.GENERAL), input, handlers) }
        shot("settings-work") { OrbitSettingsScreen(SettingsPlace.Group(SettingsGroup.WORK), input, handlers) }
    }
}
