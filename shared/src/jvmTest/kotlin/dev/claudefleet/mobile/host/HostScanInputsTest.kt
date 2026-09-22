package dev.claudefleet.mobile.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Everything the host tests read is a declared input of the task that runs them.
 *
 * This is the gate for a bug that has now landed four times, each time in a
 * different file and each time invisible: a host test starts reading some path,
 * nobody adds it to `jvmTest`'s inputs, and from then on editing that path
 * leaves the task UP-TO-DATE. The tests do not fail — they do not *run*. The
 * last instance sat behind the comment "`shared/src` needs no entry: it is
 * already the compilation's own input", which is true of `commonMain` and
 * false of `iosMain`.
 *
 * So rather than remembering, derive it. The paths these tests read are
 * literals in their own source, and the declared inputs are a literal list in
 * `shared/build.gradle.kts`; this compares the two. It is a source scan about
 * source scans, which is a little recursive, but the alternative is the comment
 * that was already there and did not hold.
 */
class HostScanInputsTest {

    /** `for (dir in listOf("a", "b"))` and `rootProject.files("c", "d")`. */
    private val declared: List<String> by lazy {
        val build = Repo.file("shared/build.gradle.kts").readText()
        val jvmTestBlock = build.substringAfter("""it.name == "jvmTest"""", "")
        if (jvmTestBlock.isBlank()) fail("could not find the jvmTest input block in shared/build.gradle.kts")

        val dirs = Regex("""for \(dir in listOf\(([^)]*)\)\)""").find(jvmTestBlock)
            ?: fail("the jvmTest input block no longer declares directories with `for (dir in listOf(...))`")
        val files = Regex("""rootProject\.files\(([^)]*)\)""").find(jvmTestBlock)
            ?: fail("the jvmTest input block no longer declares files with `rootProject.files(...)`")

        (dirs.groupValues[1] + "," + files.groupValues[1])
            .let { Regex("\"([^\"]+)\"").findAll(it) }
            .map { it.groupValues[1] }
            .toList()
            .also { assertTrue(it.isNotEmpty(), "parsed no declared inputs") }
    }

    /**
     * Paths the tests read: every repo path literal in `jvmTest`, plus the
     * roots `Repo` itself walks.
     */
    private val read: Set<String> by lazy {
        val sources = File(Repo.root, "shared/src/jvmTest")
        // Comments stripped first. This test's own prose quotes the call it
        // scans for, as an example of what it scans for, and a gate that
        // reports its own documentation as an undeclared path is a gate
        // somebody deletes.
        val block = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        // `(?<!:)` so a `https://…` inside a string literal is not mistaken
        // for the start of a line comment and the rest of the line dropped.
        val line = Regex("(?<!:)//[^\\n]*")
        val texts = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { line.replace(block.replace(it.readText(), " "), " ") }
        val paths = texts.flatMap { text ->
            Regex("""Repo\.file\("([^"]+)"\)""").findAll(text).map { it.groupValues[1] } +
                Regex("""File\(root, "([^"]+)"\)""").findAll(text).map { it.groupValues[1] }
        }.toSet()
        paths.also { assertTrue(it.size >= 3, "found suspiciously few scanned paths: $it") }
    }

    @Test
    fun every_path_the_host_tests_read_is_a_declared_input() {
        val undeclared = read.filterNot { path ->
            declared.any { path == it || path.startsWith("$it/") }
        }

        assertTrue(
            undeclared.isEmpty(),
            "these paths are read by a host test but are not inputs of `jvmTest`, so editing " +
                "them leaves the task UP-TO-DATE and the test silently does not run: $undeclared. " +
                "Add the containing directory to the list in shared/build.gradle.kts. " +
                "(declared: $declared)",
        )
    }

    /**
     * And every declared input exists.
     *
     * A typo in the list is worse than a missing entry: Gradle takes
     * `inputs.dir` on a path that is not there without complaint, so the entry
     * looks present, guards nothing, and the UP-TO-DATE hole is open again
     * behind a line that appears to close it.
     */
    @Test
    fun every_declared_input_is_really_there() {
        val missing = declared.filterNot { File(Repo.root, it).exists() }

        assertTrue(
            missing.isEmpty(),
            "declared as a `jvmTest` input but not present in the tree: $missing. " +
                "Gradle accepts a path that does not exist, so this guards nothing.",
        )
    }
}
