# fleet-mobile UX review — 02: The Session screen

Scope: `SessionScreen.kt` / `SessionViewModel.kt` / `model/Conversation.kt`, screenshots 05, 06, 06b, 07 (01 for context). Hub tools cited from `docs/control-api-reference.md`.

## 1. The operator's jobs, and how the screen serves them

| Job | Today | Evidence |
|---|---|---|
| **Read what happened** | Partly. Prose is legible, the user bubble is distinct, tool calls are one grey monospace line. But markdown is raw (`**1293 passed**`, `*is*` in 07), every Bash line truncates at the repo path so all three look identical, and `subagent`/`compact`/`command`/`interrupt` items render as "(unsupported item)" (`ConvItemSerializer` maps anything but text/tool to `Unsupported`). | 07 |
| **See why it is blocked** | Fails. 07 shows `blocked` in the bar and the list row (01) even says "waiting for input: ☐ Recreate turanga?", yet the session body shows the last assistant prose and stops. The question the agent is asking is never on this screen. | 01 vs 07 |
| **Unblock with one tap** | Fails. The only affordance is a free-text field; to answer a ☐ checklist the operator must know to type "1" or press Enter, and `send_prompt` always appends Enter, so a bare Enter cannot be sent at all (blank drafts are refused by `canSend`). | 07, 06b |
| **Send a steer** | Works, minimally. `send_prompt` fires, draft clears, a refetch follows. No history, no templates, no voice, and the field is the OS-default outlined box with a disabled `Send` that gives no hint why (offline? read-only? blank?). | 05, 06b |
| **Check the result** | Weak. The VM refetches on `session:*` events (debounced 500 ms) so a reply does appear, but there is no "working… 2m14s" indication, no jump-to-latest when scrolled up, and the growing bottom turn has no live marker. | 07 |
| **Stop a runaway** | Absent. No kill, safe-kill, restart, rename, or tags anywhere on the screen. The `…` glyph at top right of 07 is decorative overflow of the chip row, not a menu. | 07 |

Empty/error state (05/06/06b): `E_NO_TRANSCRIPT` from a session that has not written a JSONL yet is shown as a raw hub error with a UUID, over a blank body, with Refresh and a live Send. Three screenshots of the same state is itself a finding — nothing on the screen changes when you scroll or focus.

## 2. Top 10 findings, by impact

1. **The blocked prompt is invisible.** `claude_status=blocked` and `current_activity="waiting for input: ☐ Recreate turanga?"` exist on the row the screen already holds (`SessionRow.currentActivity`) but are never rendered here. The pane's actual menu needs `capture_session`. This is the single reason to open the phone app and it does not work.
2. **No one-tap answers.** A blocked session wants `y`, `1`, `2`, Enter, or Esc. Only literal-text+Enter exists; Enter alone is impossible.
3. **No stop/restart.** A runaway agent cannot be stopped from the phone. `safe_kill_session`, `kill_session`, `restart_session` all exist on the hub.
4. **Tool calls are unreadable and unexpandable.** The hub already sends `name`, `target`, `done`, `error`, `at`, `ended_at` per tool item (`ConvItem::Tool` in `transcript.rs`); the app parses only `summary`/`error` and prints `Bash(command=cd /home/dev/projects/…` three times. Tool input/result detail is desktop-only (`session_tool_detail` is a Tauri IPC command, not an MCP tool).
5. **Raw markdown.** Bold, italics, headings, lists, fenced code, and tables render as source. Agents write markdown constantly.
6. **The "Older turns are not shown" cliff.** A label with no action. `session_conversation` takes `turns` up to 100 and `session_conversations` lists earlier conversations; the app hard-codes `turns = null` (hub default 10).
7. **Error state is a stack trace.** `E_NO_TRANSCRIPT` means "this session has not spoken yet"; instead the user sees a code, a UUID, and an empty canvas. There is no distinction between "nothing yet", "hub unreachable", and "session gone".
8. **Context and cost are dropped on the floor.** `session_conversation` returns `context {tokens, window, pct, stale}`; the row carries `context_pct`; `usage_report` has cost per session. The app deserialises none of it.
9. **No live-progress affordance.** `working` is a chip in the bar only. No elapsed time, no "typing" marker on the growing turn, no jump-to-latest pill when scrolled up.
10. **No view of what the agent changed.** `repo_changes` / `repo_diff` exist; the screen has no entry point, and the `pr_url`/`ci_status` on the row are unused.

