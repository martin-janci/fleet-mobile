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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.isActive
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

    /** What `list_pages` throws, for the containment test. */
    var failPages: Throwable? = null

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
        assertNotNull(vm.state.value.error)

        // the scope survived: the very same view-model loads again
        hub.failPages = null
        vm.load(); runCurrent()
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.error)
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

    /**
     * A REFUSAL leaves the review empty (the case above); a transport failure
     * is not a refusal. Caught alike, one timeout read as "this hub refuses
     * review": a trusted `full` device went read-only, was shown the trust
     * command its operator had already run, and — nothing re-read it — stayed
     * that way for the life of the process. It is reported instead, so the
     * screen can say so and offer Retry.
     */
    @Test
    fun a_failed_review_read_that_is_not_a_refusal_is_reported() = runTest {
        for (thrown in listOf(HubError.Unauthorized("stale token"), HubError.Http(503, "down"))) {
            val hub = object : FleetSettingsActions by FakeHub() {
                override suspend fun pending(): SettingsPending = throw thrown
            }
            val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
            vm.load(); runCurrent()
            assertNotNull(vm.state.value.error, "$thrown")
            assertFalse(vm.state.value.loaded, "$thrown")
            assertFalse(vm.state.value.canWrite, "$thrown")
            // and it is dismissable, which is what the banner's close does
            vm.dismissError()
            assertNull(vm.state.value.error)
        }
    }

    /**
     * A write the view model drops as a no-op still ANSWERS the field.
     *
     * `"60.0"` or `"060"` for a stored `60` normalises to the value the hub
     * already has, and returning silently changed no state — so the draft kept
     * the person's spelling, Save stayed live, and tapping it did nothing, for
     * ever. `settled` is the one thing the field's draft is re-keyed on.
     */
    @Test
    fun a_write_the_model_drops_still_re_seeds_the_field() = runTest {
        val hub = FakeHub()
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        val key = "work.recent_days"
        val now = vm.state.value.values.getValue(key)
        val before = vm.state.value.settled

        vm.set(key, now)
        runCurrent()

        assertTrue(vm.state.value.settled > before, "the field is told the write settled")
        assertFalse(hub.calls.any { it.startsWith("set_setting $key") }, "and nothing was sent")

        // A real write settles it too: the hub may normalise to what it had.
        val real = vm.state.value.settled
        vm.set(key, "7")
        runCurrent()
        assertTrue(vm.state.value.settled > real)
        assertEquals("7", vm.state.value.values.getValue(key))
    }

    /**
     * A reload that no longer offers the open page does not strand the screen.
     *
     * `PageList` is drawn INSIDE the page-mode branch, so a page id that no
     * longer matches left no back button, no Hub / Access / Forget rows, and —
     * if the hub came back with nothing — no way out at all.
     */
    @Test
    fun a_reload_that_drops_the_open_page_closes_it() = runTest {
        val hub = object : FleetSettingsActions by FakeHub() {
            var offer = true
            override suspend fun pages() =
                if (offer) PagesBundle(registry.pages) else PagesBundle(emptyList())
        }
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        vm.open("settings.automation")
        assertEquals("settings.automation", vm.state.value.openPage)

        hub.offer = false
        vm.load(); runCurrent()

        assertNull(vm.state.value.openPage, "a page the hub no longer offers is not open")
        assertEquals(emptyList(), vm.state.value.pages)

        // And a reload that still offers it leaves it alone.
        hub.offer = true
        vm.open("settings.automation")
        vm.load(); runCurrent()
        assertEquals("settings.automation", vm.state.value.openPage)
    }

    /**
     * Applying a confirm-level PROPOSAL asks first, as typing one does.
     *
     * The confirm gate lived in `set()` only, so `decide` — the screen's second
     * write path — applied such a change with no question. The hub refuses to
     * record a proposal for a confirm-level setting, so the window is narrow: a
     * proposal left for a key the hub LATER marks `.danger(...)` survives,
     * because only decided rows are pruned.
     */
    @Test
    fun applying_a_confirm_level_proposal_asks_first() = runTest {
        val hub = FakeHub(proposals = listOf(proposal(id = 9, key = "gc.enabled", value = "true")))
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()

        assertNull(vm.decide(9, apply = true), "nothing is sent yet")
        runCurrent()
        val c = assertNotNull(vm.state.value.confirm)
        assertEquals("gc.enabled", c.key)
        assertEquals(9L, c.proposalId)
        assertTrue(c.message.isNotEmpty(), "the setting's own sentence")
        assertFalse(hub.calls.any { it.startsWith("decide") })

        vm.confirm(); runCurrent()
        assertTrue(hub.calls.any { it == "decide [9] []" }, "${hub.calls}")
        assertEquals("true", vm.state.value.values.getValue("gc.enabled"))
        assertNull(vm.state.value.confirm)
    }

    /** Cancelling it sends nothing, and the proposal is still there. */
    @Test
    fun cancelling_that_question_leaves_the_proposal_waiting() = runTest {
        val hub = FakeHub(proposals = listOf(proposal(id = 9, key = "gc.enabled", value = "true")))
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        vm.decide(9, apply = true); runCurrent()

        vm.cancelConfirm(); runCurrent()

        assertNull(vm.state.value.confirm)
        assertFalse(hub.calls.any { it.startsWith("decide") })
        assertEquals(1, vm.state.value.proposals.size)
    }

    /** REJECTING one never asks: nothing is applied. */
    @Test
    fun rejecting_a_confirm_level_proposal_asks_nothing() = runTest {
        val hub = FakeHub(proposals = listOf(proposal(id = 9, key = "gc.enabled", value = "true")))
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()

        assertNotNull(vm.decide(9, apply = false))
        runCurrent()

        assertNull(vm.state.value.confirm)
        assertTrue(hub.calls.any { it == "decide [] [9]" }, "${hub.calls}")
    }

    /**
     * A proposal the hub REFUSED stays on screen.
     *
     * The local removal ran whether or not the id was in `failed`, so whenever
     * the `pending()` re-read also failed — it is swallowed — a proposal the
     * hub still holds vanished from the phone.
     */
    @Test
    fun a_refused_decision_leaves_its_proposal_on_screen() = runTest {
        // The first `pending()` has to succeed, or `load()` reports and never
        // trusts the device; the re-read after the decision is the one that
        // fails, which is the case being pinned.
        val seed = FakeHub(proposals = listOf(proposal(id = 4)))
        val hub = object : FleetSettingsActions by seed {
            var loaded = false
            override suspend fun pending(): SettingsPending {
                if (!loaded) {
                    loaded = true
                    return seed.pending()
                }
                throw HubError.Http(503, "down")
            }
            override suspend fun decide(accept: List<Long>, reject: List<Long>) =
                SettingsDecided(failed = listOf(DecideFailure(4, "no longer waiting for review")))
        }
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        assertEquals(1, vm.state.value.proposals.size)

        vm.decide(4, apply = false); runCurrent()

        assertEquals(1, vm.state.value.proposals.size, "the hub refused it: it is still the hub's")
        assertNotNull(vm.state.value.error)
    }

    /**
     * A decision whose re-read fails marks the values STALE rather than showing
     * an old number as current.
     *
     * `describe()` was awaited outside any guard, so a failure after the hub had
     * already applied the proposal reached the outer catch — which touches
     * neither `values` nor `proposals`, so the field showed the old value and
     * the proposal still offered Apply.
     */
    @Test
    fun a_decision_whose_re_read_fails_says_the_values_are_stale() = runTest {
        val seed = FakeHub(proposals = listOf(proposal(id = 4)))
        var failDescribe = false
        val hub = object : FleetSettingsActions by seed {
            override suspend fun describe(): List<SettingDescriptor> {
                if (failDescribe) throw HubError.Http(503, "down")
                return seed.describe()
            }
        }
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        failDescribe = true

        vm.decide(4, apply = true); runCurrent()

        assertTrue(vm.state.value.valuesStale, "the screen knows it is behind")
        assertEquals(emptyList(), vm.state.value.proposals, "the hub applied it, so it is gone")
        vm.clearStale()
        assertFalse(vm.state.value.valuesStale)
    }

    /**
     * A failing `list_pages` is reported and the SCOPE survives.
     *
     * Nothing drove `load()` with a failing read, which is why the unconfined
     * `async` fan-out shipped: one refused settings read tore down the shared
     * work scope and reached the uncaught handler. The assertion that matters
     * is the last one.
     */
    @Test
    fun a_failing_page_read_is_reported_and_the_scope_lives() = runTest {
        val hub = FakeHub().apply { failPages = HubError.Http(503, "down") }
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()

        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.loaded)
        assertFalse(vm.state.value.loading)
        assertTrue(this.isActive, "the shared work scope must survive a refused read")
    }

    /** A second write to a key already in flight is dropped, not queued. */
    @Test
    fun a_write_to_a_busy_key_is_dropped() = runTest {
        val gate = CompletableDeferred<Unit>()
        val seed = FakeHub()
        val hub = object : FleetSettingsActions by seed {
            override suspend fun set(key: String, value: String): Map<String, String> {
                gate.await()
                return seed.set(key, value)
            }
        }
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()
        vm.set("work.recent_days", "7"); runCurrent()
        assertTrue("work.recent_days" in vm.state.value.busy)

        vm.set("work.recent_days", "9"); runCurrent()
        gate.complete(Unit); runCurrent()

        assertEquals("7", vm.state.value.values.getValue("work.recent_days"), "the second tap was dropped")
        assertEquals(1, seed.calls.count { it.startsWith("set_setting work.recent_days") })
    }

    /** `refuse` shows a message against one field without sending anything, and
     *  opening or leaving a page clears what was shown. */
    @Test
    fun a_refusal_the_phone_made_itself_is_shown_and_cleared_by_navigation() = runTest {
        val hub = FakeHub()
        val vm = FleetSettingsViewModel(hub, this, credentialCanWrite = true)
        vm.load(); runCurrent()

        vm.refuse("work.recent_days", "Recent: enter a whole number")
        assertEquals("Recent: enter a whole number", vm.state.value.fieldErrors["work.recent_days"])
        assertFalse(hub.calls.any { it.startsWith("set_setting") })

        vm.open("settings.automation")
        assertEquals(emptyMap(), vm.state.value.fieldErrors, "a new page starts clean")

        vm.refuse("work.recent_days", "again")
        assertTrue(vm.back(), "the page was open")
        assertEquals(emptyMap(), vm.state.value.fieldErrors)
        assertFalse(vm.back(), "and back from the list is nothing to do")
    }
}
