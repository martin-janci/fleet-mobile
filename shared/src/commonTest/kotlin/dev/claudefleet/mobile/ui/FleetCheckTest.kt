package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetRepository
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.ui.kit.StepState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetCheckTest {

    @Test
    fun the_status_before_the_stream_starts_does_not_end_the_check() {
        assertFalse(fleetCheckOver(ConnectionStatus.Offline(FleetRepository.NOT_STARTED)))
        assertFalse(fleetCheckOver(ConnectionStatus.Reconnecting(1, null)))
        assertFalse(fleetCheckOver(ConnectionStatus.Reconnecting(3, "the hub did not answer")))
    }

    @Test
    fun a_hub_that_never_answers_ends_it_after_the_last_try() {
        assertTrue(fleetCheckOver(ConnectionStatus.Reconnecting(FLEET_CHECK_TRIES, "the hub did not answer")))
        assertTrue(fleetCheckOver(ConnectionStatus.Reconnecting(FLEET_CHECK_TRIES + 5, null)))
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

    // ---- the first import (Galaxy) ----

    @Test
    fun the_first_import_counts_the_hosts_the_hub_has_read() {
        val hosts = listOf(
            HostRow(alias = "mac", lastPingedAt = 1_000),
            HostRow(alias = "nas"),
            HostRow(alias = "pi"),
            // A hidden host is not the fleet a person is waiting for.
            HostRow(alias = "old", hidden = true),
        )
        val progress = firstImportProgress(hosts)
        assertEquals(1, progress?.done)
        assertEquals(3, progress?.total)
        assertEquals("1 of 3 hosts read", progress?.label)
    }

    @Test
    fun a_fleet_already_read_never_shows_the_galaxy() {
        assertNull(firstImportProgress(listOf(HostRow(alias = "mac", lastPingedAt = 1_000), HostRow(alias = "old", hidden = true))))
        assertNull(firstImportProgress(emptyList()))
    }

    @Test
    fun the_galaxy_names_real_counts() {
        assertEquals(
            "The hub found 3 hosts and is reading them for the first time. 14 sessions so far. This happens once.",
            firstImportMeta(hosts = 3, sessions = 14),
        )
        assertEquals("The hub found 1 host and is reading it for the first time. This happens once.", firstImportMeta(hosts = 1, sessions = 0))
    }
}
