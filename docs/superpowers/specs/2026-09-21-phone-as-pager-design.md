# The phone as a pager: fleet-mobile UX overhaul, phases 0 and 1

**Date:** 2026-09-21
**Status:** Approved design; implementation plan next
**Scope:** `fleet-mobile` (shared Compose UI, both platforms) plus two small
hub-side changes in `claude-fleet`. Phases 2–4 are listed as the roadmap this
design leads to, not as part of this spec.

## Why

Five independent UX reviews of the 0.1.0 app on a phone paired to a real hub
(44 sessions, 5 hosts, 1 blocked) agreed on one diagnosis: **the app is a
window, not a control.** It calls six of the ~75 tools the hub grants a paired
`full` client. The one reason to open a phone — an agent is blocked and needs a
human — does not work: the blocked row sits seventh with the same weight as
`idle`, the session screen never shows the question the agent is asking, a
bare Enter cannot be sent, and the agent's numbered options are not buttons.
Fields the hub already sends (`context_pct`, `ci_status`, `pr_url`, `tags`,
`last_activity_at`, `kind`, `last_prompt`) are parsed and never drawn, while
the row's second line is raw pane text (`⏵⏵ bypass permissions on (shift+tab
to cycle)`, `❯ 0;16;27M0;16;27m`). The tab bar shows the letters S, H, S.
Errors are `E_*` codes with UUIDs. There is no dark theme.

The reviews are in `docs/superpowers/reviews/2026-09-21-ux/` (five reports and
a synthesis).

## Goal

Turn the window into a pager, in two steps that ship independently:

- **Phase 0 — the surface tells the truth.** Everything already on the wire
  is drawn, and nothing that is noise is. No hub change.
- **Phase 1 — the screen is a control.** A blocked agent is answered in one
  tap; a session can be steered, stopped and restarted from the phone; what
  needs the operator is pinned on top. Two small hub changes.

Three principles the reviews converged on and every decision below follows:

1. **Attention first.** What needs a person is on top, coloured (amber =
   answer a question, red = go fix the terminal) and counted in a badge.
2. **One tap to answer.** The agent's own options are buttons. Enter, Esc,
   quick replies, kill and restart are one gesture, not a typed prompt.
3. **Nothing invented on the phone.** The phone renders what the hub knows;
   when a rule exists on the hub (`needs attention`, dialog options, the
   token's write grant) the phone consumes it rather than re-deriving it.

## Non-goals (this spec)

- Notifications, widgets, deep links into sessions, a persisted snapshot
  (phase 3).
- The three-line row anatomy, filters, search, swipe actions, the Changes
  sheet, spawning a session, the Tasks tab (phase 2).
- iOS push, Live Activities, "Ask the fleet", digests (phase 4).
- A terminal emulator. `capture_session` is shown read-only, once, inside the
  blocked card.

## Phase 0 — the surface tells the truth

### 0.1 Activity line sanitised, with a fallback

`current_activity` is drawn only after `Activity.sanitize()`:

- strip ANSI escape sequences, SGR mouse-report residue (`[0-9;]+[Mm]` runs
  after an ESC or at line start), private-use glyphs `U+E000–U+F8FF`, and
  control characters;
- drop the line entirely when what remains is REPL chrome: contains
  `bypass permissions on`, `shift+tab to cycle`, `? for shortcuts`, `esc to
  interrupt`, or is a bare `❯`/`›` prompt;
- keep `waiting for input: …` / `waiting for permission: …` lines with the
  `waiting for <kind>: ` prefix removed for display (the kind is shown by the
  status token instead).

When nothing survives, the supporting line is `"$relativeTime · $kind"`
(`4 min · shell`, `2 h · background`). `relativeTime` is from
`last_activity_at`; `kind` from the row (`work` is shown as nothing).

Pure function, `commonTest`-covered with the exact strings from the
screenshots as fixtures.

### 0.2 Background sessions get a name

`SessionRow.displayName` becomes `friendly_name ?: last_prompt?.take(60)
?: "Background · ${tmux_name.removePrefix("bg:").take(4)}"` for `bg:` rows,
`friendly_name ?: tmux_name` otherwise. `last_prompt`, `last_stop_at`,
`usage_cost_micros`, `usage_model`, `parent_session_id`, `branch` are added to
`SessionRow` (all on the hub's full row; `ignoreUnknownKeys` already lets
unknown ones through, so an older hub simply yields `null`).

### 0.3 Status tokens, light and dark

A `FleetTheme` wraps `MaterialTheme(colorScheme = if (isSystemInDarkTheme())
darkColorScheme(...) else lightColorScheme(...))` and provides
`FleetStatusColors(dot, container, onContainer)` per state through a
`staticCompositionLocalOf`. States and meaning:

| State | Light dot | Dark dot | Meaning |
|---|---|---|---|
| working | `#2F6BFF` | `#7FA3FF` | pulsing dot |
| idle | `#8A8891` | `#8A8891` | quiet |
| blocked | `#E58A00` | `#FFB74D` | answer a question |
| stuck (any `stuck_kind`) | `#D32F2F` | `#FF8A80` | go fix the terminal; `Warning` icon |
| failed | `#D32F2F`, outlined | `#FF8A80`, outlined | `Close` icon |
| completed | `#1E8E3E` | `#7CD292` | `Check` icon |
| stopped | none, 1dp outline | same | |
| unknown | none, 1dp dotted outline, 70 % text | same | shown as `—`, never the word |

