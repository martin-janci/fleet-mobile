package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.OrgMember
import dev.claudefleet.mobile.model.SettingDescriptor
import dev.claudefleet.mobile.model.SettingKind
import dev.claudefleet.mobile.model.SettingProposal
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Organisations and AI settings in the New layout (redesign 14.17, MobileOrgsSettings). */
class OrbitOrgsTest {

    private val sefcik = OrgDetail(
        id = 2,
        name = "Sefcik & co",
        myRole = "member",
        members = listOf(OrgMember(1, "frantisek", "František", "admin"), OrgMember(2, "martin", "Martin", "member")),
        spentMonthMicros = 41_200_000,
        budgetMonthlyUsd = 120,
    )

    @Test
    fun the_role_is_said_in_words_never_you_are_member() {
        assertEquals("Organisation · you are a member", orgRoleLine(sefcik))
        assertEquals("Organisation · you are an admin", orgRoleLine(sefcik.copy(myRole = "admin")))
        assertEquals("Organisation · you are not in it", orgRoleLine(sefcik.copy(myRole = null)))
    }

    @Test
    fun the_owner_and_members_come_from_the_roles() {
        assertEquals("Owner: František", orgOwners(sefcik))
        assertEquals("František (owner), Martin (member)", membersLine(sefcik))
        assertNull(orgOwners(sefcik.copy(members = null)), "a hub that sent no members names no owner")
    }

    @Test
    fun the_budget_meter_shows_its_numbers() {
        val m = assertNotNull(budgetMeter(sefcik))
        assertEquals("Budget this month", m.title)
        assertEquals("$41.20 of $120", m.figure)
        assertEquals(41.2f / 120f, m.fraction, 0.001f)
        assertFalse(m.near)
        assertFalse(m.over)
    }

    @Test
    fun the_meter_warns_near_the_budget_and_says_over_once_reached() {
        assertTrue(assertNotNull(budgetMeter(sefcik.copy(spentMonthMicros = 100_000_000))).near)
        val over = assertNotNull(budgetMeter(sefcik.copy(spentMonthMicros = 130_000_000, overBudget = listOf("monthly"))))
        assertTrue(over.over)
        assertEquals(1f, over.fraction)
    }

    @Test
    fun no_spend_or_no_budget_draws_no_meter_and_a_daily_budget_is_used_without_a_monthly_one() {
        assertNull(budgetMeter(sefcik.copy(spentMonthMicros = null)))
        assertNull(budgetMeter(sefcik.copy(budgetMonthlyUsd = 0)))
        val daily = assertNotNull(budgetMeter(sefcik.copy(budgetMonthlyUsd = 0, spentTodayMicros = 5_000_000, budgetDailyUsd = 10)))
        assertEquals("Budget today", daily.title)
    }

    @Test
    fun an_orgs_consent_to_jev_is_read_and_absent_is_unknown() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(true, json.decodeFromString(OrgDetail.serializer(), """{"id":1,"name":"Personal","jev_allowed":true}""").jevAllowed)
        assertNull(json.decodeFromString(OrgDetail.serializer(), """{"id":1,"name":"Old hub"}""").jevAllowed)
    }

    @Test
    fun the_jev_mode_never_offers_auto() {
        val mode = SettingDescriptor("decide.jev.work_link", "Work link", kind = SettingKind("choice", options = listOf("off", "shadow", "assist", "auto")))
        assertEquals(listOf("off", "shadow", "assist"), mode.offeredOptions())
        val other = SettingDescriptor("update.track", "Track", kind = SettingKind("choice", options = listOf("stable", "auto")))
        assertEquals(listOf("stable", "auto"), other.offeredOptions(), "only Jev's modes are filtered")
    }

    @Test
    fun what_jev_may_do_rules_out_permissions_and_approve() {
        assertTrue("never answers a permission" in JEV_MAY)
        assertTrue("never pre-selects Approve" in JEV_MAY)
    }

    @Test
    fun a_proposal_says_who_proposed_it() {
        val p = SettingProposal(id = 1, key = "limits.task_timeout", value = "129600", source = "session", sourceDetail = "Hub tuning")
        assertEquals("From the session “Hub tuning”", proposalSource(p))
        assertEquals("From an agent", proposalSource(p.copy(source = "agent", sourceDetail = null)))
    }
}
