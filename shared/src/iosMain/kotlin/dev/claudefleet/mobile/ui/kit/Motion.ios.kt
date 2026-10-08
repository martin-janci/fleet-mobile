package dev.claudefleet.mobile.ui.kit

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** Settings › Accessibility › Motion › Reduce Motion. */
@Composable
actual fun systemReducedMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()
