@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ChatFormActions
import dev.claudefleet.mobile.data.FleetSettingsActions
import dev.claudefleet.mobile.model.DecideFailure
import dev.claudefleet.mobile.model.Danger
import dev.claudefleet.mobile.model.FormView
import dev.claudefleet.mobile.model.PagesBundle
import dev.claudefleet.mobile.model.SettingDescriptor
import dev.claudefleet.mobile.model.SettingKind
import dev.claudefleet.mobile.model.SettingProposal
import dev.claudefleet.mobile.model.SettingWrite
import dev.claudefleet.mobile.model.SettingsDecided
import dev.claudefleet.mobile.model.SettingsPending
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val FORM = """{"form_id":"f_a","session_id":1,"title":"Deploy",
    "spec":{"spec":"fleet.form/1","title":"Deploy","submit":"Deploy","steps":[{"title":"Where","fields":[
      {"name":"env","type":"select","label":"Env","options":[["stg","Staging"],["prod","Production"]],"required":true},
      {"name":"token","type":"secret","label":"Token","required":true}]}]},
    "why":"I need a target","state":"pending","created_at":1}"""

private class FakeForms : ChatFormActions {
    val calls = mutableListOf<String>()
    var refuse: HubError? = null
    var view: FormView = json.decodeFromString(FormView.serializer(), FORM)

    override suspend fun get(formId: String): FormView = view.also { calls += "get $formId" }

    override suspend fun answer(formId: String, values: Map<String, JsonElement>): FormView {
        calls += "answer $formId $values"
        refuse?.let { throw it }
        view = view.copy(state = "answered", answeredBy = "Martin")
        return view
    }

    override suspend fun decline(formId: String, note: String?): FormView {
        calls += "decline $formId $note"
        view = view.copy(state = "declined", note = note, answeredBy = "Martin")
        return view
    }
}

class AskFormModelTest {
    @Test
    fun answers_once_every_required_field_has_a_value_and_forgets_the_secret() = runTest {
        val hub = FakeForms()
        val m = AskFormModel(hub, "f_a", this)
        m.load().join()
        assertTrue(m.state.value.pending)
        assertFalse(m.state.value.ready)
        assertNull(m.answer())
        m.set("env", JsonPrimitive("prod"))
        m.set("token", JsonPrimitive("s3cret"))
        assertTrue(m.state.value.ready)
        m.answer()!!.join()
        assertEquals(listOf("get f_a", "answer f_a {env=\"prod\", token=\"s3cret\"}"), hub.calls)
        assertEquals("answered", m.state.value.form?.state)
        assertNull(m.state.value.values["token"])
    }

    @Test
    fun a_refused_answer_shows_each_fields_problem() = runTest {
        val hub = FakeForms()
        hub.refuse = HubError.Tool(
            code = "E_INVALID",
            message = "the answers do not fit the form",
            details = Json.parseToJsonElement("""{"problems":[{"field":"env","problem":"not an option"}]}"""),
        )
        val m = AskFormModel(hub, "f_a", this)
        m.load().join()
        m.set("env", JsonPrimitive("prod"))
        m.set("token", JsonPrimitive("s3cret"))
        m.answer()!!.join()
        val s = m.state.value
        assertEquals("not an option", s.problemFor("env"))
        assertNull(s.error)
        assertTrue(s.pending)
        // The secret is typed again, never kept past its one send.
        assertNull(s.values["token"])
        // Changing the field clears its problem.
        m.set("env", JsonPrimitive("stg"))
        assertNull(m.state.value.problemFor("env"))
    }

    @Test
    fun decline_sends_the_note() = runTest {
        val hub = FakeForms()
        val m = AskFormModel(hub, "f_a", this)
        m.load().join()
        m.startDecline()
        m.note("  not now  ")
        m.decline()!!.join()
        assertEquals("decline f_a not now", hub.calls.last())
        assertEquals("declined", m.state.value.form?.state)
        assertFalse(m.state.value.declining)
    }

    @Test
    fun a_form_that_already_ended_offers_nothing() = runTest {
        val hub = FakeForms()
        hub.view = hub.view.copy(state = "expired")
        val m = AskFormModel(hub, "f_a", this)
        m.load().join()
        assertFalse(m.state.value.pending)
        assertNull(m.decline())
        assertEquals(listOf("get f_a"), hub.calls)
    }
}

