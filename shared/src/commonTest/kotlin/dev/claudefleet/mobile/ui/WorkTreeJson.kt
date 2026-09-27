package dev.claudefleet.mobile.ui

/**
 * Hub answers for the Work view (claude-fleet M14), in the contract's own
 * shape — `docs/superpowers/specs/2026-09-27-work-view-design.md` in
 * claude-fleet. Nulls are left out the way the hub strips them, a few fields
 * a later hub might add are present (and must be ignored), and one value of
 * every wire enum this build does not know is there to read as `Unknown`.
 */
internal object WorkTreeJson {
    /**
     * `work { tree }`: two orgs and the unassigned section, a group of each
     * source, a task with sessions (the tree may carry some), a local task
     * with none, a bare key, and a `next_cursor` — the first page of more.
     */
    const val TREE = """
{"tasks":[
  {"task_id":"item:12","item_id":12,"key":"ABC-12","title":"Login fails","url":"https://acme.atlassian.net/browse/ABC-12",
   "kind":"tracker","tracker_id":1,"tracker_name":"Jira (acme)","provider":"jira","tracker_state":"ok",
   "status_category":"in_progress","status_name":"In Review","unavailable":false,"assignees":["Ana"],"mine":true,
   "org_id":1,"org_source":"tracker","org_fenced":true,"org_mixed":false,
   "group":{"id":"tracker:1:ABC","label":"ABC","source":"tracker","tracker_value":"ABC","editable":true},
   "counts":{"active":1,"ended":2,"suggested":1},"needs_you":true,"review":true,"last_activity_at":1790000200,
   "repos":["acme/api"],"placement_version":0,
   "sessions":[{"link_id":42,"link_version":3,"state":"active","primary":true,"session_id":7,"name":"ABC-12 login","host":"mefistos",
                "source":"manual","strength":"explicit","why":"branch abc-12-login · R3","created_at":1790000000,"decided_at":1790000100,
                "claude_status":"idle","needs_you":false,"archived":false,"resumable":true,"branch":"abc-12-login","cross_org":false,"other_tasks":1}],
   "sessions_more":0,"a_field_from_a_newer_hub":{"x":1}},
  {"task_id":"item:13","item_id":13,"key":"ABC-13","title":"Tracker down","kind":"tracker","tracker_id":1,"tracker_state":"auth_failed",
   "status_category":"todo","org_id":1,"org_source":"tracker","org_fenced":true,
   "group":{"id":"tracker:1:ABC","label":"ABC","source":"tracker","tracker_value":"ABC","editable":true},
   "counts":{"active":0,"ended":0,"suggested":0},"placement_version":0},
  {"task_id":"item:77","item_id":77,"title":"Ops cleanup","kind":"local","org_id":2,"org_source":"sessions",
   "group":{"id":"label:Ops","label":"Ops","source":"manual","editable":true},
   "counts":{"active":0,"ended":1,"suggested":0},"placement_version":4},
  {"task_id":"ref:OLD-1","key":"OLD-1","kind":"ref","org_source":"none",
   "group":{"id":"key:OLD","label":"OLD","source":"key","editable":true},
   "counts":{"ended":1},"status_category":"blocked_by_a_newer_hub"}
 ],
 "groups":[
  {"org_id":1,"org_name":"Acme","group":{"id":"tracker:1:ABC","label":"ABC","source":"tracker","tracker_value":"ABC","editable":true},"count":3},
  {"org_id":1,"org_name":"Acme","group":{"id":"label:Payments","label":"Payments","source":"rule","rule_id":3,"editable":true},"count":1},
  {"org_id":2,"org_name":"Globex","group":{"id":"label:Ops","label":"Ops","source":"manual","editable":true},"count":1},
  {"group":{"id":"key:OLD","label":"OLD","source":"key","editable":true},"count":1},
  {"group":{"id":"none","label":"","source":"a_source_from_later"},"count":2}
 ],
 "orgs":[{"id":1,"name":"Acme","color":"#2266ff"},{"id":2,"name":"Globex"}],
 "trackers":[{"id":1,"name":"Jira (acme)","provider":"jira","state":"ok","org_id":1}],
 "total":8,"next_cursor":"c1.abc","generated_at":1790000300}
"""

    /** `work { tree, filters: {group: "tracker:1:ABC", org: 1} }`, page two of the ABC section. */
    const val TREE_ABC_PAGE_TWO = """
{"tasks":[
  {"task_id":"item:14","item_id":14,"key":"ABC-14","title":"Third","kind":"tracker","tracker_id":1,"tracker_state":"ok","org_id":1,"org_source":"tracker",
   "group":{"id":"tracker:1:ABC","label":"ABC","source":"tracker"},"counts":{}}
 ],
 "groups":[{"org_id":1,"org_name":"Acme","group":{"id":"tracker:1:ABC","label":"ABC","source":"tracker"},"count":3}],
 "orgs":[{"id":1,"name":"Acme"}],"trackers":[],"total":3,"generated_at":1790000400}
"""

