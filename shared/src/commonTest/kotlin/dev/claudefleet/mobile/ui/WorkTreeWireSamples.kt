package dev.claudefleet.mobile.ui

/**
 * Work view answers exactly as the hub serialises them (claude-fleet work graph M14),
 * dumped by `WORK_VIEW_FIXTURE_DIR=<dir> cargo test -p fleet-core --lib dump_wire_samples`
 * in claude-fleet. Hand-written fixtures prove the screens; these prove the models
 * read what the hub really sends. Regenerate them when the hub's shapes change.
 */
internal object WorkTreeWireSamples {
    val TREE = """
{
  "generated_at": 1790518155,
  "groups": [
    {
      "count": 1,
      "group": {
        "editable": true,
        "id": "label:Security",
        "label": "Security",
        "source": "manual",
        "tracker_value": "TP"
      },
      "org_id": 1,
      "org_name": "Acme"
    },
    {
      "count": 2,
      "group": {
        "editable": true,
        "id": "tracker:1:TP",
        "label": "TP",
        "source": "tracker",
        "tracker_value": "TP"
      },
      "org_id": 1,
      "org_name": "Acme"
    }
  ],
  "orgs": [
    {
      "id": 1,
      "name": "Acme"
    },
    {
      "id": 2,
      "name": "Beta"
    }
  ],
  "tasks": [
    {
      "counts": {
        "active": 1,
        "ended": 0,
        "suggested": 0
      },
      "group": {
        "editable": true,
        "id": "label:Security",
        "label": "Security",
        "source": "manual",
        "tracker_value": "TP"
      },
      "item_id": 2,
      "key": "TK-2",
      "kind": "tracker",
      "last_activity_at": 1790518155,
      "mine": true,
      "needs_you": false,
      "org_fenced": true,
      "org_id": 1,
      "org_mixed": false,
      "org_source": "tracker",
      "placement_version": 1,
      "provider": "jira",
      "repos": [
        "acme/api"
      ],
      "review": false,
      "sessions": [
        {
          "archived": false,
          "created_at": 1790518155,
          "cross_org": false,
          "decided_at": 1790518155,
          "host": "h1",
          "link_id": 2,
          "link_version": 1,
          "name": "one",
          "needs_you": false,
          "other_tasks": 1,
          "primary": false,
          "resumable": true,
          "session_id": 1,
          "source": "manual",
          "state": "active",
          "strength": "explicit",
          "why": "linked by a person"
        }
      ],
      "sessions_more": 0,
      "status_category": "in_progress",
      "status_name": "In Progress",
      "task_id": "item:2",
      "title": "Audit log",
      "tracker_id": 1,
      "tracker_name": "Jira (acme)",
      "tracker_state": "unconfigured",
      "unavailable": false
    },
    {
      "counts": {
        "active": 1,
        "ended": 0,
        "suggested": 0
      },
      "group": {
        "editable": true,
        "id": "tracker:1:TP",
        "label": "TP",
        "source": "tracker",
        "tracker_value": "TP"
      },
      "item_id": 1,
      "key": "TK-1",
      "kind": "tracker",
      "last_activity_at": 1790518155,
      "mine": true,
      "needs_you": false,
      "org_fenced": true,
      "org_id": 1,
      "org_mixed": false,
      "org_source": "tracker",
      "placement_version": 0,
      "provider": "jira",
      "repos": [
        "acme/api"
      ],
      "review": false,
      "sessions": [
        {
          "archived": false,
          "created_at": 1790518155,
          "cross_org": false,
          "decided_at": 1790518155,
          "host": "h1",
          "link_id": 1,
          "link_version": 1,
          "name": "one",
          "needs_you": false,
          "other_tasks": 1,
          "primary": true,
          "resumable": true,
          "session_id": 1,
          "source": "manual",
          "state": "active",
          "strength": "explicit",
          "why": "linked by a person"
        }
      ],
      "sessions_more": 0,
      "status_category": "in_progress",
      "status_name": "In Progress",
      "task_id": "item:1",
      "title": "Login fails",
      "tracker_id": 1,
      "tracker_name": "Jira (acme)",
      "tracker_state": "unconfigured",
      "unavailable": false
    },
    {
      "counts": {
        "active": 0,
        "ended": 0,
        "suggested": 1
      },
      "group": {
        "editable": true,
        "id": "tracker:1:TP",
        "label": "TP",
        "source": "tracker",
        "tracker_value": "TP"
      },
      "item_id": 3,
      "key": "TK-3",
      "kind": "tracker",
      "last_activity_at": 1790518155,
      "mine": true,
      "needs_you": false,
      "org_fenced": true,
      "org_id": 1,
      "org_mixed": false,
      "org_source": "tracker",
      "placement_version": 0,
      "provider": "jira",
      "repos": [
        "acme/api"
      ],
      "review": true,
      "sessions": [
        {
          "archived": false,
          "created_at": 1790518155,
          "cross_org": false,
          "host": "h1",
          "link_id": 3,
          "link_version": 1,
          "name": "two",
          "needs_you": false,
          "other_tasks": 0,
          "primary": false,
          "resumable": true,
          "rule": "R6",
          "session_id": 2,
          "source": "prompt",
          "state": "suggested",
          "strength": "weak",
          "why": "mentioned in a prompt TK-3 \u00b7 R6"
        }
      ],
      "sessions_more": 0,
      "status_category": "in_progress",
      "status_name": "In Progress",
      "task_id": "item:3",
      "title": "Nobody on it yet",
      "tracker_id": 1,
      "tracker_name": "Jira (acme)",
      "tracker_state": "unconfigured",
      "unavailable": false
    }
  ],
  "total": 3,
  "trackers": [
    {
      "id": 1,
      "name": "Jira (acme)",
      "org_id": 1,
      "provider": "jira",
      "state": "unconfigured"
    }
  ]
}
""".trimIndent()

