package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.MultiStart
import dev.claudefleet.mobile.model.ResumePlan
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.TicketCard
import dev.claudefleet.mobile.model.Today
import dev.claudefleet.mobile.model.BatchResult
import dev.claudefleet.mobile.model.OrgImpact
import dev.claudefleet.mobile.model.ReviewPage
import dev.claudefleet.mobile.model.RulePreview
import dev.claudefleet.mobile.model.SessionTasks
import dev.claudefleet.mobile.model.TaskDetail
import dev.claudefleet.mobile.model.WorkDecision
import dev.claudefleet.mobile.model.WorkRule
import dev.claudefleet.mobile.model.WorkRuleDraft
import dev.claudefleet.mobile.model.WorkTask
import dev.claudefleet.mobile.model.WorkTreeFilters
import dev.claudefleet.mobile.model.WorkTreePage
import dev.claudefleet.mobile.model.WorkView
import dev.claudefleet.mobile.model.WorkViewDraft
import dev.claudefleet.mobile.model.TidyReport
import dev.claudefleet.mobile.model.TidyApplyResult
import dev.claudefleet.mobile.model.TidyApplyItem
import dev.claudefleet.mobile.model.ReopenedWork
import dev.claudefleet.mobile.model.PastWorkSummary

/**
 * The work-graph calls a screen may make — narrow for the same reason
 * [SessionActions] is: no screen holds a [dev.claudefleet.mobile.net.HubClient],
 * so none can skip [AppSession.withClient] and its "a 401 returns us to Pair".
 *
 * The reads are `work`, readonly on the hub. The rest are `work_link`, which
 * the hub hides from a readonly token; a screen offers them only when the
 * credential can write **and** [FleetState.capabilities] lists the action —
 * the app never calls a tool its token may not use.
 */
interface WorkActions {
    /** Tickets in one of the hub's views: `mine`, `sprint`, `recent`. */
    suspend fun tickets(view: String): List<Ticket>

    /** One ticket by key or pasted URL. */
    suspend fun lookup(keyOrUrl: String): Ticket

    /** What resuming [key] would do. */
    suspend fun resumePlan(key: String): ResumePlan

    /** The Today digest since [since], unix seconds. */
    suspend fun today(since: Long): Today

    /** [key]'s context card: acceptance criteria from the hub's cache. */
    suspend fun card(key: String): TicketCard

    /** What Tidy-up suggests, and the work that came back after it was done. */
    suspend fun tidy(): TidyReport
    suspend fun reopened(): List<ReopenedWork>

    /** Apply Tidy-up's choices; each item's outcome. */
    suspend fun tidyApply(items: List<TidyApplyItem>): List<TidyApplyResult>

    /** Clear an item's "reopened". */
    suspend fun dismissReopened(itemId: Long)

    /** A Claude-written summary of a past session on [key], kept in its journal. */
    suspend fun summarize(key: String, linkId: Long): PastWorkSummary

    /**
     * Accept a suggestion. [primary] false confirms it as a secondary link;
     * [expectedVersion] is the link's version — a stale one is `E_CONFLICT`.
     */
    suspend fun confirm(sessionId: Long, linkId: Long, primary: Boolean? = null, expectedVersion: Long? = null): SessionRow

    suspend fun reject(sessionId: Long, linkId: Long, expectedVersion: Long? = null): SessionRow

    suspend fun unlink(sessionId: Long, linkId: Long, expectedVersion: Long? = null): SessionRow

    /**
     * Set a session's work: a looked-up ticket by [itemId], or a bare [key].
     * [primary] false adds a secondary link and leaves the primary alone.
     * [shareAcrossOrgs] links it even though the ticket and the session are
     * in different organisations — only after the person chose to.
     */
    suspend fun link(
        sessionId: Long,
        itemId: Long? = null,
        key: String? = null,
        primary: Boolean? = null,
        expectedVersion: Long? = null,
        shareAcrossOrgs: Boolean = false,
    ): SessionRow

    /** Start work on [key] on [hostAlias]; [projectId] null lets the hub pick. */
    suspend fun start(key: String, hostAlias: String, projectId: Long? = null): SessionRow

    /**
     * Start work on [key] in each of [projectIds] on [hostAlias]: one sibling
     * session per project. Never with `force_cross_org`.
     */
    suspend fun startMany(key: String, hostAlias: String, projectIds: List<Long>): MultiStart

