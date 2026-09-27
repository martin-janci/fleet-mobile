package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.WorkViewActions
import dev.claudefleet.mobile.model.SessionTasks
import dev.claudefleet.mobile.model.TaskDetail
import dev.claudefleet.mobile.model.TreePage
import dev.claudefleet.mobile.model.WorkFilters
import dev.claudefleet.mobile.model.WorkView
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.ToolCatalog
import dev.claudefleet.mobile.net.json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/**
 * Hub answers for the Work view's reads (claude-fleet M14.1b), in the hub's
 * own wire shape: `TreePage`, `TaskDetail`, `SessionTasks` and `WorkView`
 * from `service/work/view.rs` and `store/work_view.rs`, `skip_serializing_if`
 * fields absent, and fields the phone does not read (`evidence`,
 * `org_source`, `placement_version`, `editable`, `rule_id`) present.
 */
internal object WorkViewJson {
    /**
     * An unbound phone's first page with `limit: 2`: two orgs and unassigned
     * work. The page fills Acme's first section (two of its three tasks) and
     * stops; every other section is a header and a count.
     */
    const val TREE_TWO_ORGS = """
{"tasks":[
 {"task_id":"item:70","item_id":70,"key":"PAY-7","title":"Refund flow","url":"https://acme.atlassian.net/browse/PAY-7","kind":"tracker",
  "tracker_id":1,"tracker_name":"Acme Jira","provider":"jira","tracker_state":"ok","status_category":"in_progress","status_name":"In Review",
  "unavailable":false,"assignees":["Ana"],"mine":true,"org_id":1,"org_source":"tracker","org_fenced":true,"org_mixed":false,
  "group":{"id":"tracker:1:PAY","label":"PAY","source":"tracker","tracker_value":"PAY","editable":true},
  "counts":{"active":1,"ended":1,"suggested":0},"needs_you":true,"review":false,"last_activity_at":1790000200,"repos":["acme/pay"],
  "placement_version":0,
  "sessions":[
   {"link_id":42,"link_version":3,"state":"active","primary":true,"session_id":7,"name":"pay","host":"pine","source":"branch","strength":"explicit",
    "why":"branch pay-7-refund","created_at":1790000000,"claude_status":"blocked","needs_you":true,"archived":false,"resumable":true,
    "branch":"pay-7-refund","cross_org":false,"other_tasks":0},
   {"link_id":41,"link_version":1,"state":"ended","primary":false,"name":"pay-old","host":"hetzner","source":"started",
    "why":"started from the ticket","created_at":1789000000,"ended_at":1789500000,"end_reason":"killed","needs_you":false,"archived":false,
    "resumable":true,"cross_org":false,"other_tasks":0}],
  "sessions_more":0},
 {"task_id":"item:90","item_id":90,"key":"PAY-9","title":"Ledger","kind":"tracker","tracker_id":1,"status_category":"todo",
  "unavailable":false,"mine":false,"org_id":1,"org_source":"tracker","org_fenced":true,"org_mixed":false,
  "group":{"id":"tracker:1:PAY","label":"PAY","source":"tracker","editable":true},
  "counts":{"active":0,"ended":0,"suggested":0},"needs_you":false,"review":false,"placement_version":0,"sessions":[],"sessions_more":0}
],
"groups":[
 {"org_id":1,"org_name":"Acme","group":{"id":"tracker:1:PAY","label":"PAY","source":"tracker","editable":true},"count":3},
 {"org_id":1,"org_name":"Acme","group":{"id":"none","label":"No group","source":"none","editable":true},"count":1},
 {"org_id":2,"org_name":"Globex","group":{"id":"repo:globex/api","label":"globex/api","source":"repo","editable":true},"count":2},
 {"group":{"id":"none","label":"No group","source":"none","editable":true},"count":1}
],
"orgs":[{"id":1,"name":"Acme","color":"#2266ff"},{"id":2,"name":"Globex"}],
"trackers":[{"id":1,"name":"Acme Jira","provider":"jira","state":"ok","org_id":1},{"id":3,"name":"Globex Linear","provider":"linear","state":"ok","org_id":2}],
"total":7,"next_cursor":"c-main","generated_at":1790000300}
"""