Stuck outranks status. Container/on-container values are in the visual
review; they are tokens, not literals in composables.

`StatusChip` gains a leading 14dp icon where the table says so, a
`contentDescription` of `"status: $text"`, and an `AnimatedContent` crossfade
on change.

### 0.4 Navigation and app bars

- `NavigationBar` with vector icons shipped as Compose Resources (chat
  bubbles, server rack, gear); the Sessions item carries a `BadgedBox` with
  the attention count. No `Text(name.take(1))`.
- `TopAppBar` with `titleLarge`, `pinnedScrollBehavior`.
- `PullToRefreshBox` on Sessions and Hosts; the text buttons "Refresh" and
  the busy `"…"` go. A small `live · 2 s ago` / `reconnecting` indicator from
  `ConnectionStatus` sits under the title.
- The "Needs attention" `Switch` becomes a `FilterChip` with a badge; in
  phase 1 it is replaced by the pinned inbox and the chip goes.

### 0.5 Errors and empty states

`HubError.friendly(): Friendly(title, body, isError)` maps before drawing:

- `E_NO_TRANSCRIPT` is not an error: the session body shows "Nothing has been
  said yet — send a prompt to start", composer focused, no banner. For
  `kind = shell` the body says "Shell session — no conversation to show".
- session gone (`session == null` after a `session:killed`): "This session was
  killed", with Back.
- unknown codes: title "The hub refused that", body = the hub's sentence, a
  "Details" expander with the raw code and message in monospace, copyable.
  UUIDs are never in the summary line.
- loading with an empty list: six shimmer `ListItem` skeletons; loading with
  a snapshot: a 2dp indeterminate bar under the app bar.

### 0.6 Row and turn polish (the parts of the visual system that are cheap)

- Row: `ListItem` with a 10dp status dot as leading content, `titleMedium`
  headline, the sanitised supporting line, and a trailing column of
  `StatusChip` over `labelSmall` relative time. A 2dp `LinearProgressIndicator`
  60dp wide under the headline when `context_pct != null`, `tertiary` past
  80 %. A 6dp `ci_status` dot after the time. Inset dividers.
- Host header as a `stickyHeader` (`surfaceContainer`, 40dp, count).
- Composer: pill `TextField` on `surfaceContainerHigh`, placeholder
  "Message <name>…", `ImeAction.Send`, a 48dp `FilledIconButton(Send)`.
  When disabled, one line under it says why: "hub offline", "read-only
  device", "session gone".

### 0.7 Connection truth

- `EventStream` parses `contract` from the `ready` frame; a hub outside
  `MIN_HUB_CONTRACT..=MAX_HUB_CONTRACT` (constants mirrored from the desktop's
  `contract.rs`, with a `jvmTest` that reads them from the sibling checkout
  when present) is refused with the desktop's sentence and no row events are
  applied.
- The banner distinguishes *no network* (platform connectivity, `expect`/
  `actual`), *hub unreachable (attempt N)*, and *stream down but hub answers*.
  In the last state Send stays enabled: `canSend` is gated on a `/mcp` probe
  (`fleet_health`), not on the SSE stream.

## Phase 1 — the screen is a control

### 1.1 The "Waiting for you" card

Pinned above the composer whenever `claude_status == blocked` or `stuck_kind
!= null`.

```
┌ Waiting for you ─────────────────────────────┐
│ Recreate turanga?                            │
│  [ 1 · Yes ]  [ 2 · Yes, don't ask ]  [ 3 · No ] │
│  [ Enter ]  [ Esc ]        ⌄ show terminal   │
└──────────────────────────────────────────────┘
```

- Headline: `pending_input.question` when the hub provides it (1.6), else
  `current_activity` with the `waiting for <kind>: ` prefix stripped.
