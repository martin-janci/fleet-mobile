package dev.claudefleet.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class FleetStatusColors(val dot: Color, val container: Color, val onContainer: Color)

val LocalStatusColors = staticCompositionLocalOf<(StatusTone) -> FleetStatusColors> {
    error("FleetTheme is not applied")
}

/**
 * The Orbit Fleet colours of one theme, by the manual's names. Material's
 * [ColorScheme] is built from these too, so a screen that still asks Material
 * for `onSurfaceVariant` gets `fg-muted`; new components (the phone kit, 14.1)
 * ask here by name.
 */
@Immutable
class OrbitColors internal constructor(val isDark: Boolean) {
    private fun c(name: String) = Color(OrbitTokens.argb(name, isDark))

    val bg = c("bg")
    val bgPane = c("bg-pane")
    val bgRaise = c("bg-raise")
    val bgHover = c("bg-hover")
    val bgSunk = c("bg-sunk")
    val fg = c("fg")
    val fg2 = c("fg-2")
    val fgMuted = c("fg-muted")
    val border = c("border")
    val controlBorder = c("control-border")
    val accent = c("accent")
    val accentFg = c("accent-fg")
    val accentSoft = c("accent-soft")
    val ring = c("ring")
    val statusWorking = c("status-working")
    val statusWaiting = c("status-waiting")
    val statusFailed = c("status-failed")
    val statusDone = c("status-done")
    val statusIdle = c("status-idle")
    val onWaiting = c("on-waiting")
    val waitingSoft = c("waiting-soft")
    val waitingFaint = c("waiting-faint")
    val waitingLine = c("waiting-line")
    val failedSoft = c("failed-soft")
    val failedLine = c("failed-line")
    val doneSoft = c("done-soft")
    val danger = c("danger")
    val dangerFill = c("danger-fill")
    val onDanger = c("on-danger")
    val chipBg = c("chip-bg")
    val countBg = c("count-bg")
    val track = c("track")
    val code = c("code")
    val synKw = c("syn-kw")
    val synStr = c("syn-str")
    val synNum = c("syn-num")
    val org = listOf(c("org-1"), c("org-2"), c("org-3"), c("org-4"))
    val brandInk = c("brand-ink")
    val brandLight = c("brand-light")
    val brandAmber = c("brand-amber")
    val loaderAccent = c("loader-accent")
    val cometHead = c("comet-head")
    val agentClaude = c("agent-claude")
    val scrim = c("scrim")
    val scrimStrong = c("scrim-strong")
    val aiPre = c("ai-pre")
    val usageOk = c("usage-ok")
    val usageWarn = c("usage-warn")
    val usageCrit = c("usage-crit")
    val synCode = c("syn-code")
    val controlBg = c("control-bg")
    val controlBgHover = c("control-bg-hover")
    val controlBgActive = c("control-bg-active")
    val controlBorderStrong = c("control-border-strong")
    val controlFg = c("control-fg")
    val controlFgQuiet = c("control-fg-quiet")
}

/** The manual's text styles: px sizes drawn as sp, so the system font scale still applies. */
@Immutable
class OrbitType internal constructor() {
    private fun style(name: String): TextStyle {
        val t = OrbitTokens.type.first { it.name == name }
        return TextStyle(
            fontSize = t.size.sp,
            lineHeight = t.lineHeight.sp,
            fontWeight = FontWeight(t.weight),
            fontFamily = if (t.mono) FontFamily.Monospace else FontFamily.Default,
        )
    }

    val text2xs = style("text-2xs")
    val textXs = style("text-xs")
    val textSm = style("text-sm")
    val textMd = style("text-md")
    val textLg = style("text-lg")
    val textXl = style("text-xl")
    val code = style("code")
}

private val DarkOrbit = OrbitColors(isDark = true)
private val LightOrbit = OrbitColors(isDark = false)
private val Type = OrbitType()

val LocalOrbitColors = staticCompositionLocalOf<OrbitColors> { error("FleetTheme is not applied") }
val LocalOrbitType = staticCompositionLocalOf<OrbitType> { error("FleetTheme is not applied") }

/** `Fleet.colors.fgMuted`, `Fleet.type.textSm`: the tokens of the theme on screen. */
object Fleet {
    val colors: OrbitColors
        @Composable @ReadOnlyComposable get() = LocalOrbitColors.current
    val type: OrbitType
        @Composable @ReadOnlyComposable get() = LocalOrbitType.current
}

/**
 * Material's roles mapped onto the tokens, so every screen that predates the
 * kit follows the manual without being touched. The mapping keeps each role's
 * meaning: `onSurfaceVariant` is metadata (`fg-muted`, at least 4.5:1 on every
 * surface container, which is why the highest is `chip-bg` and not `bg-hover`),
 * `primary` the accent, `tertiary` the waiting amber the quota meter turns at
 * 80 %, `error` the failed red, outlines the two border weights.
 */