    /** Resume [key]'s last conversation, on [hostAlias] or where the hub would put it. */
    suspend fun resume(key: String, hostAlias: String? = null): SessionRow

    /** Ask the session's Claude for a handover note on its work; the answer comes later. */
    suspend fun handover(sessionId: Long): SessionRow

    /**
     * **Name this work…** (claude-fleet M11.1, on the phone since M13.4a / D20):
     * new local work — a title and no ticket — linked to the session. [key]
     * is optional; the hub refuses one a ticket or another local item has.
     */
    suspend fun name(sessionId: Long, title: String, key: String? = null): SessionRow

    /** Rename local work item [itemId] (never a ticket: the hub refuses one). */
    suspend fun renameItem(itemId: Long, title: String)

    // ---- the Work view (claude-fleet M14): reads are `work`, writes `work_link` ----

    /** One page of the Work tree. */
    suspend fun tree(
        filters: WorkTreeFilters = WorkTreeFilters(),
        cursor: String? = null,
        limit: Int? = null,
        perTask: Int? = null,
    ): WorkTreePage

    /** One task and every session it has. */
    suspend fun task(taskId: String): TaskDetail

    /** Every link of one session, with its task. */
    suspend fun sessionTasks(sessionId: Long): SessionTasks

    /** The review inbox. */
    suspend fun review(cursor: String? = null, limit: Int? = null): ReviewPage

    /** The placement rules — read-only on the phone. */
    suspend fun rules(): List<WorkRule>

    suspend fun rulePreview(rule: WorkRuleDraft): RulePreview

    /** The saved views. */
    suspend fun views(): List<WorkView>

    suspend fun orgImpact(taskId: String, orgId: Long): OrgImpact

    /** Compare-and-set of the session's primary link; [expectedPrimary] `0` = none. */
    suspend fun setPrimary(sessionId: Long, linkId: Long, expectedPrimary: Long?): SessionRow

    /** Undo a decision: the link goes back to a suggestion. */
    suspend fun reconsider(sessionId: Long, linkId: Long, expectedVersion: Long? = null): SessionRow

    /** Keep a conflict on purpose. */
    suspend fun ack(sessionId: Long, linkId: Long, expectedVersion: Long? = null): SessionRow

    /** Several decisions, each answered on its own. */
    suspend fun decideBatch(decisions: List<WorkDecision>): BatchResult

    /** Place a task in a group (local only); an empty [group] clears it. */
    suspend fun place(taskId: String, group: String, expectedVersion: Long, note: String? = null): WorkTask

    suspend fun assignOrg(taskId: String, orgId: Long, impactToken: String): WorkTask

    suspend fun saveRule(rule: WorkRuleDraft): WorkRule

    suspend fun deleteRule(ruleId: Long, expectedVersion: Long? = null)

    suspend fun saveView(view: WorkViewDraft): WorkView

    suspend fun deleteView(viewId: Long)
}

/** [WorkActions] against the paired hub, through [AppSession.withClient]. */
class HubWorkActions(private val session: AppSession) : WorkActions {
    override suspend fun tickets(view: String): List<Ticket> = session.withClient { it.workTickets(view) }

    override suspend fun lookup(keyOrUrl: String): Ticket = session.withClient { it.workLookup(keyOrUrl) }

    override suspend fun resumePlan(key: String): ResumePlan = session.withClient { it.workResumePlan(key) }

    override suspend fun tidy(): TidyReport = session.withClient { it.workTidy() }

    override suspend fun reopened(): List<ReopenedWork> = session.withClient { it.workReopened() }

    override suspend fun tidyApply(items: List<TidyApplyItem>): List<TidyApplyResult> =
        session.withClient { it.workTidyApply(items) }.results

    override suspend fun dismissReopened(itemId: Long) = session.withClient { it.workDismissReopened(itemId) }

    override suspend fun summarize(key: String, linkId: Long): PastWorkSummary = session.withClient { it.workSummarize(key, linkId) }

    override suspend fun today(since: Long): Today = session.withClient { it.workToday(since) }

    override suspend fun card(key: String): TicketCard = session.withClient { it.workCard(key) }

    override suspend fun confirm(sessionId: Long, linkId: Long, primary: Boolean?, expectedVersion: Long?): SessionRow =
        session.withClient { it.confirmWork(sessionId, linkId, primary, expectedVersion) }

