---
name: fleet-mobile-repo
description: Use when developing fleet-mobile — the Kotlin Multiplatform phone client for claude-fleet (Android + iOS, shared Compose UI). Covers setting a machine up, which test host proves what, running CI without a PR, pairing the app without a camera, and the traps that have cost real time here. Triggers inside the `fleet-mobile` repo or on asks like "the mobile app", "the phone client", "fix fleet-mobile". Sister: `claude-fleet-repo` for the hub and desktop this app talks to.
---

# Working on fleet-mobile

A Kotlin Multiplatform phone client for `claude-fleet`: one shared Compose UI,
so Android and iOS are the same app rather than two that look alike. It talks to
a running `fleet-hub` over JSON-RPC `tools/call` on `POST /mcp` plus an SSE
subscription on `GET /events`, holding a paired **client** token that is never
the fleet's master token.

Read `README.md` once — it is unusually complete and explains *why* for most
decisions. This skill is the part that is not in the README: what the machine
can prove, and what has gone wrong before.

## Setting up

```bash
scripts/bootstrap.sh     # JDK check → SDK → licences → local.properties → runs the suite
scripts/doctor.sh        # what this machine can and cannot run, and what each gap costs
```

`bootstrap.sh` finishes by building, not by printing "Ready". Re-running it is a
no-op. Three things it encodes that cost an afternoon each when done by hand:

- **`platforms;android-37` does not exist.** The package carries the minor
  version: `android-37.0`. sdkmanager reports this by naming the package rather
  than the mistake.
- `unzip` is not installed on every box; the command-line tools ship as a zip.
- The archive unpacks to `cmdline-tools/` and sdkmanager refuses to run unless
  it sits at `cmdline-tools/latest/`.

`compileSdk` is 37 and that is not a preference — Compose Multiplatform 1.12's
androidx artifacts and `okhttp-android` 5.5 both fail their AAR-metadata check
below it.

## Which host proves what — read this before trusting a green run

`commonTest` runs on **three** hosts and it is easy to miscount which:

| Task | Host | Proves |
|---|---|---|
| `:shared:jvmTest` | JVM | commonTest + the `jvmTest` source scans (`IosHostTest`, `AndroidHostTest`, `CiWorkflowTest`, …) |
| `:shared:connectedAndroidDeviceTest` | Android emulator | commonTest **again** (the device-test source-set tree pulls it in), plus `AndroidSecrets` and `QrDecodeTest` |
| `:shared:iosSimulatorArm64Test` | Kotlin/Native | commonTest on the toolchain iOS actually ships, plus `KeychainSecretsTest` |
| `:androidApp:connectedAndroidTest` | Android emulator | the only tests that **launch the app**: manifest intent routing, deep-link delivery, the platform cleartext policy, and the one rendered Compose screen |
| `xcodebuild test` (iosApp) | iOS app process | `KeychainRoundTripTests` — the only thing that launches the app on iOS |

The first two are both a JVM. Kotlin/Native is the one with different string,
regex, coroutine and memory implementations, and
**`iosSimulatorArm64Test` is silently disabled off macOS** — the Kotlin plugin
skips it with a warning, so putting it in a Linux job reads as coverage while
asserting nothing. `CiWorkflowTest` has a gate refusing exactly that.

### Two iOS test hosts, deliberately

- `shared/src/iosTest` runs under `simctl spawn`: not an installed app, no
  entitlement, so `securityd` answers `errSecNotAvailable` (**-25291**).
  `KeychainSecretsTest` uses that to assert the **refusal** path, which is a
  real production case — a background wake before the device's first unlock is
  exactly what `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` invites.
- `iosApp/iosAppTests` is an XCTest bundle hosted by the app, so it runs inside
  the app's process and does the **round trip**. It needs the app **signed** —
  unsigned, the app launches fine and every Keychain call returns
  `errSecMissingEntitlement` (**-34018**), because entitlements come from
  signing. Ad-hoc (`CODE_SIGN_IDENTITY="-"`) needs no developer account on a
  simulator and synthesises the bundle id, which is what a real signed app gets.

