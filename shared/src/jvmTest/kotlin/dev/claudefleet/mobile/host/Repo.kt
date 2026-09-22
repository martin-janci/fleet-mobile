package dev.claudefleet.mobile.host

import java.io.File
import kotlin.test.fail

/**
 * The repository on disk, for the host tests that read it.
 *
 * [AndroidHostTest] and [IosHostTest] are source scans for the same reason
 * [dev.claudefleet.mobile.net.ToolsTheAppMayCallTest] is one: what they guard is
 * a property of the whole app rather than of a function, and several tasks have
 * now checked the same properties by hand and written the answer in a report. A
 * report is not a gate — it does not fire when someone deletes the line next
 * month.
 *
 * They live in `:shared`'s `jvmTest` because it is the only JVM test source set
 * in this build; `:androidApp` has none, and `iosApp` is Swift with no test
 * target at all. They fail loudly rather than silently passing if the tree is
 * not where they expect it.
 */
internal object Repo {
    /**
     * The repository root, found by climbing from the working directory until
     * `settings.gradle.kts` turns up. Gradle runs a test with the project
     * directory as its working directory, but that is a default rather than a
     * promise.
     */
    val root: File by lazy {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return@lazy dir
            dir = dir.parentFile
        }
        fail("could not find the repository root from ${File(".").absolutePath}")
    }

    fun file(path: String): File = File(root, path).also {
        if (!it.isFile) fail("expected $path under $root")
    }

    /**
     * Every **Kotlin** source that ships in the app: no test source sets.
     *
     * Kotlin only, and that is a real limit rather than an oversight — see
     * [shippedSwift]. `iosApp/` is Swift and outside both roots, so a sweep over
     * this list says nothing about the iOS host.
     */
    val shipped: List<File> by lazy {
        listOf(File(root, "shared/src"), File(root, "androidApp/src"))
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .filter { "commonTest" !in it.path && "jvmTest" !in it.path }
            .toList()
            .also { if (it.isEmpty()) fail("found no shipped Kotlin sources under $root") }
    }

    /**
     * Every Swift source in the iOS host.
     *
     * Separate from [shipped] because the two are swept for different things and
     * because a `.kt`-only sweep that silently covered nothing once `iosApp/`
     * arrived would keep passing while guarding a directory that no longer holds
     * all the host code. That is the failure this list exists to prevent.
     */
    val shippedSwift: List<File> by lazy {
        File(root, "iosApp").walkTopDown()
            .filter { it.isFile && it.extension == "swift" }
            // `shipped`, so the test bundles are out — the same exclusion
            // [shipped] already makes for `commonTest` and `jvmTest`. The rule
            // these lists serve is "app logic lives in :shared, not in the
            // host", and a test that exercises the host is not app logic. They
            // have their own gates rather than being swept in with the two
            // files that do ship.
            //
            // Both bundles, named separately: `iosAppUITests` does not contain
            // the substring `iosAppTests` (the `UI` sits in the middle), so the
            // single check that used to be here silently counted the UI test as
            // shipped host code the day it was added. It failed loudly, which
            // is the system working — but only because something else pinned
            // the file count.
            .filterNot { "iosAppTests" in it.path || "iosAppUITests" in it.path }
            .toList()
            .also { if (it.isEmpty()) fail("found no Swift sources under $root/iosApp") }
    }
}
