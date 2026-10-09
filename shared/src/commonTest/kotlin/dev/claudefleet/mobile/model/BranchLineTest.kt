package dev.claudefleet.mobile.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Files › Changes says how far the branch is from its remote and its base (review r09 A3). */
class BranchLineTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun the_hubs_answer_decodes() {
        val b = json.decodeFromString(
            BranchDiff.serializer(),
            """{"branch":"feat","upstream":null,"unpushed":[{"hash":"a"},{"hash":"b"}],"unpushedFiles":[],"truncated":false,"base":"origin/main","aheadOfBase":5,"baseFiles":[]}""",
        )
        assertEquals("2 not pushed · 5 ahead of main", branchLine(b))
    }

    @Test
    fun nothing_to_say_is_null() {
        assertNull(branchLine(BranchDiff(base = "origin/main")))
        assertNull(branchLine(BranchDiff(aheadOfBase = 3)), "no base, no ahead")
        assertEquals("50+ not pushed", branchLine(BranchDiff(unpushed = List(50) { Json.parseToJsonElement("{}") }, truncated = true)))
    }
}