Honourable mentions: the composer's `Send` is disabled with no reason text; `subagent`/`compact` items become "(unsupported item)" instead of a one-line "Compacted (312k → 40k)" or "Agent: Explore — done"; turns keyed by index means a disjoint refetch can re-animate the whole list.

## 3. Redesign proposals

### 3.1 Turn rendering (S)
Prompt bubble stays. Assistant text through a markdown renderer (multiplatform-markdown-renderer or a minimal inline parser for bold/italic/code/lists/fences). Fenced code in a horizontally scrollable monospace block with copy. Headings demoted to bold. Uses: `session_conversation` `items[].text`.

### 3.2 Collapsible tool calls with results (M; hub change for results)
```
▸ Bash  pnpm test                              2.3s ✓
▸ Edit  src/api/tenant.ts                          ✓
▾ Bash  cd …/sales-twins-app && pnpm vitest run    ✗ 4.1s
   Input   cd /home/dev/projects/github.com/…/sales-twins-app && pnpm vitest run
   Result  FAIL src/accept-invite.test.ts (2 tests)
           ● renders the invite form
           …  [show more]
```
Collapsed row = `name` + `target` (already sent, unused) + duration from `at`/`ended_at` + `done`/`error` glyph. Expanding needs a hub tool: expose the existing desktop `session_tool_detail` (`ToolDetail {input, edit{old,new}, command, result, is_error}`) as an MCP tool, readonly-allowed. Group ≥3 consecutive tools into "6 tool calls (1 failed) ▸". Render `Subagent` as a chevron row with `agent_type`/`description`/`result`, `Compact` as a divider "Compacted (pre 180k)". Dependency: hub tool.

### 3.3 "Blocked on" card (M)
Pinned above the composer whenever `claude_status == blocked` or `stuck_kind != null`:
```
┌ Waiting for you ─────────────────────────────┐
│ ☐ Recreate turanga?                          │
│   ❯ 1. Yes                                   │
│     2. Yes, and don't ask again              │
│     3. No                                    │
│  [ 1 · Yes ]  [ 2 ]  [ 3 · No ]   [ Enter ]  [ Esc ] │
│  ⌄ show terminal                             │
└──────────────────────────────────────────────┘
```
Data: `capture_session {max_lines: 40}` on entering blocked; parse the last numbered/`❯` menu or `(y/n)` line; fallback chips are always `Enter`, `Esc`, `y`, `n`. Headline from `current_activity`. `stuck_kind` maps to fixed cards: `press_enter` → one big Enter; `trust_prompt` → Yes/No; `auth_menu`/`reconnect`/`oom` → explain + Restart. Answer via `send_prompt {prompt: "1"}`; Enter needs `send_prompt {prompt: "", submit: true}` — verify the hub accepts empty text, otherwise add a `keys` parameter (`Enter`, `Escape`, `C-c`). After answering, `wait_for_session {until: "turn_gt", turn: turn_seq_before, timeout_s: 30}` and collapse the card. Dependencies: hub accepting empty/special-key sends.

### 3.4 Quick-reply chips (S)
Row above the composer, scrollable: `go on` · `yes` · `run the tests` · `commit and push` · `/clear` · `/compact` · custom (long-press to edit; stored locally). Tap = `send_prompt` immediately, no confirmation. Hidden while blocked (the card owns the space).

