package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetSettingsActions
import dev.claudefleet.mobile.model.Autonomy
import dev.claudefleet.mobile.model.KEY_AUTO_CONTINUE
import dev.claudefleet.mobile.model.KEY_AUTO_CONTINUE_MINUTES
import dev.claudefleet.mobile.model.KEY_MAX_LEVEL
import dev.claudefleet.mobile.model.KEY_REACT_TO_EVENTS
import dev.claudefleet.mobile.model.PagesBundle
import dev.claudefleet.mobile.model.SettingDescriptor
import dev.claudefleet.mobile.model.SettingKind
import dev.claudefleet.mobile.model.SettingWrite
import dev.claudefleet.mobile.model.SettingsDecided
import dev.claudefleet.mobile.model.SettingsPending
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The hub's four autonomy settings, as `get_settings` and `set_setting` answer them. */
private class DialHub(var values: MutableMap<String, String>) : FleetSettingsActions {
    val writes = mutableListOf<Pair<String, String>>()
    /** A key whose write the hub refuses. */
    var refuse: String? = null
    override suspend fun pages() = PagesBundle()
    override suspend fun describe() = values.map { (k, v) -> SettingDescriptor(key = k, label = k, kind = SettingKind("text"), value = v) }
    override suspend fun set(key: String, value: String): Map<String, String> {
        if (key == refuse) error("refused")
        writes += key to value
        values[key] = value
        return values.toMap() + ("other.setting" to "x")
    }
    override suspend fun pending() = SettingsPending(true, emptyList())
    override suspend fun decide(accept: List<Long>, reject: List<Long>) = SettingsDecided()
    override suspend fun history(key: String): List<SettingWrite> = emptyList()
}

private fun dial(level: String, ac: String, minutes: String, react: String) =
    mutableMapOf(KEY_MAX_LEVEL to level, KEY_AUTO_CONTINUE to ac, KEY_AUTO_CONTINUE_MINUTES to minutes, KEY_REACT_TO_EVENTS to react)

class AutonomyViewModelTest {

    /**
     * Review phone-flows-3: the chip read Autopilot, the desktop then set Ask
     * first, and picking Within your brief skipped auto_continue (the stale
     * copy said true) — the hub ended on a Custom mix that acts as Ask first
     * while the chip said Brief. Every key is written, and the chip shows the
     * hub's answer.
     */
    @Test
    fun a_pick_from_a_stale_chip_writes_every_key_and_lands_on_the_pick() = runTest {
        val hub = DialHub(dial("3", "true", "2", "true"))
        val vm = AutonomyViewModel(hub, this)
        vm.load().join()
        assertEquals(Autonomy.AUTOPILOT, vm.state.value.current)

        hub.values = dial("1", "false", "10", "true")
        vm.set(Autonomy.BRIEF)!!.join()

        assertEquals(dial("2", "true", "10", "true"), hub.values)
        assertEquals(Autonomy.BRIEF, vm.state.value.current)
    }

    @Test
    fun a_write_that_stops_half_way_shows_what_the_hub_holds() = runTest {
        val hub = DialHub(dial("3", "true", "2", "true"))
        val vm = AutonomyViewModel(hub, this)
        vm.load().join()
        hub.values = dial("1", "false", "10", "true")
        hub.refuse = KEY_AUTO_CONTINUE

        vm.set(Autonomy.BRIEF)!!.join()

        // max_level landed; the rest did not. The chip reads the hub's mix: Custom.
        assertEquals(listOf(KEY_MAX_LEVEL to "2"), hub.writes)
        assertNull(vm.state.value.current)
        assertEquals("false", vm.state.value.values[KEY_AUTO_CONTINUE])
        assertEquals(setOf(KEY_MAX_LEVEL, KEY_AUTO_CONTINUE, KEY_AUTO_CONTINUE_MINUTES, KEY_REACT_TO_EVENTS), vm.state.value.values.keys)
    }
}
