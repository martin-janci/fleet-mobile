package dev.claudefleet.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class FleetStatusColors(val dot: Color, val container: Color, val onContainer: Color)

val LocalStatusColors = staticCompositionLocalOf<(StatusTone) -> FleetStatusColors> {
    error("FleetTheme is not applied")
}

private fun light(tone: StatusTone) = when (tone) {
    StatusTone.WORKING -> FleetStatusColors(Color(0xFF2F6BFF), Color(0xFFE3ECFF), Color(0xFF0B3D91))
    StatusTone.IDLE -> FleetStatusColors(Color(0xFF8A8891), Color(0xFFEEEDF2), Color(0xFF4B4A52))
    StatusTone.BLOCKED -> FleetStatusColors(Color(0xFFE58A00), Color(0xFFFFE8C2), Color(0xFF6B3D00))
    StatusTone.STUCK -> FleetStatusColors(Color(0xFFD32F2F), Color(0xFFFFDAD6), Color(0xFF93000A))
    StatusTone.FAILED -> FleetStatusColors(Color(0xFFD32F2F), Color(0xFFFFDAD6), Color(0xFF93000A))
    StatusTone.COMPLETED -> FleetStatusColors(Color(0xFF1E8E3E), Color(0xFFD6F0DD), Color(0xFF0F5A2A))
    StatusTone.STOPPED -> FleetStatusColors(Color.Transparent, Color.Transparent, Color(0xFF4B4A52))
    StatusTone.UNKNOWN -> FleetStatusColors(Color.Transparent, Color.Transparent, Color(0xB34B4A52))
}

private fun dark(tone: StatusTone) = when (tone) {
    StatusTone.WORKING -> FleetStatusColors(Color(0xFF7FA3FF), Color(0xFF1B2D55), Color(0xFFB7CBFF))
    StatusTone.IDLE -> FleetStatusColors(Color(0xFF8A8891), Color(0xFF2C2B33), Color(0xFFC6C4CE))
    StatusTone.BLOCKED -> FleetStatusColors(Color(0xFFFFB74D), Color(0xFF4A3000), Color(0xFFFFD08A))
    StatusTone.STUCK -> FleetStatusColors(Color(0xFFFF8A80), Color(0xFF5C1A17), Color(0xFFFFB4AB))
    StatusTone.FAILED -> FleetStatusColors(Color(0xFFFF8A80), Color(0xFF5C1A17), Color(0xFFFFB4AB))
    StatusTone.COMPLETED -> FleetStatusColors(Color(0xFF7CD292), Color(0xFF143D25), Color(0xFF9FE0B4))
    StatusTone.STOPPED -> FleetStatusColors(Color.Transparent, Color.Transparent, Color(0xFFC6C4CE))
    StatusTone.UNKNOWN -> FleetStatusColors(Color.Transparent, Color.Transparent, Color(0xB3C6C4CE))
}

/** The app's theme: Material 3 light or dark by the system setting, plus the status tokens. */
@Composable
fun FleetTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(LocalStatusColors provides if (dark) ::dark else ::light) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