    /** `work { task }`: every session, a placement, an alias, and evidence the phone keeps as JSON. */
    const val TASK = """
{"task":{"task_id":"item:12","item_id":12,"key":"ABC-12","title":"Login fails","kind":"tracker","tracker_id":1,"tracker_name":"Jira (acme)",
   "tracker_state":"ok","status_category":"in_progress","status_name":"In Review","org_id":1,"org_source":"tracker","org_fenced":true,
   "group":{"id":"tracker:1:ABC","label":"ABC","source":"tracker","tracker_value":"ABC","editable":true},
   "counts":{"active":1,"ended":1,"suggested":1},"placement_version":2,"repos":["acme/api"],
   "sessions":[
     {"link_id":42,"link_version":3,"state":"active","primary":true,"session_id":7,"name":"api","host":"mefistos","source":"manual",
      "why":"set by hand","evidence":[{"kind":"branch","value":"abc-12-login"}],"resumable":true},
     {"link_id":43,"link_version":1,"state":"suggested","primary":false,"session_id":8,"name":"web","host":"pine","source":"branch",
      "strength":"strong","rule":"R3","why":"branch abc-12 · R3"},
     {"link_id":40,"link_version":6,"state":"ended","primary":false,"name":"old api","host":"pine","source":"started",
      "ended_at":1789000000,"end_reason":"killed","resumable":true,"pr_url":"https://github.com/acme/api/pull/9"},
     {"link_id":39,"link_version":2,"state":"rejected","name":"noise","host":"pine","decided_at":1788000000}
   ]},
 "aliases":["ref:ABC-12"],
 "description":"[fenced tracker text] Users cannot log in",
 "last_outcome":{"at":1789000000,"name":"old api","host":"pine","branch":"abc-12-v1","pr_url":"https://github.com/acme/api/pull/9"},
 "placement":{"group":"Payments","note":"moved for the audit","version":2,"updated_at":1789500000,"updated_by":"desktop"},
 "rules":[3]}
"""

    /** `work { session_tasks }`: a primary, a secondary, a suggestion and a past link. */
    const val SESSION_TASKS = """
{"session_id":7,"org_id":1,"primary_link_id":42,"links":[
  {"link_id":42,"link_version":3,"state":"active","primary":true,"session_id":7,"name":"api","host":"mefistos","why":"set by hand",
   "task":{"task_id":"item:12","key":"ABC-12","title":"Login fails","kind":"tracker","status_category":"in_progress","status_name":"In Review","org_id":1,"tracker_name":"Jira (acme)"}},
  {"link_id":44,"link_version":1,"state":"active","primary":false,"session_id":7,"name":"api","host":"mefistos",
   "task":{"task_id":"ref:OPS-1","key":"OPS-1","kind":"ref"}},
  {"link_id":45,"link_version":2,"state":"suggested","session_id":7,"why":"prompt mentions ABC-15",
   "task":{"task_id":"item:15","key":"ABC-15","title":"Signup","kind":"tracker"}},
  {"link_id":30,"link_version":5,"state":"ended","ended_at":1788000000,"end_reason":"unlinked",
   "task":{"task_id":"item:9","key":"ABC-9","title":"Old","kind":"tracker","unavailable":true}}
]}
"""

    /** `work { review }`: a suggestion with an alternative, and a cross-org conflict. */
    const val REVIEW = """
{"items":[
  {"review_id":"link:42","kind":"suggestion","session_id":7,"session_name":"api","host":"mefistos","link_id":42,"link_version":2,
   "task":{"task_id":"item:12","key":"ABC-12","title":"Login fails","org_id":1},
   "why":["branch abc-12-login since 09:05 · R3"],"strength":"strong","rule":"R3","preselected":false,
   "alternatives":[{"link_id":43,"task_id":"ref:ABC-13","key":"ABC-13","title":""}],"created_at":1790000000},
  {"review_id":"link:50","kind":"cross_org","session_id":9,"session_name":"ops","host":"h-a","link_id":50,"link_version":1,
   "task":{"task_id":"item:77","title":"Ops cleanup","org_id":2},"why":["linked with force_cross_org"]},
  {"review_id":"x:1","kind":"a_kind_from_later","session_id":1,"link_id":51}
 ],"total":3}
"""

    const val RULES = """
[{"id":3,"name":"Payments","enabled":true,"version":2,
  "conditions":{"tracker_id":1,"container":"PAY"},"group":"Payments","created_at":1790000000,"updated_at":1790000000}]
"""

    const val VIEWS = """
[{"id":1,"name":"My open work","filters":{"status":"open","mine":true,"org":1},"version":1,"updated_at":1790000000},
 {"id":2,"name":"Unassigned","filters":{"org":"none","tracker":"local"},"version":3}]
"""

    const val ORG_IMPACT = """
{"task_id":"item:77","to_org":2,"allowed":true,
 "links":[{"link_id":5,"session_id":9,"name":"api","host":"h-a","state":"active","session_org":1,"becomes_cross_org":true}],
 "hosts_losing":["h-b"],"hosts_gaining":["h-c"],"bound_clients_losing":1,"bound_clients_gaining":0,
 "journal_entries":4,"summaries":1,"impact_token":"tok-impact"}
"""

    const val BATCH = """
{"results":[{"link_id":42,"ok":true,"version":3},{"link_id":46,"ok":false,"code":"E_CONFLICT","message":"link 46 changed: now confirmed"}]}
"""
}
