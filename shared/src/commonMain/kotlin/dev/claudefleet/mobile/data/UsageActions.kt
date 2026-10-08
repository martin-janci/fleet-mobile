package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.AccountRow
import dev.claudefleet.mobile.model.UsageReport

/** The Usage screen's reads — both readonly tools. */
interface UsageActions {
    suspend fun report(sinceSecs: Long?): UsageReport
    suspend fun accounts(): List<AccountRow>
}

class HubUsageActions(private val session: AppSession) : UsageActions {
    override suspend fun report(sinceSecs: Long?): UsageReport = session.withClient { it.usageReport(sinceSecs) }
    override suspend fun accounts(): List<AccountRow> = session.withClient { it.listAccounts() }
}
