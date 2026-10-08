package dev.claudefleet.mobile.ui.kit

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android has no single "reduce motion" switch; "Remove animations" (and the
 * developer option "Animator duration scale: off") set the animator scale to
 * 0, which is what every platform animation honours, so loaders honour it too.
 */
@Composable
actual fun systemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