- Option chips: `pending_input.options[]` when present; otherwise the card
  shows only `Enter`, `Esc`, `y`, `n` and the terminal expander.
- `stuck_kind` maps to fixed cards: `press_enter` → one large Enter;
  `trust_prompt` → Yes / No; `auth_menu`, `reconnect`, `oom` → a sentence
  ("needs a login on mefistos") plus Restart from 1.2; no chips that would
  type into a login menu.
- "show terminal" expands a read-only monospace `capture_session
  {max_lines: 40}` block, fetched on expand, refreshed on each
  `session:updated`.
- An option sends `send_prompt {prompt: "<n>"}`; Enter sends `send_prompt
  {prompt: ""}` (the hub treats an empty body as a bare Enter, and a trusted
  client's body is delivered unmarked — a non-trusted client cannot send Enter
  and the card says so); Esc and C-c send `send_prompt {keys: "Escape"}` /
  `{keys: "C-c"}` (1.6). After a send the card shows a spinner, calls
  `wait_for_session {until: "turn_gt", turn: turn_seq_before, timeout_s: 30}`
  and collapses when the status leaves `blocked`; on timeout it re-enables
  with "still waiting".

The card replaces the quick-reply row (1.4) while shown.

### 1.2 Session overflow menu

`⋮` in the session bar: **Rename** (`set_friendly_name`), **Tags**
(`set_session_tags`, chip editor), **Restart** (`restart_session`, confirm
sheet), **Retire safely** (`safe_kill_session`; then a progress chip driven by
`safe_kill_state` on `session:updated`: requested → ready → deleted),
**Kill now** (`kill_session`, red, hold-to-confirm 800 ms). Hidden entirely for
a read-only token; Kill/Restart hidden on `is_controller`; `kind = external`
rows get no Kill (the hub refuses with `E_INVALID_STATE`). `E_CONFIRM_REQUIRED`
is rendered as "The desktop asks for confirmation for this — approve it there".

### 1.3 Status strip

Replaces the bare chip in the session bar:

```
● working 2m14s · ctx 62 % · $1.84 · sonnet
```

Elapsed from `last_turn_at` / `started_at`; ctx from
`session_conversation.context.pct` (dim when `stale`) or the row's
`context_pct`; cost from `usage_report {session_id}` once per screen open,
cached 60 s; model from `session_conversations[current].model`. At ctx ≥ 80 %
the strip turns amber and offers a `/compact` chip. Idle reads
"idle since 14:02".

`model/Conversation.kt` stops dropping `context {tokens, window, pct, stale}`
and `events`; `subagent` renders as a chevron row (`agent_type`,
`description`, result on expand), `compact` as a divider "Compacted",
`command` and `interrupt` as one-line system rows. "(unsupported item)" is
gone.

### 1.4 Quick replies and the composer

- A scrollable chip row above the composer: `go on` · `yes` · `run the tests`
  · `commit and push` · `/clear` · `/compact` · `+` (custom, stored locally,
  long-press to edit). Tap sends immediately.
- Draft history: swipe up on the field shows the last 20 sent prompts (local).
- Voice: a mic button runs platform speech-to-text (`expect`/`actual`:
  Android `SpeechRecognizer`, iOS `SFSpeechRecognizer`) into the draft; the
  text is sent with `submit: false` so it lands in the REPL line, and a second
  tap submits with an empty body. Nothing is submitted by voice alone.

### 1.5 Transcript depth and liveness

- Turn text through a markdown renderer (multiplatform-markdown-renderer, or a
  minimal inline parser if the dependency proves heavy on iOS): bold, italic,
  inline code, lists, fenced code in a horizontally scrollable monospace block
  with copy. Headings demoted to bold.
- Tool rows draw `name` + `target` + duration (`at`/`ended_at`) + a `done`/
  `error` glyph; three or more consecutive tools collapse into "6 tool calls
  (1 failed) ▸". Expanding a single call to its input and result waits for
  phase 2 (`session_tool_detail` as an MCP tool).
- "Older turns are not shown" becomes a top item "Load 20 earlier turns"
  calling `session_conversation {turns: shown + 20}` up to 100, and beyond
  that a footer per earlier conversation from `session_conversations`.
- When scrolled up and the tail grows, an `AssistChip("New reply")` floats
  above the composer and scrolls to it. Transcript items use
  `Modifier.animateItem()`; the working dot pulses (gated by an
  `expect fun reduceMotion()`).

### 1.6 Hub contract for phase 1 (in `claude-fleet`)

Two additions, both additive, both `serde(default)` on the wire so the phone
tolerates an older hub and the desktop tolerates an older phone:

