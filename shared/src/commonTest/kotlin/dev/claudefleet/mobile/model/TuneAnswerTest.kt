package dev.claudefleet.mobile.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The hub's `TuneAnswer.cut` reaches the phone, so a rewrite cut at the limit says so (review w30-6). */
class TuneAnswerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun a_cut_rewrite_decodes_as_cut() {
        val a = json.decodeFromString(
            TuneAnswer.serializer(),
            """{"prompt":"Fix the login…","notes":"","unchanged":false,"model":"haiku","host_alias":"h","at":1,"cut":true}""",
        )
        assertTrue(a.cut)
        assertFalse(json.decodeFromString(TuneAnswer.serializer(), """{"prompt":"x"}""").cut)
    }
}
