# fleet-mobile — operator workflow and parity review

**Baseline (from `HubClient.kt`):** the phone calls exactly six tools — `list_sessions`, `list_hosts`, `list_projects`, `session_conversation`, `send_prompt`, plus `POST /pair` — and follows `GET /events`. `SessionRow` already deserialises `context_pct`, `pr_url`, `ci_status`, `tags`, `turn_seq`, `is_controller`, but `SessionsScreen.kt`/`SessionScreen.kt` draw only `claude_status`, `stuck_kind` and `current_activity`. The app is a read-and-reply pager. Everything below is judged against `CLIENT_TOOLS` (`guard.rs`, `Access::Client`), which grants a `full` client far more than the phone uses: `new_session`, `new_bg_session`, `safe_kill_session`, `restart_session`, `recreate_session`, `move_session`, `dispatch_task`, `list_tasks`, `wait_for_task`, `inbox`, `send_message`, `repo_*`, `session_history`, `session_conversations`, `related_sessions`, `set_session_tags`, `set_friendly_name`, `rename_session`, `fleet_health`, `usage_report`, `spawn_review`, `operator_status`, `ensure_operator`, `broadcast_prompt`, `run_prompt`. Only fleet admin (`add_host`, `provision_hosts`, `pair_client`, `set_client_trust`, `apply_sync`, `set_secret`, `set_host_layers`) is out of reach.

## 1. Capability matrix

| Operator job | Desktop | Hub tool for a `full` client | Phone today | Proposed phone UX |
|---|---|---|---|---|
| Triage what needs me | `Attention.svelte`, `SidebarFilters` | `list_sessions {claude_status, tag}`, `peer_status`, `/events` | Toggle "Needs attention (N)" — blocked/stuck only | Attention **inbox** tab: blocked, stuck, failed, CI red, task done, unread inbox, context >85%, sorted by age |
| Answer a blocked agent | `PromptComposer` | `send_prompt`, `capture_session` | Free-text prompt box | Quick-reply chips (`y`, `n`, `1`/`2`, Enter) derived from `stuck_kind`/`current_activity`; `send_prompt {submit}` |
| Read the last result | `ConversationPanel`, `Timeline` | `session_conversation`, `session_transcript`, `session_history` | Turns list (fails with `E_NO_TRANSCRIPT` on idle bg rows, see 05 shot) | Fallback to `capture_session` on `E_NO_TRANSCRIPT`; "Last reply" card pinned above the composer |
| Review what it changed | `FilesPanel`, `DiffView`, `CommitGraph`, `BranchList` | `repo_changes`, `repo_diff`, `repo_log`, `repo_commit_diff`, `repo_branches` | None ("No file browsing or diffs") | Changes sheet: file list with +/-, tap → diff, commits with ahead/behind |
| Approve / merge / hand off | PR link, `spawn_review`, `ReviewDialog` | `spawn_review`, `send_prompt`, `set_session_tags` | None (`pr_url` deserialised, not drawn) | PR chip → opens browser; "Ask for review" → `spawn_review`; "Hand to bg" → `dispatch_task` |
| Spawn a session with a prompt | `NewSessionDialog`, `NewBgSessionDialog` | `new_session`, `new_bg_session`, `new_worktree` | None | FAB → host picker, project picker, prompt, "in a fresh worktree" switch |
| Kill / restart safely | `SessionDetails` actions, `ConfirmDialog` | `safe_kill_session`, `kill_session`, `restart_session`, `recreate_session` | None (the `...` in 07 does nothing visible) | Overflow menu with tiered destructiveness; `safe_kill_state` progress via `session:updated` |
| Watch cost and context | `UsageBar`, `UsageBlock`, `UsageRow`, `HostDetail` | `fleet_health`, `usage_report`, `context_pct` on the row | None (`context_pct` deserialised, not drawn) | Context ring on each row; Hosts tab gains $/day per host from `fleet_health` |
| Dispatch a bg task, collect result | `TasksPanel`, `BackgroundDetail` | `dispatch_task`, `wait_for_task`, `list_tasks`, `cancel_task`, `task:updated` event | None; bg rows show as raw `bg:<uuid>` (04 shot) | Tasks tab; bg rows named from `last_prompt`/friendly name; result paragraph inline |
| Message an agent | `AgentPanel` | `send_message {deliver}`, `inbox` | None | Long-press session → "Message"; `inbox` badge on attention tab |
| Tag / rename | `SessionRowItem`, `AccountNickname` | `set_session_tags`, `set_friendly_name`, `rename_session` | None (tags deserialised, not drawn) | Row header tap → rename sheet; tag chips filter the list |
| See CI | `SessionRowItem` CI dot | `ci_status` on full rows | None (deserialised, not drawn) | Green/red/grey dot beside status chip; red joins the attention inbox |
| Move to another host | `TransferSheet`, `TransferChip` | `move_session`, `resolve_move`, `move:progress` | None | Defer: needs master-level dual-host token in practice |
| Operator agent | `AgentFab`, `AgentPanel` | `operator_status`, `ensure_operator`, `run_prompt` | None | "Ask the fleet" box → `run_prompt` on the operator session |

