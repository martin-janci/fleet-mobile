# fleet-mobile — platform integration and the moments between opens

Lens: notifications, widgets, background, connectivity, onboarding, navigation, gestures. Sources: the screenshots; `fleet-mobile` (App.kt, Navigator.kt, EventStream.kt, FleetRepository.kt, PairScreen.kt, SettingsScreen.kt, MainActivity.kt, AndroidManifest.xml, Info.plist, README, design doc); `docs/hub.md`; `docs/control-api-reference.md`.

## 1. The operator's day

The app is *foreground-only*: `LifecycleStartEffect` starts the SSE follower on start and cancels it on stop; nothing else runs. So the "today" column is the same all the way down, and that is the finding.

| Time | Moment | Hub already has | App today |
|---|---|---|---|
| 08:10 | Dispatches 6 agents, leaves the desk | — | — |
| 08:40 | Agent A hits a permission prompt | `claude_status=blocked`, `current_activity="waiting for input: …"` | Nothing. Found at 11:00; 2h20 of agent time lost |
| 09:05 | Agent B finishes, PR opened | `completed`, `pr_url`, `ci_status` | Nothing |
| 09:30 | CI on B goes red | `ci_status=failure` | Nothing; not even on the row |
| 09:50 | Agent C's CLI needs re-login | `stuck_kind=auth_menu` | Nothing; the host's other agents queue behind it |
| 10:15 | Agent D OOMs, host reboots | `stuck_kind=oom`, `host:probed unreachable` | Nothing |
| 10:40 | Agent E loops, $18/hour | `usage_report`, `fleet_health` cost | Nothing; cost is absent from the app |
| 11:00 | Opens app on the train | — | Blank list until `ready` (no cold cache); "reconnecting (attempt 4)…"; Send disabled because it is gated on the *stream*, not `/mcp` |
| 11:02 | Answers A | `send_prompt` | Works — then the screen must stay open to see the reply; locking the phone drops the stream |

A good window. Not a pager, and a pager is what a human away from hour-long agents needs.

## 2. Findings

**Onboarding / pairing.** Install → Pair (camera off, no permission ambush) → Scan → grant → "Paired as phone, full" → *Open the fleet* → blank list → `ready` → populated. Unusually well-reasoned (single-use dedupe, echoed-loopback rule, link fills-but-never-submits). Gaps: the blank list after "Open the fleet" reads as failure — connect during the confirmation and enable the button on `ready`; `claudefleet:` is not `BROWSABLE`, so the printed URL is not tappable from a chat — fine, but then Pair needs "Paste from clipboard", the real no-camera path; and nothing tells the operator the phone is silent when closed. Say it on the Paired screen until it is false.

**Connectivity.** Stream dropped → "reconnecting (attempt N)… reason", backoff 1–30 s, resync on `ready`. Hub unreachable → same banner over the last snapshot (none on cold start). Revoked → 401 terminal, credential dropped, Pair with a reason. All surfaced; the reason line is a strength. Missing: **version skew** — `frameToEvent` reads `version` and `kinds` and ignores `contract`; the phone has no `MIN/MAX_HUB_CONTRACT` and will merge renamed rows silently, exactly what the desktop refuses (`hub.md` → *Version skew*). Send is gated on SSE, so a proxy that kills long GETs but passes POSTs leaves the operator unable to answer an agent they can see. "Attempt 4" in airplane mode should read "no network"; `ConnectivityManager`/`NWPathMonitor` know and are never asked.

**Navigation.** Three tabs with letter glyphs, two of them "S" (screenshot 09). One-level back stack, correctly wired to `BackHandler`; predictive back not opted in. No deep link into a session. No state restoration: process death mid-session lands on the list.

**Gestures.** No pull-to-refresh (a "Refresh" button — the ritual the design doc wanted gone). No swipe or long-press on rows. The "…" on the session bar is unexplained.

**Foreground-only liveness / battery.** Dropping the stream on `onStop` is right for battery. But nothing compensates: no periodic check, no snapshot, no badge. `ACCESS_NETWORK_STATE` is already merged in (media3), unused.

**Cold start.** No cache, by an explicit hardening argument. Sound for the credential store, wrong for a session list: a plain `DataStore` holding `{id, name, host, claude_status, stuck_kind, updated_at}` leaks nothing a screenshot does not.

## 3. Proposals

### (a) Notifications without a vendor push

**Android foreground service on the existing SSE reader (M).** `FOREGROUND_SERVICE_DATA_SYNC` ("Watching 34 sessions on fleet.rlt.sk") holds one `/events?kinds=session,host`. Needs runtime `POST_NOTIFICATIONS`, a min-importance sticky notification, and Doze awareness: the socket will be cut in Doze — accept it, and let a `WorkManager` periodic (15-min floor) run a `list_sessions` diff as catch-up. Notify on row *transitions*, not frames: `→blocked`, `stuck_kind null→x`, `ci_status→failure`, `→failed`, `→completed` with `pr_url`, host `reachable→unreachable`. Cost ~1–3 %/day on Wi-Fi, worse on cellular; a Settings toggle "Watch in background", on by default for `full` clients only. Cheaper fallback (S): no service, just the 15-min `WorkManager` diff.