    /** Acme's PAY section alone, from the top (`filters.org` + `filters.group`): three tasks, no more. */
    const val TREE_PAY_SECTION = """
{"tasks":[
 {"task_id":"item:70","key":"PAY-7","title":"Refund flow","kind":"tracker","unavailable":false,"mine":true,"org_id":1,"org_source":"tracker",
  "org_fenced":true,"org_mixed":false,"group":{"id":"tracker:1:PAY","label":"PAY","source":"tracker","editable":true},
  "counts":{"active":1,"ended":1,"suggested":0},"needs_you":true,"review":false,"placement_version":0,"sessions":[],"sessions_more":2},
 {"task_id":"item:90","key":"PAY-9","title":"Ledger","kind":"tracker","unavailable":false,"mine":false,"org_id":1,"org_source":"tracker",
  "org_fenced":true,"org_mixed":false,"group":{"id":"tracker:1:PAY","label":"PAY","source":"tracker","editable":true},
  "counts":{"active":0,"ended":0,"suggested":0},"needs_you":false,"review":false,"placement_version":0,"sessions":[],"sessions_more":0},
 {"task_id":"item:95","key":"PAY-11","title":"Payouts","kind":"tracker","unavailable":false,"mine":false,"org_id":1,"org_source":"tracker",
  "org_fenced":true,"org_mixed":false,"group":{"id":"tracker:1:PAY","label":"PAY","source":"tracker","editable":true},
  "counts":{"active":0,"ended":0,"suggested":1},"needs_you":false,"review":true,"placement_version":0,"sessions":[],"sessions_more":0}
],
"groups":[{"org_id":1,"org_name":"Acme","group":{"id":"tracker:1:PAY","label":"PAY","source":"tracker","editable":true},"count":3}],
"orgs":[{"id":1,"name":"Acme"},{"id":2,"name":"Globex"}],"trackers":[],"total":3,"generated_at":1790000400}
"""

    /**
     * A phone paired with `fleet-hub pair --org 1`, the org's D31 flag off:
     * the hub answers Acme and nothing else — no Globex in `orgs`, no
     * Globex tracker, no unassigned section, and a total that counts Acme's
     * tasks only. Nothing in it says another org exists.
     */
    const val TREE_BOUND_TO_ACME = """
{"tasks":[
 {"task_id":"item:70","item_id":70,"key":"PAY-7","title":"Refund flow","kind":"tracker","tracker_id":1,"unavailable":false,"mine":true,
  "org_id":1,"org_source":"tracker","org_fenced":true,"org_mixed":false,
  "group":{"id":"tracker:1:PAY","label":"PAY","source":"tracker","editable":true},
  "counts":{"active":1,"ended":0,"suggested":0},"needs_you":false,"review":false,"placement_version":0,
  "sessions":[{"link_id":42,"link_version":3,"state":"active","primary":true,"session_id":7,"name":"pay","host":"pine","source":"branch",
   "why":"branch pay-7-refund","created_at":1790000000,"claude_status":"working","needs_you":false,"archived":false,"resumable":true,
   "cross_org":false,"other_tasks":0}],
  "sessions_more":0}
],
"groups":[{"org_id":1,"org_name":"Acme","group":{"id":"tracker:1:PAY","label":"PAY","source":"tracker","editable":true},"count":1}],
"orgs":[{"id":1,"name":"Acme"}],
"trackers":[{"id":1,"name":"Acme Jira","provider":"jira","state":"ok","org_id":1}],
"total":1,"generated_at":1790000300}
"""

