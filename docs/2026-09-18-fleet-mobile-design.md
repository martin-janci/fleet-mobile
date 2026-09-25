# fleet-mobile: the phone client

**Date:** 2026-09-18
**Status:** Approved design, awaiting implementation plan
**Scope:** a new repository, `martin-janci/fleet-mobile` — a Kotlin Multiplatform app with a
shared Compose Multiplatform UI for Android and iOS, talking to a `fleet-hub`
over its existing HTTP API. No change to `claude-fleet`.

## Why

Sub-projects 1 and 2 put the fleet behind one always-on hub reachable over
HTTPS, with a credential a phone can hold, a live event stream, and the
conversation available as a tool. This is the client those were built for: see
every session across every machine from a phone, read what an agent is doing,
and steer it.

This is sub-project 3 of five. It is the original ask.

## Goals

- **One UI for both platforms.** Compose Multiplatform, so screens are written
  once. Platform code is limited to what genuinely differs: secure storage,
  camera, notifications, lifecycle.
- **Pair by scanning.** Point the camera at the QR code `fleet-hub pair`
  prints, or paste the code. The phone ends up holding its own token.
- **See the fleet.** Sessions grouped by host and project, with status,
  what each is doing, and which need a human.
- **Read and steer.** A session's conversation as structured turns, and a
  prompt box that sends to it.
- **Stay live.** The event stream drives the list; no pull-to-refresh ritual,
  though a manual refresh exists.

## Non-goals

- A terminal emulator. The desktop app owns the PTY; a phone shows the
  conversation, not the raw pane.
- Editing files or reviewing diffs. Read-only repository views may come later.
- Host administration. The hub refuses a phone every fleet-admin tool, so the
  app does not pretend to offer provisioning, adding or removing hosts.
- Push notifications while the app is closed. That needs a vendor service and
  a hub-side sender; it is its own sub-project.
- Offline editing or a local mirror of fleet state. The app caches what it has
  seen so a cold start has something to draw, and refetches.

## Architecture

```
fleet-mobile/
  shared/                     Kotlin Multiplatform library
    commonMain/               models, HubClient, repositories, view models, Compose UI
    commonTest/               everything testable without a device
    androidMain/ iosMain/     expect/actual: secure storage, camera, platform bits
  androidApp/                 thin Android host: one Activity, Compose entry point
  iosApp/                     thin SwiftUI host embedding the shared UI
```

- **Language and UI:** Kotlin Multiplatform with Compose Multiplatform. The
  Android app is Jetpack Compose; iOS renders the same composables.
- **Networking:** Ktor client (OkHttp engine on Android, Darwin on iOS) with
  `kotlinx.serialization`. Two shapes: JSON-RPC `tools/call` over `POST /mcp`
  for everything, and an SSE subscription on `GET /events`.
- **State:** a `FleetRepository` owns an in-memory snapshot of sessions, hosts
  and projects, applies event frames to it, and exposes `StateFlow`s. Screens
  observe; nothing polls except a bounded refresh on resume.
- **Storage:** the hub URL and token live in platform secure storage —
  `EncryptedSharedPreferences` on Android, the Keychain on iOS — behind one
  `expect` interface. Nothing else is persisted except a small cache of the
  last session list, so a cold start draws something immediately.

### Talking to the hub

One client type wraps the three shapes the hub offers:

| Call | Shape |
|---|---|
| Pair | `POST /pair` with the scanned code; returns the token and the hub's own base URL |
| Anything else | `POST /mcp`, JSON-RPC `tools/call`, SSE-framed reply — take the `data:` line |
| Live updates | `GET /events`, SSE, `event:` name and a JSON payload per frame |

Every request carries `Authorization: Bearer <token>`. The tools the app uses:
`list_sessions`, `list_hosts`, `list_projects`, `session_conversation`,
`send_prompt`, `capture_session`, `session_history`, `wait_for_session`,
`fleet_health`. The app never calls a tool the hub would refuse a client, so a
refusal is a bug, not a flow.

### Screens

