package dev.claudefleet.mobile.ui

/**
 * A hub answering a phone paired with `fleet-hub pair --org 1` (claude-fleet
 * M14.1b, `OrgScope::Org`): what such a token is sent, in the contract's own
 * shape — `docs/superpowers/specs/2026-09-27-work-view-design.md`
 * (*Security model*) and `docs/hub.md` (*pair --org*) in claude-fleet.
 *
 * It is listed its own org alone and sees only that org's rows; while the
 * org's `bound_sees_unassigned` (D31) is on, unassigned rows too. Anything
 * else — org 2's task 77, org 2's session 9 — answers exactly as an unknown
 * id, `E_NOTFOUND`. It is never sent a `work:changed` frame.
 */
internal object BoundPhoneJson {
    const val BOUND_ORG = 1L

    /** `work { orgs }`: org 1 alone — never the org 2 an unbound token would see. */
    const val ORGS = """
[{"id":1,"name":"Acme","color":"#2266ff","isolate_sessions":false,"bound_sees_unassigned":false,"created_at":1790000000,
  "trackers":[{"id":7,"name":"Acme Jira"}]}]
"""

    /** `work { tree }` with D31 off: org 1's tasks and headers only. */
    const val TREE = """
{"tasks":[
  {"task_id":"item:12","item_id":12,"key":"ABC-12","title":"Login fails","kind":"tracker","tracker_id":1,"tracker_state":"ok",
   "status_category":"in_progress","org_id":1,"org_source":"tracker","org_fenced":true,
   "group":{"id":"tracker:1:ABC","label":"ABC","source":"tracker","tracker_value":"ABC","editable":true},
   "counts":{"active":1,"ended":0,"suggested":1},"placement_version":0},
  {"task_id":"item:20","item_id":20,"title":"Refund audit","kind":"local","org_id":1,"org_source":"sessions",
   "group":{"id":"label:Payments","label":"Payments","source":"manual","editable":true},
   "counts":{"active":0,"ended":1,"suggested":0},"placement_version":1}
 ],
 "groups":[
  {"org_id":1,"org_name":"Acme","group":{"id":"tracker:1:ABC","label":"ABC","source":"tracker","tracker_value":"ABC","editable":true},"count":1},
  {"org_id":1,"org_name":"Acme","group":{"id":"label:Payments","label":"Payments","source":"manual","editable":true},"count":1}
 ],
 "orgs":[{"id":1,"name":"Acme","color":"#2266ff"}],
 "trackers":[{"id":1,"name":"Jira (acme)","provider":"jira","state":"ok","org_id":1}],
 "total":2,"generated_at":1790000300}
"""

    /** The same tree with D31 on: org 1's tasks and the unassigned ones, still nothing of org 2. */
    const val TREE_WITH_UNASSIGNED = """
{"tasks":[
  {"task_id":"item:12","item_id":12,"key":"ABC-12","title":"Login fails","kind":"tracker","tracker_id":1,"tracker_state":"ok",
   "status_category":"in_progress","org_id":1,"org_source":"tracker","org_fenced":true,
   "group":{"id":"tracker:1:ABC","label":"ABC","source":"tracker","tracker_value":"ABC","editable":true},
   "counts":{"active":1,"ended":0,"suggested":1},"placement_version":0},
  {"task_id":"ref:OLD-1","key":"OLD-1","kind":"ref","org_source":"none",
   "group":{"id":"key:OLD","label":"OLD","source":"key","editable":true},"counts":{"ended":1}}
 ],
 "groups":[
  {"org_id":1,"org_name":"Acme","group":{"id":"tracker:1:ABC","label":"ABC","source":"tracker","tracker_value":"ABC","editable":true},"count":1},
  {"group":{"id":"key:OLD","label":"OLD","source":"key","editable":true},"count":1}
 ],
 "orgs":[{"id":1,"name":"Acme","color":"#2266ff"}],
 "trackers":[{"id":1,"name":"Jira (acme)","provider":"jira","state":"ok","org_id":1}],
 "total":2,"generated_at":1790000300}
"""

    /** `work { task: "item:12" }`: org 1's task, with only the sessions this token may see. */
    const val TASK = """
{"task":{"task_id":"item:12","item_id":12,"key":"ABC-12","title":"Login fails","kind":"tracker","tracker_id":1,
   "tracker_state":"ok","status_category":"in_progress","org_id":1,"org_source":"tracker","org_fenced":true,
   "group":{"id":"tracker:1:ABC","label":"ABC","source":"tracker","tracker_value":"ABC","editable":true},
   "counts":{"active":1,"ended":0,"suggested":0},"placement_version":0,
   "sessions":[
     {"link_id":42,"link_version":3,"state":"active","primary":true,"session_id":7,"name":"api","host":"mefistos","source":"manual",
      "why":"set by hand","resumable":true,"cross_org":false,"other_tasks":0}
   ]},
 "aliases":[]}
"""

    /** `work { session_tasks: 7 }`: org 1's session and its org 1 links. */
    const val SESSION_TASKS = """
{"session_id":7,"org_id":1,"primary_link_id":42,"links":[
  {"link_id":42,"link_version":3,"state":"active","primary":true,"session_id":7,"name":"api","host":"mefistos","why":"set by hand",
   "task":{"task_id":"item:12","key":"ABC-12","title":"Login fails","kind":"tracker","org_id":1}},
  {"link_id":45,"link_version":2,"state":"suggested","session_id":7,"why":"prompt mentions ABC-15",
   "task":{"task_id":"item:15","key":"ABC-15","title":"Signup","kind":"tracker","org_id":1}}
]}
"""

    /** `work { review }`: org 1's suggestion — no cross-org card, which a bound token is never sent. */
    const val REVIEW = """
{"items":[
  {"review_id":"link:45","kind":"suggestion","session_id":7,"session_name":"api","host":"mefistos","link_id":45,"link_version":2,
   "task":{"task_id":"item:15","key":"ABC-15","title":"Signup","org_id":1},
   "why":["prompt mentions ABC-15"],"strength":"strong","preselected":false,"alternatives":[],"created_at":1790000000}
 ],"total":1}
"""

    /** What the hub says for org 2's task 77, word for word as for a task that does not exist. */
    const val TASK_NOT_FOUND = "task item:77 not found"

    /** And for org 2's session 9. */
    const val SESSION_NOT_FOUND = "session 9 not found"
}