    /** `work { task }`: every session of PAY-7, one of them live, with evidence the phone does not draw. */
    const val TASK_PAY7 = """
{"task":{"task_id":"item:70","item_id":70,"key":"PAY-7","title":"Refund flow","url":"https://acme.atlassian.net/browse/PAY-7","kind":"tracker",
  "tracker_id":1,"tracker_name":"Acme Jira","status_category":"in_progress","status_name":"In Review","unavailable":false,"mine":true,
  "org_id":1,"org_source":"tracker","org_fenced":true,"org_mixed":false,
  "group":{"id":"tracker:1:PAY","label":"PAY","source":"tracker","editable":true},
  "counts":{"active":1,"ended":1,"suggested":1},"needs_you":false,"review":true,"placement_version":0,
  "sessions":[
   {"link_id":42,"link_version":3,"state":"active","primary":true,"session_id":7,"name":"pay","host":"pine","source":"branch",
    "why":"branch pay-7-refund","evidence":[{"kind":"branch","value":"pay-7-refund"}],"created_at":1790000000,"claude_status":"idle",
    "needs_you":false,"archived":false,"resumable":true,"cross_org":false,"other_tasks":1},
   {"link_id":43,"link_version":1,"state":"suggested","primary":false,"session_id":8,"name":"pay-tests","host":"pine","source":"prompt",
    "strength":"weak","why":"a prompt named PAY-7","created_at":1790000100,"needs_you":false,"archived":false,"resumable":false,
    "cross_org":false,"other_tasks":0},
   {"link_id":41,"link_version":2,"state":"ended","primary":false,"name":"pay-old","host":"hetzner","source":"started",
    "why":"started from the ticket","created_at":1789000000,"ended_at":1789500000,"needs_you":false,"archived":false,"resumable":true,
    "cross_org":false,"other_tasks":0}],
  "sessions_more":0},
 "aliases":["ref:PAY-7"],"description":"Refunds must go out in 24 h.",
 "last_outcome":{"at":1789500000,"name":"pay-old","host":"hetzner","branch":"pay-7-old","summary":"Half done.","summary_kind":"note"},
 "rules":[3]}
"""

    /** `work { task }` for a task with no session at all. */
    const val TASK_NO_SESSION = """
{"task":{"task_id":"item:90","item_id":90,"key":"PAY-9","title":"Ledger","kind":"tracker","unavailable":false,"mine":false,
  "org_id":1,"org_source":"tracker","org_fenced":true,"org_mixed":false,
  "group":{"id":"tracker:1:PAY","label":"PAY","source":"tracker","editable":true},
  "counts":{"active":0,"ended":0,"suggested":0},"needs_you":false,"review":false,"placement_version":0,"sessions":[],"sessions_more":0}}
"""

    /** `work { session_tasks }`: a link's fields flattened beside its `task`. */
    const val SESSION_TASKS = """
{"session_id":7,"org_id":1,"primary_link_id":42,"links":[
 {"link_id":42,"link_version":3,"state":"active","primary":true,"session_id":7,"name":"pay","host":"pine","source":"branch",
  "why":"branch pay-7-refund","evidence":[{"kind":"branch"}],"created_at":1790000000,"needs_you":false,"archived":false,"resumable":true,
  "cross_org":false,"other_tasks":1,
  "task":{"task_id":"item:70","key":"PAY-7","title":"Refund flow","kind":"tracker","status_name":"In Review","unavailable":false,"org_id":1,"tracker_name":"Acme Jira"}},
 {"link_id":44,"link_version":1,"state":"active","primary":false,"session_id":7,"name":"pay","host":"pine","source":"manual",
  "why":"set by hand","created_at":1790000050,"needs_you":false,"archived":false,"resumable":true,"cross_org":false,"other_tasks":1,
  "task":{"task_id":"item:90","key":"PAY-9","title":"Ledger","kind":"tracker","unavailable":false,"org_id":1}},
 {"link_id":45,"link_version":1,"state":"suggested","primary":false,"session_id":7,"name":"pay","source":"prompt","why":"a prompt named OPS-2",
  "created_at":1790000060,"needs_you":false,"archived":false,"resumable":false,"cross_org":false,"other_tasks":0,
  "task":{"task_id":"ref:OPS-2","key":"OPS-2","title":"","kind":"ref","unavailable":false}},
 {"link_id":40,"link_version":2,"state":"ended","primary":false,"name":"pay","source":"branch","why":"branch pay-5","created_at":1789000000,
  "ended_at":1789400000,"needs_you":false,"archived":false,"resumable":true,"cross_org":false,"other_tasks":0,
  "task":{"task_id":"item:50","key":"PAY-5","title":"Old","kind":"tracker","unavailable":false,"org_id":1}},
 {"link_id":39,"link_version":2,"state":"rejected","primary":false,"session_id":7,"name":"pay","source":"prompt","why":"a prompt named PAY-1",
  "created_at":1789000000,"needs_you":false,"archived":false,"resumable":false,"cross_org":false,"other_tasks":0,
  "task":{"task_id":"item:10","key":"PAY-1","title":"Not it","kind":"tracker","unavailable":false,"org_id":1}}
]}
"""

