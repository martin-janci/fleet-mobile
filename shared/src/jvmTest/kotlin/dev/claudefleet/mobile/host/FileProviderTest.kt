package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Files tab's Share / Open on Android hand out a `content://` URI from the
 * app's `FileProvider`. Three files have to agree for that to work, and none
 * of them is compiled against the others: the manifest's authority, the
 * suffix `FileHandoff.android.kt` builds the same authority from, and the
 * paths XML that decides what the provider may serve. A mismatch is a crash
 * on the first Share (`IllegalArgumentException: Failed to find configured
 * root`), on a device, where nothing here runs.
 *
 * So: the authority is `${applicationId}.files` in both places, the provider
 * is not exported, and the only root it serves is `cache/downloads/` — the
 * directory `downloadCachePath` writes to — never the app's files or the
 * whole cache.
 */
class FileProviderTest {
    private val manifest by lazy { Repo.file("androidApp/src/main/AndroidManifest.xml").readText() }
    private val paths by lazy { Repo.file("androidApp/src/main/res/xml/file_paths.xml").readText().withoutXmlComments() }
    private val handoff by lazy {
        Repo.file("shared/src/androidMain/kotlin/dev/claudefleet/mobile/ui/FileHandoff.android.kt").readText()
    }

    @Test
    fun the_manifest_and_the_code_name_the_same_authority() {
        val provider = PROVIDER.find(manifest.withoutXmlComments())?.value
            ?: fail("no androidx FileProvider declared in the app's manifest")
        val authority = Regex("""android:authorities="\$\{applicationId\}(\.[a-z]+)"""").find(provider)?.groupValues?.get(1)
            ?: fail("the provider's authority is not \${applicationId}.<suffix>")
        val suffix = Regex("""FILE_PROVIDER_SUFFIX = "([^"]+)"""").find(handoff)?.groupValues?.get(1)
            ?: fail("FileHandoff.android.kt no longer names its authority suffix")

        assertEquals(authority, suffix, "the code would ask for an authority the manifest does not declare")
        assertTrue("android:exported=\"false\"" in provider, "a FileProvider must not be exported")
        assertTrue("android:grantUriPermissions=\"true\"" in provider, "without grants, no other app can read the URI")
        assertTrue("@xml/file_paths" in provider)
    }

    @Test
    fun the_provider_serves_only_the_downloads_cache() {
        val roots = ROOT.findAll(paths).map { it.groupValues[1] to it.groupValues[2] }.toList()

        assertEquals(listOf("cache-path" to "downloads/"), roots, "the provider exposes more than the downloaded copies")
        val cachePath = Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/data/DownloadActions.kt").readText()
        assertTrue("/downloads/\${download.id}/" in cachePath, "downloads are no longer cached where the provider serves")
    }

    private fun String.withoutXmlComments(): String = replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private companion object {
        val PROVIDER = Regex("""<provider\s[^>]*androidx\.core\.content\.FileProvider[\s\S]*?</provider>""")
        val ROOT = Regex("""<([a-z-]+-path)\s[^>]*path="([^"]*)"""")
    }
}
