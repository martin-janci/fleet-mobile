package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The promises a shared contract makes, kept by every platform that implements
 * it — including the one nobody here can run.
 *
 * iOS code compiles on this machine and never executes, so a contract broken
 * only on iOS is invisible to every behavioural test in the build. Both of these
 * were: `KeychainFailure` was a bare `Exception`, and the iOS scanner never
 * reported a refused camera permission. Source scans, with comments stripped
 * first — the iOS scanner names the constant in a comment, and a scan that
 * reads comments guards documentation rather than code.
 */
class PlatformActualsTest {

    /**
     * Every throwable a secure store raises is a `SecretsUnavailable`.
     *
     * `explain()` repeats the message of exactly three throwable types and
     * reduces everything else to "something went wrong (…)". `Secrets.clear()`
     * promises a `SecretsUnavailable`, Android kept that promise and iOS did not,
     * so the same refusal read as words on one platform and as a class name on
     * the other.
     */
    @Test
    fun a_secure_store_only_throws_what_explain_can_put_in_words() {
        val stores = Repo.shipped.filter { it.name.startsWith("Secrets.") && it.name != "Secrets.kt" }
        assertEquals(listOf("Secrets.android.kt", "Secrets.ios.kt"), stores.map { it.name }.sorted())

        for (file in stores) {
            val code = withoutComments(file.readText())
            val thrown = THROW.findAll(code).map { it.groupValues[1] }.toSet()
            assertTrue(thrown.isNotEmpty(), "${file.name} throws nothing — has the scan gone stale?")

            val promised = setOf("SecretsUnavailable") +
                SUBCLASS.findAll(code).map { it.groupValues[1] }.toSet()
            assertEquals(
                emptySet(),
                thrown - promised,
                "${file.name} throws something that is not a SecretsUnavailable, " +
                    "so explain() will show a class name instead of the store's words",
            )
        }
    }

    /**
     * Every platform with a camera tells the Pair screen when the permission is
     * refused. Without it iOS starts the capture session anyway and the
     * viewfinder is black, with no hint that the code can be typed.
     */
    @Test
    fun every_platform_with_a_scanner_reports_a_refused_permission() {
        val scanners = Repo.shipped
            .filter { it.name.startsWith("QrScanner.") && it.name != "QrScanner.kt" }
            .map { it.name to withoutComments(it.readText()) }
            .filter { (_, code) -> "fun qrScannerSupported(): Boolean = true" in code }
        assertEquals(listOf("QrScanner.android.kt", "QrScanner.ios.kt"), scanners.map { it.first }.sorted())

        val silent = scanners.filterNot { (_, code) -> REFUSED.containsMatchIn(code) }.map { it.first }
        assertEquals(emptyList(), silent, "a refused camera permission is never reported here")
    }

    /**
     * Every platform with a file picker tells the caller when nothing was
     * picked. Without it the attach spinner, started when the picker opened,
     * never stops — and the screen is stuck on a sheet that has already gone.
     *
     * The two platforms keep the promise in two different ways and the scan
     * accepts either, which is the point of checking for evidence rather than
     * for one spelling:
     *
     *  - Android gets it from the contract. `OpenMultipleDocuments` calls its
     *    result callback with an empty list on cancel, so passing the list
     *    straight through is the whole implementation.
     *  - iOS has to be told. `UIDocumentPickerViewController` reports a cancel
     *    only through `documentPickerWasCancelled`, an *optional* delegate
     *    method — leave it out and the picker closes in total silence. That is
     *    the iOS-only break this test exists for, exactly like the refused
     *    camera permission above: nothing in this build can run it.
     */
    @Test
    fun every_platform_with_a_picker_reports_a_cancelled_pick() {
        val pickers = Repo.shipped
            .filter { it.name.startsWith("FilePicker.") && it.name != "FilePicker.kt" }
            .map { it.name to withoutComments(it.readText()) }
            .filter { (_, code) -> "fun filePickerSupported(): Boolean = true" in code }
        assertEquals(listOf("FilePicker.android.kt", "FilePicker.ios.kt"), pickers.map { it.first }.sorted())

        val silent = pickers.filterNot { (_, code) -> CANCELLED.containsMatchIn(code) }.map { it.first }
        assertEquals(emptyList(), silent, "a cancelled pick is never reported here, so the caller waits forever")
    }

    private fun withoutComments(source: String): String =
        source.replace(BLOCK_COMMENT, "").lines().joinToString("\n") { it.substringBefore("//") }

    private companion object {
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val THROW = Regex("""throw\s+(\w+)\s*\(""")
        val SUBCLASS = Regex("""class\s+(\w+)\s*\([^)]*\)\s*:\s*SecretsUnavailable\s*\(""")
        val REFUSED = Regex("""\w*navailable\(\s*CAMERA_PERMISSION_REFUSED\s*\)""")
        val CANCELLED = Regex("""documentPickerWasCancelled|ActivityResultContracts\.OpenMultipleDocuments\(\)""")
    }
}
