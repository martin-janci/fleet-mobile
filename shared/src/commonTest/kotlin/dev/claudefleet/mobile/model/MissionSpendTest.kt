package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Contract 14: a listed mission's spend and budget, and a plan's run estimate. */
class MissionSpendTest {
    @Test
    fun a_contract_fourteen_mission_carries_its_spend_and_budget() {
        val m = json.decodeFromString<Mission>(
            """{"id":1,"name":"m","state":"active","cost_micros":31800000,"budget_micros":40000000}""",
        )
        assertEquals(31_800_000L, m.costMicros)
        assertEquals(40_000_000L, m.budgetMicros)
        val old = json.decodeFromString<Mission>("""{"id":1,"name":"m"}""")
        assertNull(old.costMicros)
        assertNull(old.budgetMicros)
    }

    @Test
    fun a_plan_carries_a_run_estimate_when_the_hub_has_one() {
        val plan = json.decodeFromString<MissionPlan>(
            """{"cost_micros":5,"run_estimate":{"micros":3000000,"runs":2,"basis":"mission"}}""",
        )
        assertEquals(RunEstimate(3_000_000, 2, "mission"), plan.runEstimate)
        assertNull(json.decodeFromString<MissionPlan>("""{"cost_micros":5}""").runEstimate)
    }
}