    /** `work { views }`: two shared views, one of them with a word this build does not know. */
    const val VIEWS = """
[{"id":1,"name":"My open work","filters":{"status":"open","mine":true},"version":1,"updated_at":1790000000},
 {"id":2,"name":"Acme review","filters":{"org":1,"review":true,"has":"someday"},"owner_org":1,"version":2,"updated_at":1790000000},
 {"id":3,"name":"Broken","filters":null,"version":1,"updated_at":1790000000}]
"""

    fun tree(text: String): TreePage = json.decodeFromString(TreePage.serializer(), text)
    fun task(text: String): TaskDetail = json.decodeFromString(TaskDetail.serializer(), text)
    fun views(text: String = VIEWS): List<WorkView> = json.decodeFromString(ListSerializer(WorkView.serializer()), text)

    /** A hub that lists every Work view read and the work-graph writes this app already makes. */
    val WORK_VIEW_CAPS = HubCapabilities.of(
        ToolCatalog(
            names = setOf("work", "work_link"),
            actions = mapOf(
                "work" to setOf("tickets", "lookup", "resume_plan", "card", "tree", "task", "session_tasks", "views"),
                "work_link" to setOf("link", "confirm", "reject", "unlink", "start", "resume"),
            ),
        ),
    )
}

/** One `work { tree }` call as the phone made it. */
internal data class TreeCall(val filters: JsonObject, val cursor: String?, val limit: Int?)

/** Records every read; each answers what the test put in its slot. */
internal class FakeWorkViewActions : WorkViewActions {
    val treeCalls = mutableListOf<TreeCall>()
    val taskCalls = mutableListOf<String>()
    val sessionTaskCalls = mutableListOf<Long>()
    var viewsCalls = 0
    var treeAnswer: (TreeCall) -> TreePage = { TreePage() }
    var failTree: Throwable? = null
    var taskAnswer: TaskDetail? = null
    var sessionTasksAnswer = SessionTasks(sessionId = 0)
    var viewsAnswer: List<WorkView> = emptyList()

    override suspend fun tree(filters: WorkFilters, cursor: String?, limit: Int?, perTask: Int?): TreePage {
        val call = TreeCall(filters.toJson(), cursor, limit)
        treeCalls += call
        failTree?.let { throw it }
        return treeAnswer(call)
    }

    override suspend fun task(taskId: String): TaskDetail {
        taskCalls += taskId
        return taskAnswer ?: throw HubError.Tool("E_NOTFOUND", "task $taskId not found")
    }

    override suspend fun sessionTasks(sessionId: Long): SessionTasks {
        sessionTaskCalls += sessionId
        return sessionTasksAnswer
    }

    override suspend fun views(): List<WorkView> {
        viewsCalls++
        return viewsAnswer
    }
}
