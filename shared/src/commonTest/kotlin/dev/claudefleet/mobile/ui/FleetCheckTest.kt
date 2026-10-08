package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetRepository
import dev.claudefleet.mobile.ui.kit.StepState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FleetCheckTest {

    @Test
    fun the_status_before_the_stream_starts_does_not_end_the_check() {
        assertFalse(fleetCheckOver(ConnectionStatus.Offline(FleetRepository.NOT_STARTED)))
        assertFalse(fleetCheckOver(ConnectionStatus.Reconnecting(1, null)))
        assertFalse(fleetCheckOver(ConnectionStatus.Reconnecting(3, "the hub did not answer")))
    }

    @Test
    fun a_connection_or_a_settled_failure_ends_it() {
        assertTrue(fleetCheckOver(ConnectionStatus.Connected("0.9.4")))
        assertTrue(fleetCheckOver(ConnectionStatus.Refused("this hub speaks contract 9")))
        assertTrue(fleetCheckOver(ConnectionStatus.Offline("the token was cancelled")))
    }

    @Test
    fun the_steps_follow_the_connection() {
        val trying = fleetCheckSteps(ConnectionStatus.Reconnecting(2, null), "fleet.janci.dev", "Pixel 9 Pro")
        assertEquals(listOf("Paired as “Pixel 9 Pro”", "Reach fleet.janci.dev", "Read the hosts and sessions"), trying.map { it.label })
        assertEquals(listOf(StepState.Done, StepState.Running, StepState.Pending), trying.map { it.state })
        assertEquals("try 2", trying[1].detail)

        val first = fleetCheckSteps(ConnectionStatus.Reconnecting(1, null), "fleet.janci.dev", "")
        assertEquals("Paired this phone", first[0].label)
        assertEquals(null, first[1].detail)

        val up = fleetCheckSteps(ConnectionStatus.Connected(null), "fleet.janci.dev", "Pixel 9 Pro")
        assertEquals(listOf(StepState.Done, StepState.Done, StepState.Done), up.map { it.state })
    }
}