### 3.5 Composer (S/M)
Single-line pill that grows to 6 lines; send icon inside; disabled state shows *why* under it ("hub offline", "read-only device", "session gone"). Up-swipe on the field opens draft history (last 20 sent prompts, local). Mic button → platform speech-to-text into the draft (M: Android `SpeechRecognizer`, iOS `SFSpeechRecognizer` via expect/actual). A prompt is text; `send_prompt` unchanged.

### 3.6 Live status strip (S)
Replaces the bare chip:
```
● working 2m14s · ctx 62% · $1.84 · sonnet
```
Elapsed from `last_turn_at`/`started_at` on the row; ctx from `session_conversation.context.pct` (dim when `stale`) or row `context_pct`; cost from `usage_report` (call once per screen open, cache 60 s); model from `session_conversations[current].model`. Turns orange at ctx ≥ 80% with a `/compact` chip. When idle: "idle since 14:02".

### 3.7 Pull-to-load older turns (S) + jump-to-latest (S)
Replace the label with a top item "Load 20 earlier turns" and pull-to-load calling `session_conversation {turns: shown+20}` (max 100); `appending`'s overlap logic already merges. Beyond 100, a footer "Earlier conversation (started 09:12, /clear)" from `session_conversations` opens that `claude_session_id` read-only. When `atBottom` is false and a refetch grows the tail, show a "↓ New reply" pill.

### 3.8 "What changed" sheet (M)
Overflow → *Changes*: `repo_changes` list (path, status), tap → `repo_diff {path}` in a unified-diff view with +/- colouring, `truncated` note. Header shows `pr_url` (opens browser) and `ci_status` dot. Dependency: none; both tools exist and are read-only.

### 3.9 Overflow menu (S)
`…` in the bar: *Changes*, *Rename* (`set_friendly_name`), *Tags* (`set_session_tags`), *Restart* (`restart_session`, confirm), *Safe kill* (`safe_kill_session`; then show `safe_kill_state` from row events as a progress chip: requested → ready → deleted), *Kill now* (`kill_session`; red, confirm sheet; handle `E_CONFIRM_REQUIRED` by explaining desktop confirmation is on). Hidden for `readOnly`.

### 3.10 States (S)
`E_NO_TRANSCRIPT` → centred "Nothing said yet. Send the first prompt." with the composer focused. Session gone (`session == null`) → "This session was killed — Back". Offline → keep last snapshot, grey composer, banner as today. Parse the error code, never show the UUID.

## 4. Effort and dependencies

| # | Proposal | Effort | Depends on |
|---|---|---|---|
| 3.1 | Markdown turns | S | markdown lib |
| 3.2 | Collapsible tools + results | M | hub: `session_tool_detail` as MCP tool |
| 3.3 | Blocked-on card | M | hub: empty/special-key `send_prompt`; pane parser |
| 3.4 | Quick replies | S | — |
| 3.5 | Composer + history + voice | S (M with voice) | platform STT |
| 3.6 | Status strip | S | parse `context` (already sent) |
| 3.7 | Older turns + jump pill | S | — |
| 3.8 | Changes sheet | M | — |
| 3.9 | Overflow menu | S | — |
| 3.10 | States | S | — |

Order: 3.3 → 3.9 → 3.6 → 3.1 → 3.4 → 3.7 → 3.2 → 3.8 → 3.5. The first three turn the screen from a viewer into a control.

## 5. Three 10x ideas

1. **Answer from the notification.** A `blocked` event (SSE `session:updated` with `claude_status` change) posts a notification whose actions are the parsed menu options; tapping "1 · Yes" calls `send_prompt` without opening the app. Most unblocks become zero-screen.
2. **Reply-to-question mode.** When the last assistant text ends with a question mark and status is idle, the composer pre-fills nothing but the chips become the agent's own offered options (extracted from numbered lists in the last `text` item). The agent proposes, the operator taps.
3. **Turn digest with "since you looked".** Store the `turn_seq` last seen per session; on open, `session_transcript {since_turn}` yields exactly the new turns, and the screen opens on a one-card digest ("3 turns, 14 tool calls, 1 failed, ctx 62%, tests green") with a "read all" expander. Ten sessions triaged in a minute.
