package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.partialForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The MobileChatForms board's Building: what the draft card says while the form arrives. */
class ChatFormDraftTest {
    private val twoSteps = """{"title":"Start Papaya receipts","steps":[{"title":"Project","fields":[""" +
        """{"name":"project","type":"text","label":"Project name"}]},{"title":"Where","fields":[{"name":"ho"""

    @Test
    fun the_atom_says_what_the_agent_reads() {
        assertEquals("Writing the form · reading the Jira epic PD-3100", draftReadingLine("the Jira epic PD-3100"))
        assertEquals("Writing the form", draftReadingLine(null))
        assertEquals("Writing the form", draftReadingLine("  "))
    }

    @Test
    fun step_bars_count_the_steps_written_so_far() {
        val two = partialForm(twoSteps)
        assertEquals("step 1 of 2", draftStepLine(two))
        // Step 1 is closed once step 2 begins: its fields are ready, no skeletons trail it.
        assertFalse(draftStillArriving(two))
        assertEquals(listOf("project"), two.fillable.map { it.name })

        val one = partialForm(twoSteps.substring(0, twoSteps.indexOf("{\"title\":\"Where\"")))
        assertNull(draftStepLine(one))
        assertTrue(draftStillArriving(one))
    }
}