1. **Pair** — when no token is stored: a camera view with a manual-entry
   fallback, then a success screen naming the hub.
2. **Sessions** — the home screen. Grouped by host, then project. Each row:
   name, status, and the one-line activity the hub already exposes. A filter
   for "needs attention" (blocked or stuck).
3. **Session** — the conversation as turns, newest at the bottom; a prompt box;
   the session's status and host; actions limited to what a client may do.
4. **Hosts** — reachability, the Claude and tmux versions, and the session
   count per host.
5. **Settings** — which hub, which client name, a way to forget the token
   (which does not revoke it; revocation is the operator's, from the terminal),
   and the app's own version.

### Events

The app subscribes on resume and drops the subscription on background. A
`ready` frame carries the hub's version; a `lagged` frame or any disconnect
triggers one full refetch and a resubscribe with backoff. Frames are applied
to the snapshot by id: session rows replace, `session:killed` removes, host and
project rows replace.

### Failure and refusal

- No network, or the hub unreachable: the last snapshot stays on screen with a
  banner; actions are disabled rather than hidden.
- `401`: the token is gone or revoked. The app drops it and returns to Pair
  with an explanation, since only the operator can issue another.
- `403`: the host name is not what the hub expects. Shown verbatim with the
  hub URL, because that is a configuration problem the operator must fix.
- A tool answering `isError` shows the `E_*` code and message as-is. These are
  written for a person and hiding them would be worse.

## Testing

- **Shared, on the JVM:** the JSON-RPC envelope (including the SSE `data:`
  framing the hub returns), event-frame application to the snapshot, the
  pairing flow against a fake client, the refusal paths (401, 403, `isError`),
  and reconnect with backoff. A fake `HubClient` backed by recorded responses,
  so no network is needed.
- **Android instrumentation:** deliberately minimal — secure storage round
  trip and the camera permission path, since those are the platform pieces.
- **Manual, on device:** pair with a real hub, watch a session update live,
  send a prompt and see the reply arrive.

## Build and CI

Gradle with the Kotlin Multiplatform and Compose plugins; the Android
application module builds a debug APK. iOS is configured but builds only on a
Mac with Xcode, so CI builds the shared library and the Android app, and the
iOS target is the developer's own `xcodebuild` on the Mac. GitHub Actions runs
`./gradlew :shared:jvmTest :androidApp:assembleDebug` on Linux.

## Open questions settled here

1. **Compose Multiplatform for iOS rather than SwiftUI.** The ask was one
   shared view; Compose on iOS is stable enough for a tool used by its author,
   and a SwiftUI rewrite of five screens is the alternative.
2. **No hub administration in the app**, because the hub refuses it anyway.
3. **The app never stores the master token**, only a paired client token, so a
   lost phone is revoked from the terminal without touching anything else.

---

# Appendix: what changed during implementation

*Added by Task 8, 2026-09-18. Everything above is the approved design, copied
verbatim from `claude-fleet`'s `docs/superpowers/specs/`. This appendix records
where the built app differs from it and why, so a reader of the design is not
misled by a sentence that stopped being true. Each of these was ruled on at the
time; none of them is a silent drift.*

**Secure storage is not an `expect`/`actual` pair.** The design says the hub URL
and token live behind "one `expect` interface". `Secrets` is a plain Kotlin
interface with `AndroidSecrets` and `KeychainSecrets` implementing it, because
an `expect class` forces one constructor signature on both actuals — and Android
needs a `Context` while iOS needs nothing. The workaround would be a global
application-context holder installed by a `ContentProvider`, which buys the
keyword at the price of hidden global state. The platform host constructs its own
store and hands it in. **The camera is the only `expect`/`actual` pair.**

**The barcode scanner is ZXing, not ML Kit.** The plan named ML Kit.
`com.google.mlkit:barcode-scanning:17.3.0` was measured on the debug runtime
classpath and pulls seventeen Play Services entries including
`transport-backend-cct`, Google's Cloud Client Telemetry uploader, and took the
debug APK from 14 MB to 40 MB. A phone paired to a self-hosted hub should not
have to carry a Google telemetry client to read one QR code, and may have no
Play Services at all. CameraX already yields `YUV_420_888`, whose Y plane is
exactly the luminance buffer ZXing's `PlanarYUVLuminanceSource` wants. iOS is
unaffected — `AVCaptureMetadataOutput` decodes QR in the platform.

**Two iOS targets, not three.** `iosArm64` and `iosSimulatorArm64`. Compose
Multiplatform publishes no `iosX64` artifact after 1.10.3, and declaring it fails
the *common* metadata compile rather than only an iOS task. `iosX64` is the
Intel-Mac simulator alone, so the cost is that an Intel Mac cannot run the
simulator locally.

**`compileSdk` is 37, not 35.** Forced by Compose Multiplatform 1.12's own
androidx artifacts and by `okhttp-android` 5.5 arriving through Ktor; 35 and 36
both fail the AAR-metadata check. `minSdk` (26) and `targetSdk` (35) are as
designed.

**CI runs `./gradlew build`, not `:shared:jvmTest :androidApp:assembleDebug`.**
Those two tasks are both green on a tree where commonMain uses
`Map.toSortedMap()` — a JVM-only extension that does not exist for
Kotlin/Native — so neither of them compiles the iOS targets. This actually
happened, and the whole build is what caught it.

**The snapshot holds projects as well as sessions and hosts, and the event
stream subscribes to `session,host,project`.** A session row carries a
`project_id` and no project name, so `list_projects` is the only thing that can
turn `3` into `owner/repo`, which the design's "grouped by host, then project"
needs. `ProjectRow` models only the fields that `list_projects` and
`project:updated` both carry, because the two shapes disagree and modelling a
field only one of them has would let an event frame blank what the list filled
in.

**An echoed loopback hub address loses to the address the phone actually
reached.** The design says the `/pair` response carries "the hub's own base URL"
and implies storing it. The hub sets that field to its configured public URL or,
when none is set, its own loopback base — and `http://127.0.0.1:8899` stored on
a phone means the phone. Following the design literally would let every operator
who has not set `hub.public_url` pair successfully and then hold a permanently
dead app.

**A `readonly` credential disables the prompt box** rather than sending and
showing the hub's refusal. `send_prompt` is not in the hub's readonly allow-list,
and the standing constraint is that the app never calls a tool its token may not
use.

**A `401` on the event stream is terminal.** The repository stops reconnecting
and goes Offline, because only the operator can issue another token. Every other
failure, including a `503` "events are not enabled", retries at
1/2/4/8/16/30 seconds.

**The event stream follows the lifecycle, not the composition.** The design's
"subscribe on resume and drop on background" needed a real lifecycle observer:
a backgrounded Android activity keeps its composition, so the first
implementation held the SSE connection open for as long as the app was
installed. This is why the iOS host must embed the UI through
`ComposeUIViewController` — it is the only place Compose Multiplatform provides
a lifecycle owner on iOS.

**There is no cold-start cache.** The design promises "a small cache of the
last session list, so a cold start draws something immediately." The app's
only persistence seam is `Secrets` (`EncryptedSharedPreferences` on Android,
the Keychain on iOS), sized and hardened for exactly one thing — a bearer
credential — with `allowBackup` disabled, the API 31+ data-extraction rules
excluding it from every backup and transfer path, and
`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` on iOS. A session row
carries a project path and a host alias, so writing it through that credential
store, or beside it in a second, unencrypted seam, changes what that hardened
boundary protects. `FleetRepository` starts from `emptyList()`; the fleet list
is blank until the first `refresh()`/`ready` resync lands, same as any other
cold start once the event stream connects.

**Send is gated on the `/events` stream, not a live probe of `/mcp`.**
`ConnectionStatus.Connected` — the one state `SessionViewModel.canSend`
requires — comes entirely from the SSE follower in `FleetRepository`, so if
`/events` is unavailable while `/mcp` still answers, Send stays disabled until
the stream reconnects.

**`/events` has a bounded idle-socket timeout, not an unbounded one.** The
request itself has no deadline (a live stream has no natural end), but a read
that receives no bytes for `EVENTS_IDLE_TIMEOUT_MS` (45 s — two missed
15 s heartbeats) ends the connection. The engine's timeout surfaces as
`HubError.Transport`, the same shape any other dropped connection arrives in,
so `FleetRepository.follow()` reconnects with its existing backoff exactly as
it would for a severed socket.

**A session screen's conversation reads coalesce.** `load()`, `refresh()`, a
send's follow-up, and the event-triggered refetch all go through one queue: a
request folds into whatever read is already registered but has not started
its hub call yet, so a burst of taps or event frames costs at most one hub
call beyond whichever is already running. `SessionUiState.loading` tracks the
first-ever read; `refreshing` tracks every later one, queued or in flight, and
the Refresh button is disabled while either is true.

**The work graph (M8) is gated on the hub's tools, not on its contract.**
Plan: claude-fleet `docs/superpowers/plans/2026-09-24-work-graph-m8-phone.md`.
On every `ready` the repository calls `tools/list` and publishes
`HubCapabilities` (tool names, each schema's `action` enum when there is one,
and actions the hub refused as unknown on this connection). `MAX_HUB_CONTRACT`
did not move. `SessionRow` gained `work` / `work_suggested`, replaced whole with the row on
every `session:updated` (the plan's "keep the old value" was corrected by the
hub's M8.0: frames are null-stripped, so an absent `work` is a cleared link); `"work"` joined
`SNAPSHOT_EVENT_KINDS` for the ticket cache (`work:item` upserts,
`work:tracker_removed` marks unavailable), while a session's own work rides
`session:updated`. `work_link` joined `LIFECYCLE_TOOLS`, so a quick Confirm
also rides `/mcp` under the 330 s deadline — the price of starting work being
the same tool. The session screen's work lives in its own
`SessionWorkViewModel` rather than growing `SessionViewModel`; the plan's
`SessionViewModelTest` cases are in `SessionWorkViewModelTest`. The Tickets
sheet is a sheet over the Sessions tab, not a fourth tab. Resume offers only
`last` with a host picker; the phone never edits a brief. In ticket mode the
New session form leaves the project to the hub unless one is picked, and an
`E_AMBIGUOUS` narrows the list to the hub's candidates; `E_EXISTS` from start
or resume opens the named session instead.

**M8.6 caught the phone up with the hub** (claude-fleet M4.6, M5, M9.1–M9.3),
gated the same way, action by action. *Today* is a sheet like Tickets: it
sends local midnight as `since` (a `utcOffsetSeconds` expect/actual rather
than a date library), re-buckets after the org filter with the hub's rule,
and re-reads — debounced — on `session:*` and `work:item` while open; its
`standupText` is the desktop's, line for line, with the desktop's test cases
by name. The one departure: a session the phone has no row for yet keeps the
digest's own `org_id` instead of dropping out of every org. Orgs are read
once per connection (`work orgs`) into an `OrgDirectory` — a label and a
filter, never a fence, since a paired phone's token sees every org. A row's
org is its `org_id` (in the phone view from the hub's M8.6), else its work's.
Past work is the resume plan's `candidates`; the ticket card is `work card`
from the hub's cache. **Ask for a handover** is asynchronous on the hub, so
`FleetState.timeline` passes `session:event` frames on and the work sheet
follows `handover_*` for its session. `agent_inferred` links (Claude's answer
to the classification nudge) read "suggested by Claude when asked".

**Share standup.** Beside *Copy standup*, the Today sheet's **Share** hands
the same text to the platform share sheet (`rememberShareText`, an
expect/actual: Android's chooser, iOS's `UIActivityViewController`, the
clipboard on the test-only JVM target), for a standup posted from the phone.
What the two produce is held to the desktop's by a shared fixture,
`commonTest/fixtures/today-standup.json`, whose expected texts were produced by
running claude-fleet's `today.ts`; `StandupFixtureTest` runs every case the
phone's org filter can express through `scopeToday` and `standupText` on every
target.

