# fleet-mobile — Sessions list & triage review

Scope: the Sessions tab of fleet-mobile 0.1.0 on a Samsung phone against `https://fleet.rlt.sk` (44 sessions on 5 hosts, 1 blocked). Files cited are under `fleet-mobile/shared/src/commonMain/kotlin/dev/claudefleet/mobile/` unless noted; hub facts come from `claude-fleet/docs/control-api-reference.md` (CAR) and `crates/fleet-core/src/store/rows.rs`.

## 1. The operator's jobs on this screen

1. **"Does anything need me right now?"**. Today the answer is a small "Needs attention (1)" label in the top bar (01) and the one blocked row sits at the *bottom* of the first screen, under six `unknown` rows, because sorting is host → project → recency (`SessionsViewModel.kt:195-228`). The filter is a 48dp switch whose label is not tappable (`SessionsScreen.kt:99-103`); screenshot 04, meant to show it on, shows it still off and the full list — the tap missed. Fails.
2. **"What does it need, and can I answer from here?"** — the blocked row does say `waiting for input: ☐ Recreate turanga?` (01), which is the best line on the screen. But opening it (07) shows a transcript ending in a `Bash(...)` call, "Older turns are not shown", and a free-text Prompt box; the question and its options are gone and there is no yes/no. Half-served.
3. **"Is the fleet healthy overall?"** — no totals, no cost, no "3 working / 30 idle / 2 stopped", no host reachability in the list header beyond a count (01–03). Hosts tab (08) shows `local — unreachable — not probed yet` with 6 hidden sessions, which reads as an outage. Fails.
4. **"Find the session I was thinking about"** — 44 rows, mefistos alone is 20 (03), no search, no sort, no collapse. Twelve rows on `mac` are named `bg:44366faf-…` (03), which nobody can recognise. Fails.
5. **"Prune / steer without opening each one"** — kill idle leftovers, tag, nudge. No row actions at all; the only verb is tap-to-open then type. Fails.

## 2. Top 10 findings (by impact)

1. **The attention signal is hidden in the ordering.** One blocked session out of 44 is the whole point of the app, and it renders as row 7 with the same visual weight as `idle`. The chip (`StatusChip.kt:33-42`) is `errorContainer` (pale pink) — same tint as the `E_NO_TRANSCRIPT` banner (05), so red means "error" and "needs you" at once. Evidence: 01 bottom, 03.
2. **`current_activity` is REPL chrome, not activity.** 11 of 12 rows on 02 show `⏵⏵ bypass permissions on (shift+tab to cycle) · ← 3 agents`; 01 shows `❯ 0;16;27M0;16;27m` (SGR mouse-report residue) and bare `❯`. The hub classifies that footer as *idle* (`pane_intel.rs:596-605`) and still records it as the activity line; the phone prints it verbatim on purpose (`SessionsScreen.kt:163-174` "does not second-guess it"). The second line is 40% of the row and carries nothing.
3. **`unknown` is the most common chip on the first screen** (5 of 7 rows, 01; 03 `coral bootes`). `claude_status == null` is drawn as a word (`StatusChip.kt:35`) with no hint whether it means "hub hasn't looked yet", "pane blank", or "shell session". 
4. **Recency is computed and never shown.** `lastActivityAt` sorts rows (`SessionsViewModel.kt:231`) but no row says "3 min ago" or "idle 2 d". A blocked session that has been waiting 6 h looks the same as one that asked 10 s ago. Evidence: every row in 01–03.
5. **Needs-attention is too narrow.** `needsAttention = blocked || stuckKind != null` (`SessionRow.kt:51`). `failed`, `ci_status = failing`, ghosts (`lost_at`), a host that went unreachable, and `unknown` for more than a few minutes are all excluded, so the count reads "(1)" while `local` has 6 unprobed sessions (08).
6. **Background sessions are unnamed.** `bg:<uuid>` rows (03, 10 of them) truncate before anything meaningful. The hub says the prompt becomes the default friendly name (CAR `new_bg_session`), yet `displayName` falls through to `tmuxName` (`SessionRow.kt:48`); `last_prompt` is on the wire (rows.rs:179) and the phone drops it.
7. **No search, sort or collapse for 44 rows.** Reaching mefistos' 20 sessions is three screens of thumb travel (03). Host headers are static `Surface`s (`SessionsScreen.kt:113-137`), not sticky, not tappable.
8. **No actions on the row.** Kill, safe-kill, restart, tag, nudge all exist on the hub (CAR `kill_session`, `safe_kill_session`, `restart_session`, `set_session_tags`, `send_prompt`) and the app has a `full` token (09). Each costs open → scroll → type.
9. **The detail view fails the one job the list promised.** 05: an `idle` row opens to an empty pane and `E_NO_TRANSCRIPT` (a shell/non-Claude session — `kind` is on the row, `SessionRow.kt:28`, and could have said so). 07: blocked row opens to a transcript that never shows the pending question.
10. **Chrome details that cost trust.** Tab bar letters S/H/S — two tabs share "S" (all shots). "Refresh" in the bar on a screen fed by SSE. Host header says "6 sessions" but the fleet total is nowhere. `local` shown to a phone as `unreachable` (08).

