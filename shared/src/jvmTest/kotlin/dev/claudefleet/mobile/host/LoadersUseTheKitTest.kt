package dev.claudefleet.mobile.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The phone's loaders are the kit's (review r12, manual: loaders).
 *
 * Material's `CircularProgressIndicator` / `LinearProgressIndicator` show the
 * moment a wait starts (a flash on a quick load), ignore the app's Motion
 * choice and are not the brand's loaders; `PullToRefreshBox` draws Material's
 * arrow instead of the Orbit. The kit wraps all three where it must
 * ([dev.claudefleet.mobile.ui.kit.OrbitPullToRefresh] sits on
 * `PullToRefreshBox`), so `ui/kit/` is the one place allowed to name them.
 * Every other shipped screen uses `Comet`, `OrbitMarkLoader`, `InlineLoading`,
 * `ProgressRing` or `OrbitPullToRefresh`.
 *
 * Any mention counts, an import or a comment too: a comment naming the
 * Material call is how the call comes back.
 */
class LoadersUseTheKitTest {
    @Test
    fun no_screen_outside_the_kit_names_a_material_spinner_or_pull_to_refresh() {
        val roots = listOf("shared/src/commonMain", "androidApp/src/main").map { File(Repo.root, it) }
        roots.forEach { if (!it.isDirectory) fail("expected ${it.path}") }
        val sources = roots.flatMap { it.walkTopDown().toList() }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { "${File.separator}ui${File.separator}kit${File.separator}" in it.path }
        if (sources.size < 20) fail("found only ${sources.size} sources: the tree is not where this test expects it")

        val offenders = sources.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                BANNED.firstOrNull { it in line }?.let { "${f.relativeTo(Repo.root)}:${i + 1}: $it" }
            }
        }
        assertTrue(offenders.isEmpty(), "use the kit's loaders instead:\n" + offenders.joinToString("\n"))
    }

    private companion object {
        val BANNED = listOf("CircularProgressIndicator", "LinearProgressIndicator", "PullToRefreshBox")
    }
}