private class FakeSettings(var canWrite: Boolean = true, var proposals: List<SettingProposal> = emptyList()) : FleetSettingsActions {
    val calls = mutableListOf<String>()
    override suspend fun pages() = PagesBundle()
    override suspend fun describe() = listOf(
        SettingDescriptor(
            key = "work.auto_tidy",
            label = "Auto-tidy",
            kind = SettingKind("bool"),
            danger = Danger("confirm", "Fleet will safe-kill finished sessions."),
        ),
    ).also { calls += "get_settings" }
    override suspend fun set(key: String, value: String): Map<String, String> = error("a settings card never writes a setting directly")
    override suspend fun pending() = SettingsPending(canWrite, proposals).also { calls += "setting_proposals" }
    override suspend fun history(key: String): List<SettingWrite> = emptyList()
    override suspend fun decide(accept: List<Long>, reject: List<Long>): SettingsDecided {
        calls += "decide $accept $reject"
        val known = proposals.map { it.id }.toSet()
        proposals = proposals.filterNot { it.id in accept }
        return SettingsDecided(applied = accept.filter { it in known }, failed = accept.filterNot { it in known }.map { DecideFailure(it, "no longer waiting for review") })
    }
}

private fun tidy(id: Long = 7, current: String = "false") =
    SettingProposal(id = id, key = "work.auto_tidy", value = "true", before = "false", current = current, why = "less clutter", source = "agent", sourceDetail = "mercury")

class SettingCardModelTest {
    @Test
    fun shows_the_proposals_change_in_words_and_asks_with_the_settings_warning() = runTest {
        val hub = FakeSettings(proposals = listOf(tidy()))
        val m = SettingCardModel(hub, 7, this, mayDecide = true)
        m.load().join()
        val s = m.state.value
        assertEquals("Auto-tidy", s.label)
        assertEquals("Off" to "On", s.words(s.proposal!!.current) to s.words(s.proposal!!.value))
        assertFalse(s.moved)
        assertEquals("Auto-tidy: Off → On. Fleet will safe-kill finished sessions.", s.question)
        assertEquals("an agent (mercury)", whoWords(s.proposal!!.source, s.proposal!!.sourceDetail))
    }

    @Test
    fun apply_asks_first_then_decides_once() = runTest {
        val hub = FakeSettings(proposals = listOf(tidy()))
        val m = SettingCardModel(hub, 7, this, mayDecide = true)
        m.load().join()
        m.askApply()
        assertTrue(m.state.value.confirming)
        assertFalse(hub.calls.any { it.startsWith("decide") })
        m.apply()!!.join()
        assertNull(m.apply())
        assertEquals(1, hub.calls.count { it.startsWith("decide") })
        assertEquals("decide [7] []", hub.calls.last())
        assertEquals("applied", m.state.value.done)
        assertEquals("work.auto_tidy" to "true", m.state.value.applied)
    }

    @Test
    fun not_now_writes_nothing() = runTest {
        val hub = FakeSettings(proposals = listOf(tidy()))
        val m = SettingCardModel(hub, 7, this, mayDecide = true)
        m.load().join()
        m.later()
        assertEquals("later", m.state.value.done)
        assertEquals(listOf("setting_proposals", "get_settings"), hub.calls)
    }

    @Test
    fun a_device_that_may_not_write_has_no_apply() = runTest {
        for ((hubSays, credential) in listOf(false to true, true to false)) {
            val hub = FakeSettings(canWrite = hubSays, proposals = listOf(tidy()))
            val m = SettingCardModel(hub, 7, this, mayDecide = credential)
            m.load().join()
            assertFalse(m.state.value.canWrite)
            m.askApply()
            assertFalse(m.state.value.confirming)
            assertNull(m.apply())
        }
    }

    @Test
    fun a_proposal_already_decided_is_gone_and_a_moved_value_says_so() = runTest {
        val gone = SettingCardModel(FakeSettings(), 7, this, mayDecide = true)
        gone.load().join()
        assertTrue(gone.state.value.loaded)
        assertNull(gone.state.value.proposal)

        val moved = SettingCardModel(FakeSettings(proposals = listOf(tidy(current = "true"))), 7, this, mayDecide = true)
        moved.load().join()
        assertTrue(moved.state.value.moved)
    }
}