## 3. Redesign proposals

### P1 — Attention inbox pinned above the list

```
┌ NEEDS YOU (3) ───────────────────────────────┐
│ ● ano · sales-twins-app · oci        6 min   │
│   Recreate turanga?        [ Yes ] [ No ] [↗]│
│ ● Main · kuk-agent · mefistos  press_enter   │
│   [ Press Enter ]  [ Open ]                  │
│ ● local (host) unreachable · 6 sessions      │
└──────────────────────────────────────────────┘
```
Rows: `friendly_name`, `host_alias`, project label, `current_activity` with the `waiting for input:` prefix stripped, age from `last_activity_at`, `stuck_kind`. Yes/No = `send_prompt { prompt: "1" }` / `"2"` — hub has the tool, but the *options* are not on the row: the hub parses the dialog (`pane_intel.rs` `Dialog.prompt`) and discards the choices. **Hub change**: expose `dialog_options: [{key, label}]` on the row. `press_enter` → `send_prompt { prompt: "", submit: true }` (exists). Membership: `blocked || stuck || failed || ci_status=failing || lost_at || host.reachable=false || (unknown && age>5 min)`. Replaces the switch entirely.

### P2 — Row anatomy

```
● fix wrapped alcohol report PDF        idle · 2 h
  papayapos-backend · mefistos          ctx 41% · $3.20
  ⎇ PR #212 ✓ passing                   [review] [wip]
```
Line 1: 8dp status dot (colour) + name + status word + relative age (`last_activity_at`, `last_stop_at`). Line 2: project · host (so the row survives flat/search views) + `context_pct` + `usage_cost_micros/1e6`. Line 3, only when present: `pr_url` + `ci_status`, `tags`. Second line replaces raw `current_activity` for `idle`/`unknown`; keep it only for `working` (spinner text) and `blocked` (question). All fields are already on the full row (`list_sessions summary=false`, rows.rs 124–204, 316–325) — `SessionRow.kt` needs `last_prompt`, `last_stop_at`, `usage_cost_micros`, `usage_model`, `parent_session_id`, `branch` added; the app's `ignoreUnknownKeys` already lets them through. No hub change. For `bg:` rows, name = `friendly_name ?: last_prompt.take(60) ?: tmux_name`.

### P3 — Summary header + real filters

