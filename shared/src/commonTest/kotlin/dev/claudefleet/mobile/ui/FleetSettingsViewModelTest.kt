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
import dev.claudefleet.mobile.model.SettingWrite
import dev.claudefleet.mobile.model.Danger
import dev.claudefleet.mobile.model.changedFrom

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

    /** What `list_pages` throws, for the containment test. */
    var failPages: Throwable? = null

    /** What `setting_history` throws. */
    var failHistory: Throwable? = null

    override suspend fun pages(): PagesBundle {
        calls += "list_pages"
        failPages?.let { throw it }
        return PagesBundle(registry.pages)
    }
    override suspend fun describe() = registry.descriptors.map { it.copy(value = values.getValue(it.key)) }.also { calls += "get_settings" }
    override suspend fun set(key: String, value: String): Map<String, String> {
        calls += "set_setting $key=$value"
        refuseWrite?.let { throw it }
        values[key] = value.trim()
        return values.toMap()
    }
    override suspend fun pending() = SettingsPending(canWrite, proposals).also { calls += "setting_proposals" }
    override suspend fun history(key: String): List<SettingWrite> {
        calls += "setting_history $key"
        failHistory?.let { throw it }
        return listOf(SettingWrite(id = 1, key = key, before = "1", after = "2", actor = "person"))
    }
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

    /** 11.9: the notifier keeps the hub's notify.* values, so every answer of values reaches it. */
    @Test
    fun every_answer_of_values_reaches_the_notifier() = runTest {
        val hub = FakeHub()
        val seen = mutableListOf<Map<String, String>>()
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true, onValues = { seen += it })
        vm.load(); runCurrent()
        assertEquals(1, seen.size)
        assertEquals(hub.values["work.recent_days"], seen.last()["work.recent_days"])
        vm.set("work.recent_days", "3"); runCurrent()
        assertEquals("3", seen.last()["work.recent_days"])
    }

    /**
     * A refused settings read stays on this screen. The fan-out's `async`
     * children used to run directly under `load()`'s own `launch`, so a
     * failed `list_pages` cancelled that job rather than being caught — and
     * the scope behind it is `rememberCoroutineScope`'s plain Job, not a
     * SupervisorJob, with no `CoroutineExceptionHandler` in any production
     * source. One refused read therefore tore down the whole shared work
     * scope and reached the uncaught handler.
     *
     * The scope here is the TestScope, so an escaped failure fails this test:
     * it is the containment that is being asserted, not just the message.
     */
    @Test
    fun a_refused_pages_read_is_contained_and_leaves_the_scope_usable() = runTest {
        val hub = FakeHub()
        hub.failPages = HubError.Tool("E_FORBIDDEN", "nope")
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()

        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.loaded)
        // A failed read is the home screen's to draw, with Retry: a plain
        // sentence up front, the code only behind Details.
        val failed = assertNotNull(vm.state.value.loadError)
        assertFalse("E_FORBIDDEN" in failed.body, failed.body)
        assertEquals("E_FORBIDDEN: nope", failed.details)

        // the scope survived: the very same view-model loads again
        hub.failPages = null
        vm.load(); runCurrent()
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.loadError)
        assertNull(vm.state.value.error)
    }

    @Test
    fun a_refused_write_is_said_in_words_without_its_code() = runTest {
        val hub = FakeHub()
        hub.refuseWrite = HubError.Tool("E_INVALID", "must be between 1 and 30")
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        vm.set("work.recent_days", "99"); runCurrent()
        val said = assertNotNull(vm.state.value.fieldErrors["work.recent_days"])
        assertEquals("must be between 1 and 30", said)
    }

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

    @Test
    fun a_fields_history_is_read_only_where_the_hub_serves_it() = runTest {
        val hub = FakeHub()
        val vm = FleetSettingsViewModel(hub, backgroundScope, credentialCanWrite = true)
        vm.showHistory("work.recent_days").join()
        assertTrue(hub.calls.none { it.startsWith("setting_history") }, "no tool the hub did not list")

        vm.setHistoryAvailable(true)
        vm.showHistory("work.recent_days").join()
        runCurrent()
        assertEquals("work.recent_days", vm.state.value.history?.first)
        assertEquals("2", vm.state.value.history?.second?.single()?.after)

        vm.closeHistory()
        assertEquals(null, vm.state.value.history)
    }

    @Test
    fun a_failed_history_read_stays_in_its_dialog_and_says_so() = runTest {
        val hub = FakeHub(); hub.failHistory = HubError.Transport(RuntimeException("connection reset"))
        val vm = FleetSettingsViewModel(hub, backgroundScope, credentialCanWrite = true, historyAvailable = true)
        vm.showHistory("work.recent_days").join()
        // The dialog stays open on the setting, with the failure in it — not
        // closed, with a sentence nothing draws.
        assertEquals("work.recent_days", vm.state.value.history?.first)
        assertNull(vm.state.value.history?.second)
        assertEquals("Cannot reach the hub", vm.state.value.historyError?.title)

        hub.failHistory = null
        vm.showHistory("work.recent_days").join()
        assertNull(vm.state.value.historyError, "a retry clears the failure")
        assertEquals("2", vm.state.value.history?.second?.single()?.after)

        hub.failHistory = HubError.Transport(RuntimeException("connection reset"))
        vm.showHistory("work.recent_days").join()
        vm.closeHistory()
        assertNull(vm.state.value.history)
        assertNull(vm.state.value.historyError)
    }

    /** G1.5: a typed value waits for the Save bar; Save writes the lot, in the order staged. */
    @Test
    fun typed_values_stage_and_one_save_writes_them_in_order() = runTest {
        val hub = FakeHub()
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        vm.type("work.recent_days", "3")
        vm.stage("work.summary_model", "opus")
        vm.type("gc.bg_idle_secs", "2")
        runCurrent()
        val s = vm.state.value
        assertTrue(hub.calls.none { it.startsWith("set_setting") }, "nothing sent before Save: ${hub.calls}")
        assertEquals(3, s.changeCount)
        assertEquals("3 changes", saveBarCount(s.changeCount))
        assertEquals("3", s.shown("work.recent_days"), "the row shows what is staged")
        assertEquals("7200", s.staged["gc.bg_idle_secs"], "hours typed, seconds staged")
        assertTrue(s.canSave)

        vm.save(); runCurrent()
        assertEquals(
            listOf("set_setting work.recent_days=3", "set_setting work.summary_model=opus", "set_setting gc.bg_idle_secs=7200"),
            hub.calls.filter { it.startsWith("set_setting") },
        )
        val after = vm.state.value
        assertEquals(0, after.changeCount)
        assertFalse(after.saving)
        assertEquals("3", after.values["work.recent_days"])
        assertEquals("opus", after.values["work.summary_model"])
    }

    @Test
    fun typing_the_stored_value_back_is_no_change_and_discard_puts_everything_back() = runTest {
        val hub = FakeHub()
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        val stored = vm.state.value.values.getValue("work.recent_days")
        vm.type("work.recent_days", "3")
        vm.type("work.recent_days", stored)
        assertEquals(0, vm.state.value.changeCount)

        vm.type("work.recent_days", "3")
        vm.stage("work.summary_model", "opus")
        val epoch = vm.state.value.fieldEpoch["work.recent_days"] ?: 0
        vm.discard()
        val s = vm.state.value
        assertEquals(0, s.changeCount)
        assertEquals(stored, s.shown("work.recent_days"))
        assertEquals(epoch + 1, s.fieldEpoch["work.recent_days"], "the field starts again from the hub's value")
        runCurrent()
        assertTrue(hub.calls.none { it.startsWith("set_setting") })
    }

    @Test
    fun a_half_typed_number_holds_the_save_bar_with_its_reason() = runTest {
        val hub = FakeHub()
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        vm.type("work.recent_days", "2.5")
        val s = vm.state.value
        assertEquals(1, s.changeCount, "a problem still counts as a change")
        assertFalse(s.canSave)
        assertTrue(s.stageProblems.getValue("work.recent_days").contains("whole number"))
        assertNull(vm.save())
        vm.type("work.recent_days", "2")
        assertTrue(vm.state.value.canSave)
        assertTrue(vm.state.value.stageProblems.isEmpty())
    }

    @Test
    fun a_refused_value_stays_staged_beside_its_field_and_the_rest_are_written() = runTest {
        val hub = object : FleetSettingsActions by FakeHub() {
            override suspend fun set(key: String, value: String): Map<String, String> {
                if (key == "work.recent_days") throw HubError.Tool("E_INVALID", "must be between 1 and 30")
                return mapOf(key to value)
            }
        }
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        vm.type("work.recent_days", "99")
        vm.stage("work.summary_model", "opus")
        vm.save(); runCurrent()
        val s = vm.state.value
        assertEquals(mapOf("work.recent_days" to "99"), s.staged)
        assertEquals("must be between 1 and 30", s.fieldErrors["work.recent_days"])
        assertEquals("opus", s.values["work.summary_model"])
    }

    @Test
    fun a_switch_still_writes_at_once_and_offers_undo() = runTest {
        val hub = FakeHub()
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        val before = vm.state.value.values.getValue("playbooks.press_enter")
        val next = if (before == "true") "false" else "true"
        vm.stage("playbooks.press_enter", next); runCurrent()
        assertEquals(0, vm.state.value.changeCount, "a switch is not staged")
        assertEquals(next, vm.state.value.values["playbooks.press_enter"])
        val u = assertNotNull(vm.state.value.undo)
        assertEquals(before, u.before)
        vm.undo(); runCurrent()
        assertEquals(before, vm.state.value.values["playbooks.press_enter"])
        assertNull(vm.state.value.undo, "undoing offers no undo of the undo")
    }

    @Test
    fun reset_stages_the_default_for_a_typed_value_and_writes_it_for_a_switch() = runTest {
        val hub = FakeHub()
        hub.values["work.recent_days"] = "3"
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        val d = vm.state.value.descriptors.getValue("work.recent_days")
        assertEquals("changed from 14 days", d.changedFrom(vm.state.value.values.getValue("work.recent_days")))
        vm.reset("work.recent_days")
        assertEquals("14", vm.state.value.staged["work.recent_days"])
        assertEquals(1, vm.state.value.fieldEpoch["work.recent_days"])
        assertTrue(hub.calls.none { it.startsWith("set_setting") })
    }

    @Test
    fun a_staged_value_that_needs_confirming_asks_once_on_save() = runTest {
        // Every confirming setting today is a switch; a typed one would ask on Save, not per keystroke.
        val base = FakeHub()
        val hub = object : FleetSettingsActions by base {
            override suspend fun describe() = base.describe().map {
                if (it.key == "work.recent_days") it.copy(danger = Danger(level = "confirm", message = "Sure?")) else it
            }
        }
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        vm.type("work.recent_days", "3")
        assertNull(vm.state.value.confirm)
        vm.save(); runCurrent()
        val c = assertNotNull(vm.state.value.confirm)
        assertTrue(c.thenSave)
        assertTrue(base.calls.none { it.startsWith("set_setting") })
        vm.confirm(); runCurrent()
        assertEquals("3", vm.state.value.values["work.recent_days"])
        assertEquals(0, vm.state.value.changeCount)
    }

    @Test
    fun a_device_that_may_not_write_stages_nothing() = runTest {
        val hub = FakeHub(canWrite = false)
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        vm.type("work.recent_days", "3")
        vm.stage("work.summary_model", "opus")
        vm.reset("work.recent_days")
        assertEquals(0, vm.state.value.changeCount)
        assertNull(vm.save())
    }
}
