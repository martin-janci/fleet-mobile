package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The accounts a project forbids (claude-fleet migrations 217/218): the
 * phone's login choices skip them, as the desktop's do (`accountsForbiddenFor`,
 * here [forbiddenAccounts] over [AccountBlocks]; `headroomForAccount`,
 * `allowedLogins`), so it never proposes a login the hub refuses with
 * `E_ACCOUNT_FORBIDDEN`.
 */
class ForbiddenAccountsTest {
    private val own = HostLogin(profile = null, accountUuid = "personal", usedPct = 10.0)
    private val spare = HostLogin(profile = "spare", accountUuid = "spare-acct", usedPct = 5.0)
    private val team = HostLogin(profile = "team", accountUuid = "team-acct", usedPct = 60.0)
    private val full = HostLogin(profile = "full", accountUuid = "full-acct", usedPct = 100.0)

    @Test
    fun the_project_forbids_an_account_itself_or_through_its_org() {
        val accounts = listOf(
            AccountRow("a", forbiddenProjectIds = listOf(3)),
            AccountRow("b", forbiddenOrgIds = listOf(9)),
            AccountRow("c", forbiddenProjectIds = listOf(4), forbiddenOrgIds = listOf(8)),
        ).associate { it.uuid to it.blocks() }
        assertEquals(setOf("a"), forbiddenAccounts(accounts, 3))
        assertEquals(setOf("a", "b"), forbiddenAccounts(accounts, 3, orgId = 9))
        assertEquals(setOf("c"), forbiddenAccounts(accounts, 4, orgId = 7))
        assertEquals(emptySet(), forbiddenAccounts(accounts, null, orgId = 9), "no project, nothing forbidden")
    }

    @Test
    fun the_freest_login_skips_a_forbidden_account() {
        assertEquals(spare, freestLogin(listOf(own, spare, team)))
        assertEquals(own, freestLogin(listOf(own, spare, team), forbidden = setOf("spare-acct")))
        assertEquals(team, freestLogin(listOf(own, spare, team), forbidden = setOf("spare-acct", "personal")))
        assertNull(freestLogin(listOf(own), forbidden = setOf("personal")))
    }

    @Test
    fun the_switch_suggestion_skips_a_forbidden_account() {
        val h = Headroom(pauseAtPct = 95.0, logins = listOf(full, spare, team))
        assertEquals(spare, h.forAccount("full-acct").suggestion)
        assertEquals(team, h.forAccount("full-acct", forbidden = setOf("spare-acct")).suggestion)
        assertNull(h.forAccount("full-acct", forbidden = setOf("spare-acct", "team-acct")).suggestion)
    }

    /** A row whose account has no login on the host keeps the hub's answer, but never a forbidden suggestion. */
    @Test
    fun the_hubs_own_suggestion_gives_way_when_forbidden() {
        val h = Headroom(pauseAtPct = 95.0, chosen = full, over = true, suggestion = spare, logins = listOf(full, spare, team))
        assertEquals(spare, h.forAccount("elsewhere").suggestion)
        assertNull(h.forAccount("elsewhere", forbidden = setOf("spare-acct")).suggestion)
        assertEquals(spare, h.forAccount("elsewhere", forbidden = setOf("team-acct")).suggestion)
    }

    @Test
    fun a_signed_out_session_is_never_offered_a_forbidden_login() {
        val h = Headroom(pauseAtPct = 95.0, logins = listOf(own, spare, team))
        assertEquals(spare, h.signedInElsewhere("work", { false }))
        assertEquals(own, h.signedInElsewhere("work", { false }, setOf("spare-acct")))
        assertNull(h.signedInElsewhere("work", { false }, setOf("spare-acct", "personal", "team-acct")))
    }

    // ---- the project's org, as the hub reads it (`Store::project_org`) ----

    private fun dir(vararg rules: OrgRule) = OrgDirectory(rules = rules.toList())

    @Test
    fun a_project_is_in_the_org_of_its_most_specific_owner_or_repo_rule() {
        val p = ProjectRow(3, owner = "Acme", repo = "api")
        assertEquals(1, dir(OrgRule(id = 5, orgId = 1, owner = "acme")).orgOfProject(p), "owner, case-insensitively")
        assertEquals(
            2,
            dir(OrgRule(id = 5, orgId = 1, owner = "acme"), OrgRule(id = 6, orgId = 2, owner = "acme", repo = "API")).orgOfProject(p),
            "a repo rule outranks an owner rule",
        )
        assertEquals(1, dir(OrgRule(id = 5, orgId = 1, owner = "acme"), OrgRule(id = 6, orgId = 2, owner = "acme")).orgOfProject(p), "a tie goes to the older rule")
        assertNull(dir(OrgRule(id = 5, orgId = 1, owner = "other")).orgOfProject(p))
        assertNull(dir(OrgRule(id = 5, orgId = 1, owner = "acme", hostAlias = "mac")).orgOfProject(p), "a host-bound rule places sessions, not projects")
        assertNull(dir(OrgRule(id = 5, orgId = 1, owner = "local")).orgOfProject(ProjectRow(4, owner = "local", repo = "x")), "a local project has no owner")
    }

    /** The phone has no base path: a path rule that could match makes the org unknown rather than guessed. */
    @Test
    fun a_path_rule_that_could_apply_makes_the_org_unknown() {
        val p = ProjectRow(3, owner = "acme", repo = "api")
        assertNull(dir(OrgRule(id = 5, orgId = 1, owner = "acme"), OrgRule(id = 6, orgId = 2, pathPrefix = "/srv/acme")).orgOfProject(p))
        assertEquals(
            1,
            dir(OrgRule(id = 5, orgId = 1, owner = "acme"), OrgRule(id = 6, orgId = 2, owner = "other", pathPrefix = "/srv")).orgOfProject(p),
            "a path rule for another owner cannot apply",
        )
    }
}