    val TASK = """
{
  "placement": {
    "group": "Security",
    "note": "why",
    "task_id": "item:2",
    "updated_at": 1790518155,
    "updated_by": "me",
    "version": 1
  },
  "task": {
    "counts": {
      "active": 1,
      "ended": 0,
      "suggested": 0
    },
    "group": {
      "editable": true,
      "id": "label:Security",
      "label": "Security",
      "source": "manual",
      "tracker_value": "TP"
    },
    "item_id": 2,
    "key": "TK-2",
    "kind": "tracker",
    "last_activity_at": 1790518155,
    "mine": true,
    "needs_you": false,
    "org_fenced": true,
    "org_id": 1,
    "org_mixed": false,
    "org_source": "tracker",
    "placement_version": 1,
    "provider": "jira",
    "repos": [
      "acme/api"
    ],
    "review": false,
    "sessions": [
      {
        "archived": false,
        "created_at": 1790518155,
        "cross_org": false,
        "decided_at": 1790518155,
        "host": "h1",
        "link_id": 2,
        "link_version": 1,
        "name": "one",
        "needs_you": false,
        "other_tasks": 1,
        "primary": false,
        "resumable": true,
        "session_id": 1,
        "source": "manual",
        "state": "active",
        "strength": "explicit",
        "why": "linked by a person"
      }
    ],
    "sessions_more": 0,
    "status_category": "in_progress",
    "status_name": "In Progress",
    "task_id": "item:2",
    "title": "Audit log",
    "tracker_id": 1,
    "tracker_name": "Jira (acme)",
    "tracker_state": "unconfigured",
    "unavailable": false
  }
}
""".trimIndent()

    val SESSION_TASKS = """
{
  "links": [
    {
      "archived": false,
      "created_at": 1790518155,
      "cross_org": false,
      "decided_at": 1790518155,
      "host": "h1",
      "link_id": 1,
      "link_version": 1,
      "name": "one",
      "needs_you": false,
      "other_tasks": 1,
      "primary": true,
      "resumable": true,
      "session_id": 1,
      "source": "manual",
      "state": "active",
      "strength": "explicit",
      "task": {
        "key": "TK-1",
        "kind": "tracker",
        "org_id": 1,
        "status_category": "in_progress",
        "status_name": "In Progress",
        "task_id": "item:1",
        "title": "Login fails",
        "tracker_name": "Jira (acme)",
        "unavailable": false
      },
      "why": "linked by a person"
    },
    {
      "archived": false,
      "created_at": 1790518155,
      "cross_org": false,
      "decided_at": 1790518155,
      "host": "h1",
      "link_id": 2,
      "link_version": 1,
      "name": "one",
      "needs_you": false,
      "other_tasks": 1,
      "primary": false,
      "resumable": true,
      "session_id": 1,
      "source": "manual",
      "state": "active",
      "strength": "explicit",
      "task": {
        "key": "TK-2",
        "kind": "tracker",
        "org_id": 1,
        "status_category": "in_progress",
        "status_name": "In Progress",
        "task_id": "item:2",
        "title": "Audit log",
        "tracker_name": "Jira (acme)",
        "unavailable": false
      },
      "why": "linked by a person"
    }
  ],
  "org_id": 1,
  "primary_link_id": 1,
  "session_id": 1
}
""".trimIndent()

    val REVIEW = """
{
  "items": [
    {
      "created_at": 1790518155,
      "host": "h1",
      "kind": "suggestion",
      "link_id": 3,
      "link_version": 1,
      "preselected": false,
      "review_id": "link:3",
      "rule": "R6",
      "session_id": 2,
      "session_name": "two",
      "strength": "weak",
      "task": {
        "key": "TK-3",
        "kind": "tracker",
        "org_id": 1,
        "status_category": "in_progress",
        "status_name": "In Progress",
        "task_id": "item:3",
        "title": "Nobody on it yet",
        "tracker_name": "Jira (acme)",
        "unavailable": false
      },
      "why": [
        "mentioned in a prompt TK-3 \u00b7 R6"
      ]
    }
  ],
  "total": 1
}
""".trimIndent()

    val ORG_IMPACT = """
{
  "allowed": true,
  "bound_clients_gaining": 0,
  "bound_clients_losing": 0,
  "hosts_gaining": [],
  "hosts_losing": [
    "h1"
  ],
  "impact_token": "4597236def2aa34e7ec49568",
  "journal_entries": 0,
  "links": [
    {
      "becomes_cross_org": true,
      "host": "h1",
      "link_id": 4,
      "name": "two",
      "session_id": 2,
      "session_org": 1,
      "state": "active"
    }
  ],
  "summaries": 0,
  "task_id": "item:4",
  "to_org": 2
}
""".trimIndent()

}