Note: the mobile allow-list is pinned by `ToolsTheAppMayCallTest`; every proposal below widens it deliberately and by name.

## 2. The five biggest gaps

### Gap 1 — Session actions: kill / restart / recreate / rename / tag (effort **S**)
The operator's most common phone impulse after reading a stuck session is "restart it" or "get rid of it". Today the only lever is typing a prompt.

```
┌ ano · claude-fleet-oci        blocked  ⋮ ┐
│  ⋮ ───────────────────────────────────  │
│   Rename / friendly name                │  set_friendly_name
│   Tags: review  wip  [+]                │  set_session_tags
│   Restart REPL (in place)               │  restart_session
│   Recreate (frozen / OOM / ghost)       │  recreate_session
│   Retire safely (commit+push, then rm)  │  safe_kill_session
│   Kill now  ⚠ unpushed work is lost     │  kill_session   [hold to confirm]
└─────────────────────────────────────────┘
```
Calls: as listed; the `session:updated` stream already animates `safe_kill_state` requested→ready. Controller rows (`is_controller`) hide kill/recreate. Hard-refuse `kill_session` on `kind=external` client-side (the hub returns `E_INVALID_STATE` anyway).

### Gap 2 — Review what it changed + PR/CI (effort **M**)
The phone shows a session saying "unit suite green: 1293 passed" and gives no way to check. `pr_url`, `ci_status` are already on the row.

```
┌ Changes · feat/api-tenant                          ┐
│ PR #212  ● CI failing   [Open PR]                  │  pr_url, ci_status
│ main…feat  ↑3 ↓0                                   │  repo_branches
│ M  src/tenant/resolve.ts        +41 −7   ›         │  repo_changes
│ A  src/tenant/resolve.test.ts   +88      ›         │
│ ── commits ──────────────────────────────          │  repo_log {limit:10}
│ a1b2c3  feat(tenant): resolve by header     2h     │
└────────────────────────────────────────────────────┘
tap a file → repo_diff {session_id, path}  (unified, monospace, +/- tinted, truncated flag shown)
tap a commit → repo_commit + repo_commit_diff
```
Add CI dot to every row and put `ci_status=failing` into the attention filter. Read-only tools, so the `readonly` client gets this too.

### Gap 3 — Spawn a session from the phone (effort **M**)
"I had an idea on the tram" is the phone-native use case and it is impossible today.

```
┌ New session                                    ┐
│ Host      [claude-fleet-htz ▾]  (reachable only)│  list_hosts
│ Project   [martin-janci/claude-fleet ▾]        │  list_projects
│ ( ) In the main checkout                       │
│ (•) Fresh worktree from  [main ▾]              │  new_session {new_worktree, base_branch}
│ ( ) Background (headless), no tmux             │  new_bg_session {host_alias, prompt}
│ Prompt  ┌──────────────────────────────┐ 🎤   │
│         │ Fix the tenant header parse… │       │
│ [Start]                                        │
└────────────────────────────────────────────────┘
```
Compose: `new_session` (friendly_name from first line of the prompt) then `send_prompt` once `claude_status` leaves `null`/`working` on the first `session:updated`; or a single `new_bg_session`. The returned row is merged optimistically as the desktop does.

### Gap 4 — Tasks and background work as first-class (effort **M**)
Nine `bg:<uuid>` rows in shot 04 are unreadable. The hub knows their prompt, their requester and — for dispatched tasks — a one-paragraph `result`.