`xcodebuild test` is also **the only thing that launches the app**, and the
first run that did found it could not start at all: Compose Multiplatform throws
`IllegalStateException` without `CADisableMinimumFrameDurationOnPhone` in
`Info.plist`, which on iOS is an uncaught Kotlin exception and a dead process.
Builds and bare-binary tests cannot see that class of failure.

### Kotlin/Native differs from the JVM in ways that matter

- A blown stack is **`SIGBUS`**, not a catchable `StackOverflowError`. So
  `catch (Throwable)` is not a fix on iOS; bound the input before the recursion.
  `JsonDepth.kt` exists for this.
- Exceptions only bridge to Swift for functions marked `@Throws`. A Swift
  `do/catch` around an unannotated Kotlin call never runs — the exception
  terminates the process.
- **Measure on the dispatcher the app really uses.** The same stack probe on the
  test's *main* thread parsed 10,000 levels happily; only
  `withContext(Dispatchers.Default)`, where Ktor delivers, reproduced the crash.
- **`compileKotlinIosSimulatorArm64` green on Linux does not mean it links.**
  The klib step runs here; the LLVM step (`linkDebugTestIosSimulatorArm64`) is
  macOS-only and rejects things the compiler accepted. A Kotlin `object`
  extending an Objective-C class is one: it fails with *"Allocation of Obj-C
  class … should have been lowered"* at link time and compiles cleanly at every
  step before that. Use a `class`. Anything touching `iosMain`/`iosTest` and
  cinterop needs a CI round before you believe it.

## The contract with claude-fleet, and the one part that drifts

Five wire contracts bind this app to the hub. Four are self-correcting:

| Contract | Why it does not drift |
|---|---|
| Tool names and access | `ToolsTheAppMayCallTest` allow-lists them; the hub refuses anything else; `tools/list` decides the additive ones per connection |
| `SessionRow` | `ignoreUnknownKeys`, so new columns pass by |
| `POST /pair` | Four fields, unchanged since it was written |
| `GET /events` | `ready` / `lagged` plus open-ended row events |

**`ConvItem` is the one that drifts, and it drifts silently.** It is a tagged
union in `crates/fleet-core/src/service/transcript.rs`, and this app has its own
copy in `model/Conversation.kt`. When the hub grows a variant, nothing fails:
`ConvItemSerializer` falls back to `Unsupported` on purpose, so the screen draws
`(unsupported item: <kind>)` and both repos' test suites stay green, because
each side is internally consistent.

That has already cost something. The hub's `74c82b3` (2026-09-20, "parse task
notifications instead of printing their XML") improved the desktop and made the
phone worse: task notifications had been arriving as `text` items holding raw
XML — ugly, and readable — and afterwards arrived tagged, so the phone showed a
placeholder where content used to be.

So when anything touches `transcript.rs`, check `ConvItemTest` — it holds a
fixture in the hub's own wire shape for every kind, and
`every_kind_the_hub_emits_today_is_modelled` is the list to extend.
`ConversationItemsTest` (emulator) then proves each one actually draws, which
is a different claim from parsing.

## Gating on the hub's tools, not its version

The work graph (M8) is additive, and the wire contract (`MAX_HUB_CONTRACT`) is
**not** what gates it — a contract bump refuses whole connections, and a
missing feature should only hide a button. Instead, on every `ready`,
`FleetRepository` calls `tools/list` once (plain MCP, same auth) and publishes
`FleetState.capabilities`:

- `work` present → chips, *By work*, *My work*, the Tickets sheet.
- `work_link` present → Confirm / Not this / Clear / Set work… / Start here /
  Resume. The hub filters `tools/list` per caller, so a **readonly** token is
  never shown `work_link` — and the UI checks `Credentials.canWrite` as well.
  Both, always: never call a tool the token cannot use.