**Channels**, so the operator tunes them in system settings: `attention` (blocked/stuck: high, sound, optional DND bypass), `failed` (failed/OOM/host lost: high), `finished` (default), `ci` (default), `cost` (low), `service` (min).

**Actions (M).** `Open` (deep link, d); `Reply…` via `RemoteInput` → `send_prompt`; for `blocked` with `waiting for input:` an `Approve` = `send_prompt("y")` — safe only once the hub says what *kind* of prompt it is. For `auth_menu` offer nothing: "needs a login on mefistos".

**Hub dependency.** Works on today's events. Two additions make it good: a `pending_input {kind: permission|question|menu, options}` field on `SessionRow`, derived from the pane-intel that already fills `current_activity`, so Approve is a button and not a guess; and `attention_digest` (or `list_sessions {needs_attention}` plus a fleet-wide `wait_for_attention {since_seq}` long-poll). `wait_for_session` is the wrong primitive for a phone: one session, 600 s, one open request each.

**iOS, honestly.** No foreground services; background URLSession cannot hold SSE; `BGAppRefreshTask` runs a few times a day at the system's whim; local notifications need running code. Without push the ceiling is an unreliable refresh diff, a Live Activity that freezes on background, and badge-on-open. Re-scope the non-goal: *no vendor push on Android* is achievable and right; on iOS the only real path is APNs from the hub (`hub.apns` token key, a `register_device` client tool, ~200 lines of Rust). Revisit for iOS only — there the vendor is the OS.

### (b) Widget / Live Activity / lock-screen glance

**Android Glance widget (M):** "2 need you · 5 working · 1 failed" plus the top three attention rows with one line of `current_activity`, each a deep link; fresh via (a)'s service or poll (`updatePeriodMillis` floor is 30 min). **iOS WidgetKit (M):** same, reloaded on app-open and refresh task; a Lock Screen accessory with the blocked count. **Live Activity (L):** start when opening a `working` session — "ano · working · 12 min · turn 41" in the Dynamic Island; updates need the app alive or APNs push-to-update, so ship only with (a)'s iOS path. Hub: `attention_digest` for one cheap call.

### (c) Quick Settings tile / shortcut "What needs me?" (S)

Android `TileService` with the attention count as subtitle, tap opens the list filtered. Static shortcuts (long-press icon) and iOS quick actions: "Needs attention", "Hosts". Persist the filter instead of resetting it on process death.

### (d) Deep links (S)

`claudefleet://session/<id>` and `claudefleet://attention` handled by `Navigator` (today the scheme routes only to `onPairLink`, which drops anything that is not a pairing payload). Pair links stay non-`BROWSABLE`; session links can be, they carry nothing. Add `https://fleet.rlt.sk/s/<id>` as App Link / Universal Link so the desktop's "copy session link" opens the phone. Hub: serve `assetlinks.json` and `apple-app-site-association`.

### (e) Offline snapshot and reconnect (S)

Persist the last snapshot, draw it instantly with "as of 11:02". Three banner states from platform truth: *no network* (no counter), *hub unreachable (attempt N)*, *stream down but hub answers* — in the last, let Send work off a `/mcp` probe. Parse `contract` and refuse out-of-range hubs with the desktop's sentence. Pull-to-refresh on both lists.

### (f) Wearable (optional, M)

Wear OS tile and watchOS complication off the same digest. Only after (a): phone notifications already mirror to the wrist, and `Reply` with voice input reaches a blocked agent from there for free.

## 4. Top 10 by impact

1. Android foreground-service watcher + transition notifications (a) — the window becomes a pager; M.
2. `attention` channel with `Reply` `RemoteInput` — answer from the shade; M.
3. Persisted snapshot, instant cold start (e) — S.
4. `claudefleet://session/<id>` + App/Universal Links (d) — S; unlocks 1, 2, 5.
5. Home-screen widget with count and top three rows (b) — M.
6. Hub `pending_input` + `attention_digest`/`wait_for_attention` — hub M, app S; makes Approve real.
7. Parse `contract`; version-skew refusal (e) — S; stops silent wrong rows.
8. Connectivity-aware banner; Send gated on `/mcp` (e) — S.
9. APNs via hub for iOS (a) — L; the only way iOS stops being a window.
10. Pull-to-refresh, real tab icons, predictive back, saved filter — S; the polish every review flags first.

## 5. Three 10x ideas

**Notification-native fleet.** Ninety percent of operator interaction is "an agent asked; I said yes/no/one line." Make the notification the primary UI — grouped by host, `Reply`/`Approve`/`Open` — and the app the place for the other ten percent. Zero hub changes to prototype on Android.

**Budget as an alert.** `usage_report` has per-session cost. Let the phone set a ceiling (`set_budget` tool), have the hub emit `session:budget_exceeded`, notify with `Pause` (`safe_kill_session`). Cost is the one failure that spends money while nobody looks, and it is invisible on the phone today.

**One conversation across devices.** The hub knows which client last touched a session and `session_history` records `client:phone`. Show "on your Mac" when the desktop has it, offer *Take over here*, and when the operator sits back down the desktop timeline shows what was answered from the train. The fleet then talks to one operator, not two apps looking at the same rows.
