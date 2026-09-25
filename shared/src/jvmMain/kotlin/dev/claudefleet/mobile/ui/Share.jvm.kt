package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/** The JVM target exists for the tests; it has no share sheet, so the text goes to the clipboard. */
@Composable
internal actual fun rememberShareText(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return { clipboard.setText(AnnotatedString(it)) }
}