- **Actions.** `action` is a free string on hubs before M8.0. When the schema
  has an `enum`, `HubCapabilities.has(tool, action)` reads it; when it does
  not, an action counts as present until the hub answers `E_INVALID`
  "unknown … action", which `FleetState.actionMissing` records **for that
  connection** (the next `ready` asks again — it may be an upgraded hub).
- A hub that cannot answer `tools/list` reads as the old hub: nothing
  work-shaped is offered, and nothing errors. `HUB_VERSION_KEYS` stays only
  for the `send_prompt { keys }` chips.

`work` and `work_link` are in `ToolsTheAppMayCallTest`'s `permitted` set;
`work_admin` (tracker administration, master-only) is in `forbidden`. Two row
rules to keep: a `session:updated` payload **without** a `work` key keeps the
old `work` (like `is_controller`), while a missing `work_suggested` means "no
suggestion" — the hub skips it when empty. `FleetSnapshotTest` pins both with
store-row fixtures. Grouping by work mirrors the desktop's
`buildSessionsByWork` (`src/lib/sidebar_index.ts`); `SessionsViewModelTest`
carries its cases by name, so extend both together.

## How Compose surfaces to XCUITest on iOS

Measured on an iOS 18.5 simulator (there is no other way to know it from here):

| Compose | XCUITest element |
|---|---|
| `Text` | `StaticText`, carrying `label:` |
| `OutlinedTextField` | `TextView`, `label:` = its label joined to its supporting text |
| `Button` | `Button` with a nested `StaticText`; reports `Disabled` when it is |

`PairLinkUITests` attaches the whole tree on every run, and CI keeps the
`.xcresult` for it — the log alone drops attachments. That attachment is what
diagnosed the `%23` bug below; without it the failure was three
indistinguishable possibilities.

**`URL.absoluteString` percent-encodes `#` for the `claudefleet:` scheme.** So
iOS hands `onOpenURL` a URL whose string is `…/pair%23ABCDEFGH`, and every deep
link failed with *"that is not a claude-fleet pairing code"* until
`pairLinkPayload` learned to undo that one encoding. Android never sees it —
`Uri.toString()` does not re-encode — which is why a shared parser was not
enough and the two platforms needed separate proof.

## No Mac and no `/dev/kvm` here

So the emulator and simulator jobs can only be exercised in CI. The workflow has
`workflow_dispatch`:

```bash
gh workflow run CI --ref "$(git branch --show-current)"
gh run download <run-id> -n ios-xctest-log            # the iOS test output
gh run download <run-id> -n instrumentation-reports   # the emulator run
gh run download <run-id> -n test-reports              # jvmTest XML
```

**Prefer artifacts to job logs.** `gh run view --log-failed` hits `/jobs`, and
polling it tightly trips GitHub's *secondary* rate limit — which is not
exhausted quota (every bucket still reads 5000/5000) and which **each retry
extends**. Poll at 150s+, and read results from artifacts instead.

## Two traps that have each bitten more than once

### `jvmTest` can pass stalely

The host tests read repo files through `Repo.file(...)` at run time. Those are
not inputs Gradle knows about, so editing `Info.plist`, a workflow or a script
leaves `jvmTest` **UP-TO-DATE** and the scan silently does not re-run. A deleted
plist key once reported SUCCESS.

`shared/build.gradle.kts` now declares them **by directory** —
`androidApp/src`, `iosApp`, `scripts`, `.github/workflows` — precisely because
the file-by-file list was wrong three separate times. If a mutation "survives" a
source-scan gate, suspect this first and re-run with `--rerun-tasks`.

### A gate that matches a substring is not a gate

Found repeatedly: `unusedOnNewIntent` still contains `onNewIntent`;
`deliver(intent)` still appears inside `onNewIntent` after the `onCreate` call
is deleted; `"DEVELOPMENT_TEAM" !in ci` fires on a *comment* explaining the team
stays empty. Match the declaration or the call site, and follow
`ToolsTheAppMayCallTest`'s lead of matching **quoted** names so prose is safe.

