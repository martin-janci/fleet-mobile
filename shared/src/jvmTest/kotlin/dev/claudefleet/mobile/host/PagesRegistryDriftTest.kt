package dev.claudefleet.mobile.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The pages fixture against the hub's own registry.
 *
 * `src/commonTest/fixtures/pages-registry.json` is a hand copy of claude-fleet's
 * `src/lib/pages/registry.generated.json`, and its only freshness mechanism was
 * a comment saying to copy it again. Within days of the feature landing it was
 * three descriptors and three pages behind — and `FleetSettingsTest`'s own KDoc
 * claimed "a renamed field on the hub's side fails here rather than drawing an
 * empty screen", which a frozen copy cannot make true.
 *
 * A **soft** gate, in [HubContractDriftTest]'s style and for the same reason: CI
 * checks out this repository alone, so a test that failed whenever claude-fleet
 * is absent would fail on every run that matters and be deleted within a week.
 * It skips with a printed note instead, and fires on the machine where the copy
 * is actually being made.
 *
 * It compares only the page IDS and setting KEYS, not every field. The fixture
 * is deliberately cut to the fields this app's model declares, so a field-level
 * comparison would report every omission as drift; what matters is that the
 * phone is told about the same pages and settings the hub has.
 */
class PagesRegistryDriftTest {

    @Test
    fun the_fixture_names_the_same_pages_and_settings_as_the_hub() {
        val hub = desktopRegistry()
        if (hub == null) {
            println(
                "PagesRegistryDriftTest: skipped — no $DESKTOP checkout beside ${Repo.root.name}, " +
                    "so $REGISTRY could not be read. The fixture's own parse is still gated by " +
                    "FleetSettingsTest.",
            )
            return
        }

        val fixture = Repo.file("shared/src/commonTest/fixtures/pages-registry.json").readText()
        val hubText = hub.readText()

        assertEquals(
            ids(hubText, "pages", "id"),
            ids(fixture, "pages", "id"),
            "the pages fixture is behind ${hub.path}: re-copy its `pages` and `descriptors`, " +
                "cut to the fields model/FleetSettings.kt declares",
        )
        assertEquals(
            ids(hubText, "descriptors", "key"),
            ids(fixture, "descriptors", "key"),
            "the pages fixture's settings are behind ${hub.path}: re-copy it",
        )
    }

    /** The hub's generated registry, or null when there is no checkout beside this one. */
    private fun desktopRegistry(): File? =
        Repo.root.resolveSibling(DESKTOP).resolve(REGISTRY).takeIf { it.isFile }

    /**
     * Every `"<field>": "…"` inside the top-level `"<array>"`, sorted.
     *
     * A regex over the text rather than a JSON parse: this source set has no
     * serializer for the hub's shape (the fixture's own parse is `commonTest`'s
     * job), and the identifiers are what the comparison is about. Keyed on the
     * array's own slice so a `descriptors` key cannot be read as a page id.
     */
    private fun ids(text: String, array: String, field: String): List<String> {
        val start = text.indexOf("\"$array\"").takeIf { it >= 0 }
            ?: fail("no top-level \"$array\" array")
        val others = listOf("pages", "descriptors", "actions", "resources", "sources")
            .filter { it != array }
            .mapNotNull { text.indexOf("\"$it\"", start + array.length + 2).takeIf { i -> i > 0 } }
        val end = others.minOrNull() ?: text.length
        val slice = text.substring(start, end)
        // The two files are written with different indents (this one 1 space,
        // the hub's generator 2), so the depth is not fixed — the ARRAY SLICE
        // above is what keeps the two fields apart. It has to: a section item's
        // `key` is a setting key too, and reading those as descriptors would
        // make the comparison pass on a fixture that had lost every descriptor.
        return Regex("""\n\s{2,8}"$field": "([^"]+)"""")
            .findAll(slice)
            .map { it.groupValues[1] }
            .sorted()
            .toList()
            .also { if (it.isEmpty()) fail("found no \"$field\" in \"$array\"") }
    }

    private companion object {
        const val DESKTOP = "claude-fleet"
        const val REGISTRY = "src/lib/pages/registry.generated.json"
    }
}
