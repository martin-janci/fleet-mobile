package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.MemberGrants
import dev.claudefleet.mobile.model.MemberRemoved
import dev.claudefleet.mobile.model.OrgMemberRow

/**
 * The member actions the Company screen may take (claude-fleet `org_admin`,
 * phase D): list, change a role, and remove with a say over what was shared
 * with the person. Adding a member pairs a device, which stays on the desktop.
 */
interface MemberActions {
    suspend fun members(orgId: Long): List<OrgMemberRow>

    suspend fun setRole(orgId: Long, personId: Long, role: String): List<OrgMemberRow>

    suspend fun grants(orgId: Long, personId: Long): MemberGrants

    suspend fun narrow(orgId: Long, personId: Long): Int

    suspend fun remove(orgId: Long, personId: Long, keepGrants: Boolean): MemberRemoved
}

class HubMemberActions(private val session: AppSession) : MemberActions {
    override suspend fun members(orgId: Long): List<OrgMemberRow> = session.withClient { it.orgMembers(orgId) }

    override suspend fun setRole(orgId: Long, personId: Long, role: String): List<OrgMemberRow> =
        session.withClient { it.setOrgMember(orgId, personId, role) }

    override suspend fun grants(orgId: Long, personId: Long): MemberGrants = session.withClient { it.memberGrants(orgId, personId) }

    override suspend fun narrow(orgId: Long, personId: Long): Int = session.withClient { it.narrowMemberGrants(orgId, personId) }

    override suspend fun remove(orgId: Long, personId: Long, keepGrants: Boolean): MemberRemoved =
        session.withClient { it.removeOrgMember(orgId, personId, keepGrants) }
}