## Mutation testing is what actually finds gaps here

Reading and 400 passing tests did not find these; flipping one operator at a
time and re-running `:shared:jvmTest` (~6 s each) did. Two sweeps found twelve
real gaps, all of them "exactly at the boundary" — including two in ceilings
written days earlier whose own tests used `MAX - 1` and `MAX - 16`.

Writing a sweep: mask string literals (or you mutate message text), take
comparison operators **only when whitespace-delimited** (Kotlin spaces a
comparison and not a generic — this cut non-compiling mutations from 122 to 18),
treat a **hang as a kill** and restore in a `finally`, or a crash leaves the tree
dirty.

**Triage survivors; do not reflex-fix.** Of 55 survivors in one sweep, 43 were
equivalent mutants, unobservable data-class defaults, or unrenderable Compose.
"Fixing" those means adding tests that assert nothing.

## Pairing the app without a camera

A `claudefleet:` URL carries the same string the QR encodes, with the scheme in
front, so `PairTarget.parse` does all the validating and there is no second
parser:

```bash
adb shell am start -a android.intent.action.VIEW -d "claudefleet:https://hub/pair#ABCDEFGH"
xcrun simctl openurl booted "claudefleet:https://hub/pair#ABCDEFGH"
```

`MainActivity` is `launchMode="singleTop"`, and it must stay that way. Under
the default `standard` mode the *second* `am start` never reaches
`onNewIntent`: Android stacks a fresh activity and the one on screen keeps the
first URL. That is precisely the case an agent hits when it re-runs a setup
script, and it was live until the emulator caught it. If a link seems to be
ignored, check that attribute first.

**Release fills the two fields and waits for a tap; a debug build submits.** The
difference is wired to the build (`BuildConfig.DEBUG`, `Platform.isDebugBinary`)
and never to anything in the link — a link is something anyone can send, and a
silent pair would re-point the app at a hub of the sender's choosing.

For an agent setting a dev machine up: mint the code on the hub
(`fleet-hub pair --name phone`), prefix the printed URL, fire it at a **debug**
build. A freshly paired app shows an empty fleet unless the hub has real
sessions, so point it at a live hub rather than expecting demo data.

## Conventions worth knowing before changing anything

- **The token is the one value with a rule of its own.** Nothing that reaches a
  screen, a log or a crash report may repeat it. `HubError.Transport` refuses to
  keep a cause that quotes the wire, because `kotlinx.serialization` appends the
  input document to its message and the hub writes `token` first in the pair
  reply. `redacted()` scrubs **before** capping — capping first cuts a token in
  half and the `replace` then matches nothing.
- **One implementation of a rule.** Host parsing is `hostOfUrl`/`normalizedHost`
  and nothing else; duplicated host parsing is the documented root cause of the
  four separate occasions `isThisMachine` was wrong.
- **`MutableStateFlow.update {}`, never `value = value.copy(...)`.** Stated in
  `SessionViewModel`'s KDoc; two classes have been fixed for breaking it.
- **The app never calls a tool its token may not use.** Its four read tools are
  in the hub's `READONLY_TOOLS`; `send_prompt` is not, which is what
  `canSendPrompts` gates. `work_link` is not either, and is gated twice (see
  *Gating on the hub's tools*). `Credentials.grantsWrite` mirrors the hub's
  `TokenMode::parse` exactly — only the literal `full` grants write, because
  the hub fails closed and the app used to fail open.
- **A gate that cannot fail is worse than no gate.** This repo has removed
  several. If you add one, mutate the thing it guards and watch it go red.

## When in doubt, read the hub

`claude-fleet` is usually checked out alongside. The app's assumptions about the
hub are checkable against `crates/fleet-core/src/mcp/` rather than against
prose — that is how the `grantsWrite` inversion and the spent-pairing-code
message were found. Note `POST /pair` consumes the code **before** it mints
anything, so everything the app does afterwards happens with the code already
spent.