1. **`pending_input` on the session row** — derived from the pane-intel
   `Dialog` the hub already parses (question line and the numbered choices it
   currently discards):
   `{ kind: "permission" | "input", question: String?, options: [{ n: u8,
   label: String, selected: bool }] }`, `null` when there is no dialog.
   Reconciled with `current_activity`; carried on `session:updated`. The
   desktop's Conversation tab may use it too.
2. **`send_prompt { keys }`** — an optional `keys: "Escape" | "C-c" |
   "Enter"` sent instead of text (`tmux send-keys`), refused with
   `E_VALIDATE` for anything else; audited like a prompt but never recorded
   as one.

Both need `REGEN_DOCS=1` for the reference and `REGEN_HUB_CONTRACT=1` for the
desktop's golden, and the routing test row for the new arg.

### 1.7 Attention inbox

Pinned above the list, replacing the filter chip from 0.4:

```
┌ NEEDS YOU (3) ──────────────────────────────┐
│ ● ano · sales-twins-app · oci         6 min │
│   Recreate turanga?     [ 1 · Yes ] [ 3 · No ] [↗] │
│ ● Main · kuk-agent · mefistos   press Enter │
│   [ Enter ]                            [↗] │
│ ● local — unreachable · 6 sessions          │
└─────────────────────────────────────────────┘
```

Membership (client-side until the hub's `needs_attention` field lands in
phase 3): `blocked`, `stuck_kind != null`, `failed`, `ci_status == failing`,
`lost_at != null`, host `reachable == false`, `context_pct ≥ 85`, and
`claude_status == null` for longer than five minutes. Each row carries the
same chips as the card in 1.1, bound to the same calls, so most unblocks never
leave the list. The count feeds the tab badge. Collapsed to a one-line
"Nothing needs you" when empty.

## Architecture notes

- One rule, one place: `Activity.sanitize`, `SessionRow.displayName`,
  `needsAttention`, `HubError.friendly`, `FleetStatusColors.of(row)` are pure
  functions in `commonMain` with `commonTest` fixtures; composables only draw.
- View models keep `MutableStateFlow.update {}`; every new action goes through
  `SessionActions` with the same `HubError` surface as `send_prompt`.
- New hub calls are added to `HubClient` one per tool, and
  `ToolsTheAppMayCallTest` is extended so a tool outside the hub's
  `CLIENT_TOOLS` cannot be called by mistake; write tools are gated by
  `Credentials.grantsWrite` as `send_prompt` is today.
- No new runtime dependencies beyond the markdown renderer (evaluate its
  iOS footprint first; the ZXing-over-ML-Kit precedent applies).
- Everything is testable on the JVM except the voice `actual`s and the
  connectivity `actual`s, which get the same treatment as `AndroidSecrets`:
  a device test in the emulator job, and an honest note where it does not run.

## Testing

- `commonTest`: sanitiser fixtures from the screenshots; `displayName` for
  `bg:` rows; status token selection (stuck outranks blocked); attention
  membership including the five-minute `unknown` rule with an injected clock;
  `HubError.friendly` for every code the app knows; `pending_input` parsing
  with the field absent, present and malformed; the card's chip set for each
  `stuck_kind`; `contract` parsing and refusal.
- View-model tests: the blocked card sends, spins, waits on `turn_gt`, and
  collapses on status change; Enter is refused for a non-trusted client with
  the explanatory message; overflow actions surface `HubError.Tool` messages;
  `canSend` follows the `/mcp` probe, not the SSE state.
- Mutation sweep on every new gate before the PR is opened, per the repo
  skill.
- Screenshots re-taken on the phone after each phase for the review folder.

## Roadmap this leads to

- **Phase 2 — the fleet at a glance:** three-line row anatomy, summary header
  with counts and `$ today`, sort/filter/search, swipe actions, the Changes
  sheet (`repo_changes` / `repo_diff` / PR / CI), New session, the Tasks tab,
  expandable tool calls (hub: `session_tool_detail` as an MCP tool).
- **Phase 3 — the phone reaches you:** Android foreground-service watcher
  over the SSE stream with transition notifications and Reply/Approve
  actions, deep links, persisted snapshot, Glance widget; hub:
  `needs_attention` reason, `attention_digest` / `wait_for_attention`,
  `message:created`, `list_sessions {needs_attention}`. iOS via an APNs relay
  in the hub, later.
- **Phase 4 — 10×:** "Ask the fleet" through the operator session, the
  overnight digest, approve from the lock screen, broadcast with fan-in,
  budget alerts, heat-map header and agent avatars.