```
44 sessions · 5 hosts · ● 6 working  ○ 30 idle  ■ 5 stopped  ▲ 3 need you
$41.20 today · 1 host unreachable
[ Needs you ] [ Working ] [ Idle > 1 h ] [ Mine ] [ host ▾ ] [ project ▾ ]  ⌕
```
Counts are client-side over rows already held. "$ today" = `fleet_health.usage` or `usage_report { since_secs: 86400 }` (CAR, both exist; one extra call per resync). Chips are a single-select segment; `host ▾` and `project ▾` are bottom sheets. Sort toggle: *recency* (default, flat, no host headers) vs *by host* (today's grouping, with sticky, collapsible host headers showing `n working / m blocked`).

### P4 — Search

Search field revealed by pull-down on the list (`⌕` in header as fallback). Matches `friendly_name`, `tmux_name`, `last_prompt`, project label, `host_alias`, `tags`, `branch`. Client-side; the snapshot is in memory. No hub change.

### P5 — Swipe actions + long-press sheet

```
◀ swipe left:  [ Nudge ]  [ Kill ]        (Kill = safe_kill_session; long-press Kill = kill_session)
▶ swipe right: [ Tag ]    [ Open ]
long-press:    Rename · Tags · Restart · Recreate · Copy PR link · Related (n)
```
Nudge = `send_prompt { prompt: "go on" }` with a configurable phrase. Kill on a `working` row asks; on `idle`/`stopped` acts with a 5 s undo snackbar (the hub has no undo — the snackbar delays the call). `E_CONFIRM_REQUIRED` surfaces as the confirm sheet. Tools: `safe_kill_session`, `kill_session`, `restart_session`, `recreate_session`, `set_session_tags`, `set_friendly_name`, `related_sessions` — all exist (CAR). Readonly tokens hide the sheet, as the prompt box already does.

### P6 — Blocked detail that shows the question

Detail header for a blocked row pins the question and options (P1's `dialog_options`) above the transcript; for `kind = shell` show "shell session — no transcript" instead of `E_NO_TRANSCRIPT`.

### P7 — Chrome fixes

Icons in the tab bar; replace "Refresh" with pull-to-refresh plus a small "live · 2 s ago" indicator from `ConnectionStatus`; drop `local` from the phone's host list when it is the hub's own machine (or label it "hub").

## 4. Effort and dependencies

| Proposal | Effort | Depends on |
|---|---|---|
| P1 inbox (without option buttons) | M | P2 fields; hub change for `dialog_options` (S on hub: `Dialog` already holds the parsed lines) |
| P2 row anatomy | S | none — model fields + composable |
| P3 summary + filters + sort | M | P2; `usage_report` call |
| P4 search | S | P2 (`last_prompt`) |
| P5 swipe/long-press | M | `SessionActions.kt` needs kill/tag/restart wrappers; confirm-sheet for `E_CONFIRM_REQUIRED` |
| P6 blocked detail | S | P1's hub change |
| P7 chrome | S | none |

Ship order: P2 → P7 → P1 (without buttons) → P4 → P3 → P5 → hub `dialog_options` → P1/P6 buttons.

## 5. Three 10x ideas

1. **Answer from the lock screen.** A `session:updated` that flips a row to `blocked`/stuck becomes a push notification whose body is the question, with the parsed options as notification actions (Android `RemoteInput`/action buttons, iOS notification categories). The operator never opens the app; the fleet just asks. Needs a hub-side push relay (device-token registration + FCM/APNs send on that one transition) — the SSE stream cannot wake a backgrounded phone.
2. **Talk to the fleet, not to sessions.** The hub already has an operator session (`ensure_operator`, `operator_status`) and `run_prompt`. Give the phone a single text field at the top: "what's blocked and why", "kill everything idle on mefistos over 2 h", "which PRs went red today". The operator Claude runs the same tools and answers in a paragraph; the list becomes optional. No new hub API — a trusted client's prompts are already delivered unmarked (`feat(hub): a paired client the operator trusts…`).
3. **"Since you last looked" digest.** The phone stores its last-foreground timestamp; on resume it shows a one-screen diff — 4 completed, 2 PRs green, 1 red, $12 spent, 3 asked you — assembled from `session_history`, `ci_status`, `usage_report { since_secs }`, all existing. A scan of 44 rows becomes a 5-second read that usually ends in "nothing changed, put it away".