internal fun orbitColorScheme(o: OrbitColors): ColorScheme {
    val base = if (o.isDark) darkColorScheme() else lightColorScheme()
    val inverse = if (o.isDark) LightOrbit else DarkOrbit
    return base.copy(
        primary = o.accent,
        onPrimary = o.accentFg,
        primaryContainer = o.accentSoft,
        onPrimaryContainer = o.fg,
        inversePrimary = inverse.accent,
        secondary = o.fg2,
        onSecondary = o.bg,
        secondaryContainer = o.chipBg,
        onSecondaryContainer = o.fg,
        tertiary = o.statusWaiting,
        onTertiary = o.onWaiting,
        tertiaryContainer = o.waitingSoft,
        onTertiaryContainer = o.fg,
        background = o.bg,
        onBackground = o.fg,
        surface = o.bg,
        onSurface = o.fg,
        surfaceVariant = o.bgSunk,
        onSurfaceVariant = o.fgMuted,
        surfaceTint = o.accent,
        inverseSurface = inverse.bgPane,
        inverseOnSurface = inverse.fg,
        error = o.danger,
        onError = o.onDanger,
        errorContainer = o.failedSoft,
        onErrorContainer = o.fg,
        outline = o.controlBorder,
        outlineVariant = o.border,
        scrim = o.scrim,
        surfaceBright = o.bgRaise,
        surfaceDim = o.bg,
        surfaceContainerLowest = o.bg,
        surfaceContainerLow = o.bgPane,
        surfaceContainer = o.bgPane,
        surfaceContainerHigh = o.bgRaise,
        surfaceContainerHighest = o.chipBg,
    )
}

/**
 * The six status words' colours (Needs you, Working, Failed, Done, Paused,
 * Idle) on the phone's tones. Each `onContainer` is the status colour itself,
 * which the manual keeps text-safe on `bg` and `bg-pane`, except red: light
 * `status-failed` on `failed-soft` over `bg` is 4.35:1, under the 4.5 floor,
 * so a failed or stuck chip writes its word in `fg` and keeps the red for its
 * dot and fill. A stopped or unknown session has no dot and no fill, only the
 * idle grey; a paused one (an account at its limit) has the idle dot. `OrbitThemeContrastTest` holds every pair at 4.5:1.
 */
internal fun orbitStatusColors(o: OrbitColors, tone: StatusTone): FleetStatusColors = when (tone) {
    StatusTone.WORKING -> FleetStatusColors(o.statusWorking, o.chipBg, o.statusWorking)
    StatusTone.IDLE -> FleetStatusColors(o.statusIdle, o.chipBg, o.statusIdle)
    StatusTone.BLOCKED -> FleetStatusColors(o.statusWaiting, o.waitingSoft, o.statusWaiting)
    StatusTone.STUCK -> FleetStatusColors(o.statusFailed, o.failedSoft, o.fg)
    StatusTone.FAILED -> FleetStatusColors(o.statusFailed, o.failedSoft, o.fg)
    StatusTone.COMPLETED -> FleetStatusColors(o.statusDone, o.doneSoft, o.statusDone)
    StatusTone.STOPPED -> FleetStatusColors(Color.Transparent, Color.Transparent, o.statusIdle)
    StatusTone.PAUSED -> FleetStatusColors(o.statusIdle, o.chipBg, o.statusIdle)
    StatusTone.UNKNOWN -> FleetStatusColors(Color.Transparent, Color.Transparent, o.statusIdle.copy(alpha = 0.7f))
}

/** Material's shape scale on the manual's radii: chips and fields, rows, cards, sheets. */
private val OrbitShapes = Shapes(
    extraSmall = RoundedCornerShape(OrbitTokens.radius("radius-sm").dp),
    small = RoundedCornerShape(OrbitTokens.radius("radius-md").dp),
    medium = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp),
    large = RoundedCornerShape(OrbitTokens.radius("radius-sheet").dp),
    extraLarge = RoundedCornerShape(OrbitTokens.radius("radius-sheet").dp),
)

private val DarkScheme = orbitColorScheme(DarkOrbit)
private val LightScheme = orbitColorScheme(LightOrbit)

private val darkStatus: (StatusTone) -> FleetStatusColors = { orbitStatusColors(DarkOrbit, it) }
private val lightStatus: (StatusTone) -> FleetStatusColors = { orbitStatusColors(LightOrbit, it) }

/**
 * The app's theme: the Orbit Fleet tokens, dark or light by the system
 * setting (or [dark], for previews that draw both), laid over Material 3 so existing screens follow them, plus the
 * status colours and the manual's type scale for the phone kit.
 */
@Composable
fun FleetTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val orbit = if (dark) DarkOrbit else LightOrbit
    CompositionLocalProvider(
        LocalStatusColors provides if (dark) darkStatus else lightStatus,
        LocalOrbitColors provides orbit,
        LocalOrbitType provides Type,
    ) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, shapes = OrbitShapes, content = content)
    }
}