    override suspend fun reject(sessionId: Long, linkId: Long, expectedVersion: Long?): SessionRow =
        session.withClient { it.rejectWork(sessionId, linkId, expectedVersion) }

    override suspend fun unlink(sessionId: Long, linkId: Long, expectedVersion: Long?): SessionRow =
        session.withClient { it.unlinkWork(sessionId, linkId, expectedVersion) }

    override suspend fun link(sessionId: Long, itemId: Long?, key: String?, primary: Boolean?, expectedVersion: Long?, shareAcrossOrgs: Boolean): SessionRow =
        session.withClient { it.linkWork(sessionId, itemId, key, primary, expectedVersion, shareAcrossOrgs) }

    override suspend fun start(key: String, hostAlias: String, projectId: Long?): SessionRow =
        session.withClient { it.startWork(key, hostAlias, projectId) }

    override suspend fun startMany(key: String, hostAlias: String, projectIds: List<Long>): MultiStart =
        session.withClient { it.startWorkMany(key, hostAlias, projectIds) }

    override suspend fun resume(key: String, hostAlias: String?): SessionRow =
        session.withClient { it.resumeWork(key, mode = "last", hostAlias = hostAlias) }

    override suspend fun handover(sessionId: Long): SessionRow = session.withClient { it.handoverWork(sessionId) }

    override suspend fun name(sessionId: Long, title: String, key: String?): SessionRow =
        session.withClient { it.nameWork(sessionId, title, key) }

    override suspend fun renameItem(itemId: Long, title: String) = session.withClient { it.renameWorkItem(itemId, title) }

    override suspend fun tree(filters: WorkTreeFilters, cursor: String?, limit: Int?, perTask: Int?): WorkTreePage =
        session.withClient { it.workTree(filters, cursor, limit, perTask) }

    override suspend fun task(taskId: String): TaskDetail = session.withClient { it.workTask(taskId) }

    override suspend fun sessionTasks(sessionId: Long): SessionTasks = session.withClient { it.workSessionTasks(sessionId) }

    override suspend fun review(cursor: String?, limit: Int?): ReviewPage = session.withClient { it.workReview(cursor, limit) }

    override suspend fun rules(): List<WorkRule> = session.withClient { it.workRules() }

    override suspend fun rulePreview(rule: WorkRuleDraft): RulePreview = session.withClient { it.workRulePreview(rule) }

    override suspend fun views(): List<WorkView> = session.withClient { it.workViews() }

    override suspend fun orgImpact(taskId: String, orgId: Long): OrgImpact = session.withClient { it.workOrgImpact(taskId, orgId) }

    override suspend fun setPrimary(sessionId: Long, linkId: Long, expectedPrimary: Long?): SessionRow =
        session.withClient { it.setPrimaryWork(sessionId, linkId, expectedPrimary) }

    override suspend fun reconsider(sessionId: Long, linkId: Long, expectedVersion: Long?): SessionRow =
        session.withClient { it.reconsiderWork(sessionId, linkId, expectedVersion) }

    override suspend fun ack(sessionId: Long, linkId: Long, expectedVersion: Long?): SessionRow =
        session.withClient { it.ackWork(sessionId, linkId, expectedVersion) }

    override suspend fun decideBatch(decisions: List<WorkDecision>): BatchResult = session.withClient { it.decideWorkBatch(decisions) }

    override suspend fun place(taskId: String, group: String, expectedVersion: Long, note: String?): WorkTask =
        session.withClient { it.placeWork(taskId, group, expectedVersion, note) }

    override suspend fun assignOrg(taskId: String, orgId: Long, impactToken: String): WorkTask =
        session.withClient { it.assignWorkOrg(taskId, orgId, impactToken) }

    override suspend fun saveRule(rule: WorkRuleDraft): WorkRule = session.withClient { it.saveWorkRule(rule) }

    override suspend fun deleteRule(ruleId: Long, expectedVersion: Long?) = session.withClient { it.deleteWorkRule(ruleId, expectedVersion) }

    override suspend fun saveView(view: WorkViewDraft): WorkView = session.withClient { it.saveWorkView(view) }

    override suspend fun deleteView(viewId: Long) = session.withClient { it.deleteWorkView(viewId) }
}
