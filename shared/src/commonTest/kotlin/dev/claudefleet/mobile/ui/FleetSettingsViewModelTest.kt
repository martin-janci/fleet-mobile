@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetSettingsActions
import dev.claudefleet.mobile.model.DecideFailure
import dev.claudefleet.mobile.model.PAGES_REGISTRY_FIXTURE
import dev.claudefleet.mobile.model.Page
import dev.claudefleet.mobile.model.PagesBundle
import dev.claudefleet.mobile.model.SettingDescriptor
import dev.claudefleet.mobile.model.SettingProposal
import dev.claudefleet.mobile.model.SettingsDecided
import dev.claudefleet.mobile.model.SettingsPending
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class Registry(val pages: List<Page>, val descriptors: List<SettingDescriptor>)

private val registry: Registry by lazy { json.decodeFromString(Registry.serializer(), PAGES_REGISTRY_FIXTURE) }

/** The hub, in memory: the registry's settings, a proposal queue, and a
 *  record of every call. */
private class FakeHub(
    var canWrite: Boolean = true,
    var proposals: List<SettingProposal> = emptyList(),
    var refuseWrite: HubError? = null,
) : FleetSettingsActions {
    val values = registry.descriptors.associate { it.key to it.value }.toMutableMap()
    val calls = mutableListOf<String>()

    override suspend fun pages() = PagesBundle(registry.pages).also { calls += "list_pages" }
    override suspend fun describe() = registry.descriptors.map { it.copy(value = values.getValue(it.key)) }.also { calls += "get_settings" }
    override suspend fun set(key: String, value: String): Map<String, String> {
        calls += "set_setting $key=$value"
        refuseWrite?.let { throw it }
        values[key] = value.trim()
        return values.toMap()
    }
    override suspend fun pending() = SettingsPending(canWrite, proposals).also { calls += "setting_proposals" }
    override suspend fun decide(accept: List<Long>, reject: List<Long>): SettingsDecided {
        calls += "decide $accept $reject"
        val applied = proposals.filter { it.id in accept }
        applied.forEach { values[it.key] = it.value }
        val known = proposals.map { it.id }.toSet()
        proposals = proposals.filterNot { it.id in accept || it.id in reject }
        return SettingsDecided(
            applied = accept.filter { it in known },
            rejected = reject.filter { it in known },
            failed = (accept + reject).filterNot { it in known }.map { DecideFailure(it, "no longer waiting for review") },
        )
    }
}

private fun proposal(id: Long = 4, key: String = "work.recent_days", value: String = "3") =
    SettingProposal(id = id, key = key, value = value, before = "14", current = "14", why = "shorter", source = "agent", sourceDetail = "control API")

class FleetSettingsViewModelTest {

    @Test
    fun it_reads_the_hubs_pages_values_and_review_and_who_may_write() = runTest {
        val hub = FakeHub(proposals = listOf(proposal()))
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        val s = vm.state.value
        assertTrue(s.loaded)
        assertTrue(s.pages.any { it.id == "settings.automation" })
        assertEquals(hub.values["work.recent_days"], s.values["work.recent_days"])
        assertEquals(1, s.proposals.size)
        assertTrue(s.canWrite)
        assertTrue(s.editable("work.recent_days"))
        assertFalse(s.editable("mcp.port"), "owned by another subsystem")
        assertFalse(s.editable("projects.base_path"), "a map is a desktop's")
    }

    @Test
    fun an_untrusted_or_readonly_device_reads_and_never_writes() = runTest {
        for ((credential, hubSays) in listOf(true to false, false to true)) {
            val hub = FakeHub(canWrite = hubSays)
            val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = credential)
            vm.load(); runCurrent()
            assertFalse(vm.state.value.canWrite)
            vm.set("work.recent_days", "3"); runCurrent()
            assertNull(vm.decide(4, apply = true))
            assertTrue(hub.calls.none { it.startsWith("set_setting") || it.startsWith("decide") }, "${hub.calls}")
        }
    }

    @Test
    fun a_write_goes_to_the_hub_and_the_screen_shows_what_the_hub_stored() = runTest {
        val hub = FakeHub()
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        vm.set("work.recent_days", "3")
        assertTrue("work.recent_days" in vm.state.value.busy)
        runCurrent()
        assertEquals("3", vm.state.value.values["work.recent_days"])
        assertFalse("work.recent_days" in vm.state.value.busy)
        // The same value again is not sent.
        vm.set("work.recent_days", "3"); runCurrent()
        assertEquals(1, hub.calls.count { it.startsWith("set_setting") })
    }

    @Test
    fun a_refused_write_stays_next_to_its_field() = runTest {
        val hub = FakeHub(refuseWrite = HubError.Tool("E_FORBIDDEN", "this device may read the fleet's settings", null))
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        vm.set("work.recent_days", "3"); runCurrent()
        assertNotNull(vm.state.value.fieldErrors["work.recent_days"])
        assertEquals(registry.descriptors.single { it.key == "work.recent_days" }.value, vm.state.value.values["work.recent_days"])
    }

    @Test
    fun a_setting_that_needs_confirming_asks_first() = runTest {
        val hub = FakeHub()
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        vm.set("gc.enabled", "true"); runCurrent()
        val c = assertNotNull(vm.state.value.confirm)
        assertTrue(c.message.isNotBlank())
        assertTrue(hub.calls.none { it.startsWith("set_setting") })
        vm.cancelConfirm()
        assertNull(vm.state.value.confirm)
        vm.set("gc.enabled", "true"); vm.confirm(); runCurrent()
        assertEquals("true", vm.state.value.values["gc.enabled"])
    }

    @Test
    fun deciding_a_proposal_applies_it_and_reads_the_review_again() = runTest {
        val hub = FakeHub(proposals = listOf(proposal(4), proposal(5, "playbooks.press_enter", "true")))
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        vm.decide(4, apply = true); runCurrent()
        assertEquals("3", vm.state.value.values["work.recent_days"])
        assertEquals(listOf(5L), vm.state.value.proposals.map { it.id })
        vm.decide(5, apply = false); runCurrent()
        assertTrue(vm.state.value.proposals.isEmpty())
        assertEquals("false", vm.state.value.values["playbooks.press_enter"])
        vm.decide(9, apply = true); runCurrent()
        assertEquals("no longer waiting for review", vm.state.value.error)
    }

    @Test
    fun pages_open_and_back_returns_to_the_list() = runTest {
        val vm = FleetSettingsViewModel(FakeHub(), this, credentialCanWrite = true)
        vm.load(); runCurrent()
        assertFalse(vm.back())
        vm.open("settings.automation")
        assertEquals("settings.automation", vm.state.value.page?.id)
        assertTrue(vm.back())
        assertNull(vm.state.value.page)
    }

    @Test
    fun a_hub_that_serves_no_review_leaves_the_device_read_only_not_broken() = runTest {
        val hub = object : FleetSettingsActions by FakeHub() {
            override suspend fun pending(): SettingsPending = throw HubError.Tool("E_FORBIDDEN", "not a client-callable tool", null)
        }
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.canWrite)
    }
}
