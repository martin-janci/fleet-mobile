package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** The JVM target exists for the tests; it caches under the temp directory and hands nothing on. */
@Composable
internal actual fun rememberFileHandoff(): FileHandoff = remember { JvmFileHandoff }

private object JvmFileHandoff : FileHandoff {
    override val cacheDir: String = System.getProperty("java.io.tmpdir").trimEnd('/') + "/fleet-mobile"
    override val offered: List<Handoff> = emptyList()
    override suspend fun perform(action: Handoff, path: String, name: String): String? =
        throw HandoffFailed("This build has nowhere to hand a file on to.")
}
