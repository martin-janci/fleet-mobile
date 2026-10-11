package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.AccountRow
import dev.claudefleet.mobile.model.UsageReport

/** The Usage screen's reads — both readonly tools. */
interface UsageActions {
    suspend fun report(sinceSecs: Long?): UsageReport
    suspend fun accounts(): List<AccountRow>
    suspend fun setNickname(uuid: String, nickname: String?): AccountRow
}

class HubUsageActions(private val session: AppSession) : UsageActions {
    override suspend fun report(sinceSecs: Long?): UsageReport = session.withClient { it.usageReport(sinceSecs) }
    override suspend fun accounts(): List<AccountRow> = session.withClient { it.listAccounts() }
    override suspend fun setNickname(uuid: String, nickname: String?): AccountRow =
        session.withClient { it.setAccountNickname(uuid, nickname) }
}