```
┌ Tasks                                    [+ Dispatch] ┐
│ ● running  "Bump deps and run CI"   mefistos    12m   │  list_tasks
│ ✓ done     "Write the ADR for moves" mac        1h    │
│   ↳ Result: Wrote docs/adr/0003…, 2 open questions ▾  │  task.result
│ ✗ failed   "Migrate hooks"           oci        3h    │
└───────────────────────────────────────────────────────┘
[+ Dispatch] → prompt + host/project → dispatch_task {new_worker:{host_alias, project_id}, prompt}
```
`task:updated` is already on `/events`; subscribe with `?kinds=session,host,task`. Label bg rows by `friendly_name` (the hub sets it to the prompt) and hide the uuid. Cancel via `cancel_task`.

### Gap 5 — Cost and context at a glance (effort **S**)
A context ring on each row (`context_pct` is already parsed) and a $/day figure per host from `fleet_health.usage` (micro-USD → "$4.10 today"). Sessions above 85 % context join the attention inbox with the action "Recreate to reclaim context". One extra call (`fleet_health`) on each `ready` resync, no new stream.

## 3. Hub-side changes to plan

1. **`needs_attention` reason on the session row** (`why: blocked|stuck|ci_failing|context_high|task_done|inbox_unread|failed`) — every client re-derives it today; the phone, the desktop `Attention.svelte` and a push relay would all agree on one field.
2. **Push relay** (`POST /push/register {platform, token}` + a hub-side FCM/APNs sender fired on the same predicate) — the README lists "no push while closed" as the app's biggest gap and it is unbuildable client-side.
3. **`message:created` event and an `inbox` roll-up in `fleet_health`** — `send_message` lands in a store row but no `/events` frame announces it, so a phone must poll `inbox` per session.
4. **`session_conversation` should fall back to the pane** (or return `E_NO_TRANSCRIPT` with `pane_tail`) — shot 05 shows an idle session rendering as an error banner; the desktop calls `capture_session` instead.
5. **A `client` token allowed on `move_session` for a named target** — the tool is in `CLIENT_TOOLS` but the description says "in practice the master"; either honour the phone's token across both hosts or drop the row and stop advertising it.
6. **`list_sessions {needs_attention:true}` filter + `usage_micro_usd_today` on the slim row** — one call for the attention tab, no `summary=false` fan-out.
7. **Suggested replies from `pane_intel`** (`options: ["Yes","No, and tell Claude what to do"]` parsed from the permission prompt) — quick-reply chips need the hub, which already sees the pane, to name the choices; the phone should not regex the menu.

## 4. A phone-first flow: "Morning digest, then unblock in one thumb"

Desktop has no notion of "what happened while I slept". The phone should open on it.

```
┌ Overnight · since 23:10                         ┐
│ 3 finished  ·  2 need you  ·  1 CI red  · $11.40│  fleet_health, list_tasks, list_sessions
│ ─────────────────────────────────────────────── │
│ ▸ ano · sales-twins-app        waiting: Recreate turanga?  [No] [Yes]   │
│ ▸ bg "ADR for moves" · mac     done — "Wrote docs/adr/0003…"  [Open]    │
│ ▸ feat/api-tenant · htz        PR #212 CI failing            [Review]   │
│ ▸ dispatchers · htz            idle since 02:14, 91% context [Recreate] │
└──────────────────────────────────────────────────┘
```
Each card is a session with one primary action already bound to a hub call (`send_prompt {prompt:"y"}`, `recreate_session`, the Changes sheet). Add a mic on the composer: OS speech-to-text → `send_prompt {submit:false}` so the operator reads the transcription in the staged REPL line before a second tap submits. Digest boundary = `last_activity_at` after the phone's last foreground, computed locally; no hub change needed for v1, item 1 above makes it exact.

## 5. Three "10x" ideas

1. **Approve-by-notification.** Push (item 2) carrying `stuck_kind=press_enter|trust_prompt` with the hub-parsed options (item 7) as notification action buttons; answering never opens the app. `send_prompt` from the notification service, `wait_for_session {until:turn_gt}` to confirm.
2. **"Ask the fleet" — a chat with the operator agent.** One box on the top of the Sessions tab: `ensure_operator` once, then `run_prompt {session_id: operator, prompt, timeout_s: 120}`. "Which sessions touched the tenant resolver this week?" or "kill everything idle on mefistos" become a sentence; the phone renders the transcript. The desktop has `AgentPanel` but on a phone this is the whole UI.
3. **Broadcast and fan-in.** Long-press a project group → `broadcast_prompt {project_id, prompt}` ("rebase on main and report"), then a fan-in card that watches `turn_seq` per session and lists each `session_transcript {since_turn}` reply as it lands. Ten sessions steered from a bus stop; the desktop's `BulkPromptDialog` sends but never collects.
