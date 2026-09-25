# fleet-mobile

A phone client for [`claude-fleet`](https://github.com/martin-janci/claude-fleet).

Every Claude Code session on every machine, on a phone: what each one is doing,
which ones are blocked, what an agent just said, a box to answer it with, and
a **+** to start a new one on any reachable host.
One Kotlin Multiplatform codebase with a shared Compose UI, so Android and iOS
are the same app rather than two apps that look alike.

It talks to a running **`fleet-hub`** over its existing HTTP API — JSON-RPC
`tools/call` on `POST /mcp` for everything it does, and an SSE subscription on
`GET /events` so the list moves by itself. It holds a paired client token of its
own, never the fleet's master token.

---

## Pairing

The app is useless until it has a credential, and a credential is minted on the
hub, by the operator, in a terminal. That is deliberate: a phone cannot enrol
itself.

On the machine running `fleet-hub serve`:

```bash
fleet-hub pair --name phone
```

It prints a QR code and the URL under it:

```
https://fleet.example.com/pair#ABCDEFGH
```

Open the app and either **scan that QR** or type the hub address and the eight
characters after the `#`. The app posts the code to the hub once, gets a token
back, and stores it — `EncryptedSharedPreferences` on Android, the Keychain on
iOS.

What travels in the QR is a *pairing code*, not a token: single-use, valid for
ten minutes by default, held only in the hub's memory (so restarting the daemon
voids it), and in the URL **fragment**, which a browser never puts on the wire.

```bash
fleet-hub pair --name kiosk --mode readonly   # observe only; the default is full
fleet-hub pair --name phone --ttl 120         # seconds the code stays valid (30–3600)
```

A `readonly` client can watch everything and send nothing; the app knows this
and disables the prompt box, and leaves out the New session button, rather than
making a call it knows would be refused.

It decides that the way the hub decides it, which is worth stating because the
two used to disagree. `TokenMode::parse` in the hub is
`match s { "full" => Full, _ => Readonly }` — **only the literal `full` grants
write** — and `store/clients.rs` gives the reason: "a typo would silently
downgrade a client rather than fail." So a mode this app does not recognise, a
future one or a value that got mangled, leaves the phone reading the fleet and
declining to send, exactly as the hub would treat it. A stored credential with
no mode at all is not treated as a credential: the hub always sends one, so its
absence means the file was truncated or written by something else, which is not
a reason to assume the most permissive answer.

### If the app cannot keep the credential

The pairing code is spent the moment the hub answers — `POST /pair` consumes it
before it mints anything, and the hub's own comment on its failure branch is
"the code is spent either way — mint a new one". So if the phone's secure store
then refuses the write, the credential exists on the hub and nowhere else, and
the app says so: *that pairing code is spent — run `fleet-hub pair` again*.
Retrying the same code cannot work.

The likeliest way to see this is a phone restored from a backup: the
`EncryptedSharedPreferences` file comes back but the Keystore master key that
decrypts it does not, so the store will not open — and that is exactly when
someone is setting the app up for the first time. The client row left on the hub
is the operator's to clear with `fleet-hub client revoke`.

### Pairing from a link

The eight characters can arrive without a camera. A `claudefleet:` URL carries
the same string the QR encodes, so the pair URL the hub prints becomes a link
by putting the scheme in front of it:

```bash
# Android
adb shell am start -a android.intent.action.VIEW \
  -d "claudefleet:https://fleet.example.com/pair#ABCDEFGH"

# iOS simulator
xcrun simctl openurl booted "claudefleet:https://fleet.example.com/pair#ABCDEFGH"
```

On Android the activity is `launchMode="singleTop"`, and that is load-bearing
rather than incidental: under the default `standard` mode a *second* `am start`
does not reach `onNewIntent` at all — Android stacks a new copy of the activity
and the one on screen keeps the first URL. The emulator showed exactly that,
which means the second link was being ignored by the screen the person was
looking at. It is the case an agent hits the moment it re-runs a setup script,
so it is the one that matters most here.

**A link fills the two fields and stops.** Somebody taps, exactly as they would
after typing the code. That is deliberate: a link is something anyone can send,
and while it cannot reach the credential this device already holds, a silent
pair would re-point the app at a hub of the sender's choosing and the next
prompt typed would go there.

**A debug build submits on its own**, so a dev machine can be set up with no
hands — which is the whole point of the scheme. The difference is wired to the
build (`BuildConfig.DEBUG`, `Platform.isDebugBinary`) and never to anything in
the link.

A link goes through exactly the checks a scan does — the same parser, so the
same rules about userinfo, cleartext and the code alphabet.

**Losing the phone is the operator's problem to solve, from the terminal:**

```bash
fleet-hub client list
fleet-hub client revoke phone
```

The app's *Forget this hub* in Settings only forgets the token locally. It does
not revoke it, it cannot revoke it, and the screen says so.

### If pairing fails

- **"the code was refused"** — codes are single-use and expire. Mint another.
- **Paired, then nothing loads.** The hub tells a freshly paired device which
  base URL to use, and if `hub.public_url` is not set it names its own loopback
  address, which on a phone means the phone. The app ignores an echoed loopback
  and keeps the address it actually reached, so this should not bite. But note
  which way round the rule goes: **any other valid echo wins over the address
  in the QR.** A hub reachable under several names stores the name the hub
  gives for itself, not the one you scanned, so set `hub.public_url` to the
  name you want phones to use.
- **A hub on the LAN, over plain `http://`.** Read *Transport* below before
  trying this. The short version: use HTTPS — loopback is the only cleartext
  exception the Android build ships. Android blocks cleartext outright
  at this `targetSdk`, and the app now refuses plain `http` to anything that is
  not on your own network.

---

## Transport

**Use HTTPS.** Everything below is about the cases where you cannot.

The app imposes its own rule, rather than relying on whatever the two platforms
happen to default to: plain `http://` is permitted only to a destination that is
plausibly this machine or this network — loopback in any spelling, the RFC 1918
private ranges, CGNAT and link-local, IPv6 unique-local and link-local, a
single-label name like `fleethub`, or anything under `.local`. Plain `http` to
anything else is refused when the address is entered or scanned, because a
bearer token in the clear on the open internet is not a trade worth making.
It fails closed, with a message.

Then the platform has its own say, and on Android it is stricter than the app:

- **Android.** `targetSdk` is 35, so the platform blocks cleartext for every
  destination unless the app ships an exception. The app now ships exactly one,
  in `res/xml/network_security_config.xml`, and it is three hosts wide:
  `127.0.0.1`, `localhost`, and `10.0.2.2` (the emulator's alias for the host
  machine's loopback). **Everything else is still blocked, including the LAN.**

  That exception exists because the emulator was asked and said no.
  `NetworkSecurityPolicy.isCleartextTrafficPermitted("127.0.0.1")` answered
  **false**, which meant pairing to a hub on the dev machine over `http` —
  the documented way to set one up, and the one `scripts/bootstrap.sh` builds —
  could not work and never had. `permitsCleartext` in the shared code was
  approving an address the platform then refused, and the failure surfaced as a
  connection error with no explanation. `CleartextPolicyTest` in
  `androidApp/src/androidTest` is that experiment, and it now pins both halves:
  loopback permitted, LAN and the public internet not.

  For a hub on the **LAN** over plain `http`, the answer is still HTTPS. Add a
  [network security configuration](https://developer.android.com/privacy-and-security/security-config)
  entry naming that host if you must. The format matches domains and IP
  *literals*, not CIDR ranges, so "all of 192.168/16" cannot be expressed —
  which is why the shipped exception stops at loopback, and why the shared
  `permitsCleartext` is deliberately wider than what Android can enforce. **Do
  not set
  `android:usesCleartextTraffic="true"`**: that is a blanket exception for every
  destination, which is precisely what the iOS side refuses, and a test fails if
  it appears.

- **iOS.** `NSAllowsLocalNetworking` permits cleartext to the local network and
  `NSLocalNetworkUsageDescription` lets the system ask permission for it, both in
  `iosApp/iosApp/Info.plist`. `NSAllowsArbitraryLoads` is deliberately absent and
  a test keeps it absent. Nobody has yet watched that permission dialog appear —
  see *What a Mac still has to check*.

The asymmetry is real and is not a bug in this document: a LAN hub over plain
http can work on iOS and cannot on Android, until someone adds the Android
configuration for their own host. A hub on **this machine** over plain http now
works on both.

### How much it will read

The app bounds what arrives, not just how long it waits for it. The hub is
trusted for *what* it says — you paired with it — but nothing between the phone
and the hub is: a reverse proxy, whatever the operator put in front of it, and
on a LAN hub reached over plain `http`, anything on the network able to write
into the connection. A read with no ceiling is a phone that stops, and on a
phone that means the process being killed rather than the app being slow.

| Ceiling | Value | What it stops |
|---|---|---|
| One reply to a tool call | 8 MiB | A body read whole into memory before anything parses it. The hub bounds `session_conversation` at roughly a megabyte of transcript tail, so this is several times more than it can produce. |
| One line on `/events` | 256 KiB | A stream that never sends `\n`. Ktor's `readLine` takes no limit at all; `readLineStrict` is the one that does. |
| One event frame | 512 Ki chars | A frame whose `data:` never ends. SSE terminates a frame with a blank line and nothing guarantees one arrives. |
| Turns kept on a session screen | 200 | The screen holding every turn of a session that has been running for hours, while the hub's own window stays at ten. Oldest go first and the screen says *Older turns are not shown*. |
| JSON nesting, anywhere off the wire | 64 | A document deep enough to exhaust the parser's stack. `kotlinx.serialization` parses by recursive descent, so nesting depth *is* stack depth. |

That last one is the only ceiling whose failure is not merely a big allocation,
and it is worth spelling out because the two platforms fail differently.
Measured on a background dispatcher, which is where the app actually parses —
Ktor delivers on one, and a secondary thread gets a fraction of a main thread's
stack:

- **Android** throws `StackOverflowError` at around ten thousand levels. That
  is an `Error`, not an `Exception`, and every parse site in the app guarded
  with `catch (e: Exception)` — so it went straight past all of them.
- **iOS** does not throw anything. The process is killed with signal 10,
  `SIGBUS`. Confirmed in CI, where it took the test binary down mid-run.

Widening those catches to `Throwable` would have fixed neither: catching a
`StackOverflowError` is unreliable wherever it is possible at all, and on
Kotlin/Native the signal never becomes a Kotlin exception. So the depth is
checked *before* the parser is handed anything, in one linear non-recursive
pass. Worth remembering for anything else here that recurses over wire data.

None of these is a guess at the largest legitimate payload — they are the point
past which nothing legitimate is happening, which is why they can be generous.
Passing one fails the *connection*, not the app: it becomes an ordinary
`HubError`, and the reconnect-with-backoff and `ready` resync that already
handle every other dropped connection handle this one. Overshooting a ceiling
costs one reconnect.

## What it does

- **Sessions** — every session across every host, grouped by host and then by
  project, with status, the one-line activity the hub already computes, and a
  filter for the ones that need a human (blocked or stuck).
- **Session** — the conversation as turns, newest at the bottom; tool calls as
  one-line summaries with a marker when one failed; a prompt box.
- **Hosts** — reachability, Claude and tmux versions, session count. Hidden
  hosts are listed and marked rather than dropped, because the app cannot
  unhide one and a hidden host can still own live sessions.
- **Settings** — which hub, which client name, the app version, and *Forget
  this hub*.
- **Work** (a hub with the work graph) — what each session is working on, as
  the hub decided it:
  - a key chip on every row (a dotted outline for a guess nobody has decided,
    struck through when the tracker stopped answering for the ticket), and a
    *By work* toggle that puts each host's work groups ahead of its projects;
  - a *My work* chip when a tracker is connected;
  - on a session, the ticket chip opens a sheet with the ticket, *why* the
    session is linked to it, and **Confirm** / **Not this** / **Clear**, plus
    *Set work…* in the menu;
  - a **Tickets** sheet (My work, Current sprint, Recent, and a search by key or
    pasted URL) with **Open** for a ticket a session is already on, **Start
    here** for one nobody is, and **Resume** for past work. A tapped ticket
    shows its acceptance criteria and the sessions that worked on it before;
  - a **Today** sheet: what is waiting on you, in progress, shipped and stale
    since local midnight, and **Copy standup** — the desktop's text;
  - **Ask for a handover** on a linked session: Claude writes a handover note
    for whoever picks the work up next, and the sheet says when it is written;
  - with two or more organisations, org chips that narrow the list and Today,
    and the org on each work heading and ticket.

  The phone never works a key out for itself and never becomes a second brain:
  it shows what the hub stamped on the row and calls the same `work` /
  `work_link` tools the desktop does. Which of those buttons exist is decided
  per connection by the hub's own `tools/list` — see *Gating on the hub's
  tools* in `skills/fleet-mobile-repo/SKILL.md`.

## What it deliberately does not do

- **No terminal.** The desktop app owns the PTY. A phone shows the conversation,
  not the raw pane.
- **No host administration.** No provisioning, no adding or removing hosts, no
  secrets, no pairing other clients. The hub refuses a client token all of
  those, so the app does not offer a button that would produce an error. The
  tools it may call are pinned by a test (`ToolsTheAppMayCallTest`), as an
  allow-list rather than a list of things someone thought to forbid.
- **No file browsing or diffs.**
- **No tracker administration, and no brief editing.** Connecting Jira is
  `work_admin`, which is master-only and which the app never names (the test
  forbids it). Starting or resuming work uses the hub's default brief; editing
  one stays on the desktop.
- **No push notifications while the app is closed.** That needs a vendor push
  service and a sender in the hub; it is its own piece of work.
- **No offline mirror.** The last snapshot stays on screen when the hub is
  unreachable, with a banner, and actions are disabled rather than hidden.
- **It never holds the master token**, only a paired client token, so a lost
  phone is revoked from the terminal without touching anything else.

---

## Building

You need a JDK 21. Everything else:

```bash
scripts/bootstrap.sh
```

It installs the Android command-line tools if they are missing, accepts the
licences, installs the two SDK packages this build needs, writes
`local.properties`, and finishes by running the test suite — so a green run
means the machine is actually ready rather than merely furnished. Re-running it
is a no-op. `--sdk-dir DIR` puts the SDK somewhere other than
`$ANDROID_HOME`/`~/Android/Sdk`.

```bash
scripts/doctor.sh
```

says what this machine can and cannot run, and what each gap costs. Most
machines are missing something on purpose — there is no Mac on the box this was
written on, and no `/dev/kvm` either — and the point is to know which checks
therefore only ever run in CI, so "it passed locally" is read with the right
amount of confidence.

By hand, if you would rather: the SDK needs **platform `android-37.0`** and
**build-tools `37.0.0`**, and `local.properties` needs `sdk.dir=…` (or export
`ANDROID_HOME`).

```bash
sdkmanager --install "platforms;android-37.0" "build-tools;37.0.0"
```

Note the minor version. `platforms;android-37` does not exist, and sdkmanager
reports that by naming the package rather than the mistake.

`compileSdk` is 37 and that is not a preference: Compose Multiplatform 1.12's
own androidx artifacts and `okhttp-android` 5.5 (which arrives through Ktor)
both fail their AAR-metadata check below it. `minSdk` is 26 and `targetSdk` 35.

### Everything

```bash
./gradlew build
```

The whole build, rather than a couple of task names, because it is the only
thing that compiles every target. `:shared:jvmTest` passes happily on a tree
where commonMain uses a JVM-only extension that Kotlin/Native does not have —
this project has already shipped one (`Map.toSortedMap()`) and the full build is
what caught it.

### Android

```bash
./gradlew :androidApp:assembleDebug
# androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

### The tests

```bash
./gradlew :shared:jvmTest            # everything testable without a device
./gradlew :shared:connectedAndroidDeviceTest   # needs an emulator or a phone
```

The second is one test class, `AndroidSecretsTest`: write the credential, read
it back, clear it, and confirm the entry is *gone* rather than blanked, plus
that neither the key nor the token is on disk in the clear. It is the only test
here that needs real hardware, because `EncryptedSharedPreferences` and the
Keystore exist on a device and nowhere else.

### iOS — needs a Mac

There is no way to build the iOS app on Linux. Kotlin/Native cross-compiles
`iosMain` to a klib, so the Kotlin is type-checked against the real UIKit,
Foundation, Security and Compose declarations; linking `Shared.framework`,
compiling the Swift and running any of it need macOS and Xcode.

```bash
open iosApp/iosApp.xcodeproj
# set a Development Team on the iosApp target, then Run
```

Xcode's *Compile Kotlin Framework* build phase calls
`./gradlew :shared:embedAndSignAppleFrameworkForXcode`, which builds the shared
module for whichever SDK and configuration Xcode is using and puts
`Shared.framework` where the target's `FRAMEWORK_SEARCH_PATHS` points. Xcode
build phases do not read your shell profile, so if `java` is not at a standard
location, set `JAVA_HOME` in that phase; the script says so rather than failing
with Gradle's own message.

Two iOS targets are declared, `iosArm64` and `iosSimulatorArm64` — devices and
the Apple-Silicon simulator. There is deliberately no `iosX64`: Compose
Multiplatform stopped publishing that variant after 1.10.3, and declaring it
breaks the *common* metadata compile for every platform. The cost is that an
Intel Mac cannot run the simulator locally.

CI now builds this too, on a macOS runner (`.github/workflows/ci.yml` →
`macos`): the same unsigned `xcodebuild … build` against the Simulator SDK,
`ARCHS=arm64` for the same reason there is no `iosX64` target, no
`DEVELOPMENT_TEAM`, no device destination, and no simulator runtime installed
on the runner. It proves the link and nothing more — see *What a Mac still
has to check* for everything it does not prove.

---

## Releasing

A signed release build needs four repository secrets (Settings → Secrets and
variables → Actions):

- `ANDROID_KEYSTORE_BASE64` — the upload keystore, base64-encoded
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Create a keystore, if there isn't one yet, with the JDK's own `keytool` —
outside the working tree, so there is nothing here for a `.gitignore` pattern
to have to catch:

```bash
keytool -genkeypair -v -keystore "${TMPDIR:-/tmp}/release.jks" -alias <your-alias> \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -i "${TMPDIR:-/tmp}/release.jks" | tr -d '\n' > "${TMPDIR:-/tmp}/release.jks.base64"
```

`TMPDIR` is commonly unset on Linux (it is a macOS default); a bare `$TMPDIR`
would then expand to nothing and leave these commands writing to `/release.jks`.
`${TMPDIR:-/tmp}` falls back to `/tmp` instead, so the files still land
outside the working tree rather than at the filesystem root.

Put `${TMPDIR:-/tmp}/release.jks.base64`'s contents in the
`ANDROID_KEYSTORE_BASE64` secret and the three passwords/alias you chose in
the other three, then delete both files. **Never commit a keystore or its base64
form** — `.gitignore` excludes `*.jks`, `*.jks.base64`, `*.keystore`,
`*.keystore.base64` and `*.p12`, but that is a second line of defense, not a
reason to create the file inside the repo in the first place.

Cut a release by pushing a tag:

```bash
git tag vX.Y.Z && git push origin vX.Y.Z
```

`.github/workflows/release.yml` then builds `:androidApp:assembleRelease`
with the tag (minus its leading `v`) as `versionName` and the workflow run
number as `versionCode`, verifies the resulting APK is actually signed, and
attaches it to a GitHub release for that tag. `versionCode` must only ever
increase for a given signing key — the Play Store and most installers refuse
an update with a `versionCode` that has gone backwards.

The workflow refuses to run rather than publish something it shouldn't:
- Any of the four secrets missing → it fails before building. There is no
  fallback to a debug key and no unsigned release is ever published.
- A tag that isn't `vX.Y.Z` (with an optional `-suffix`) → rejected before the
  tag is used for anything.
- The decoded keystore lives only under the runner's temp directory, never in
  the checked-out workspace, and is deleted at the end of the job regardless
  of whether it succeeded.

## What a Mac still has to check

**The shared code runs on iOS, and so does the app itself — far enough to
launch, and far enough to use the Keychain. What still needs a person is a
screen, a camera, and a real device.**

What changed: the `macos` job runs `:shared:iosSimulatorArm64Test` on a booted
simulator, so the whole shared suite — **313 tests** — executes on
Kotlin/Native on every push. That is the app's
entire logic layer: the address and transport rules, the SSE framing, the
conversation merge, every view model, and the token-hygiene rules.

Those tests already ran twice — under `jvmTest`, and again on the emulator,
because the device-test source-set tree pulls `commonTest` into the Android run
— but both of those are a JVM. Kotlin/Native has its own string, regex,
coroutine and memory implementations, and the shared code had only ever been
*compiled* for it. All 313 pass there exactly as they do on the JVM, which is
the first evidence that the two platforms agree about any of it.

What that leaves, and what it no longer does:

- **The Keychain round trip now happens**, in `iosApp/iosAppTests` — an XCTest
  bundle hosted by the app, so it runs inside the app's process under its own
  bundle identifier and entitlements. Eight cases: write and read back,
  replace, clear, clearing nothing, two accounts kept apart, a non-ASCII name
  across the C boundary, and a diagnostic that reports the `OSStatus` if the
  Keychain ever refuses again.

  It needs the app to be **signed**, which is the part that is easy to get
  wrong and was: unsigned, the app launches perfectly well and every Keychain
  call returns `errSecMissingEntitlement` (-34018), because entitlements come
  from signing. Ad-hoc (`CODE_SIGN_IDENTITY="-"`) is enough on a simulator and
  needs no developer account; the entitlement it synthesises is the bundle id,
  the same one a signed app gets on a device.

  `KeychainSecretsTest` in `shared/src/iosTest` still runs and still asserts the
  **refusal** path, because `simctl spawn` gives it no entitlement — which makes
  it a good test of the case the design invites: a background wake before the
  device's first unlock. The two suites cover the class from opposite ends.

This is the list, in the order a Mac should work through it. The first two are
not polish: get either wrong and the app does not work at all.

1. **`NSCameraUsageDescription` reaches the built app.** It is in
   `iosApp/iosApp/Info.plist`, and `GENERATE_INFOPLIST_FILE` is `NO` in both
   configurations so Xcode uses that file rather than synthesising one. iOS does
   not *refuse* the camera when the purpose string is missing — it terminates
   the process. On a fresh install the first screen is Pair, so the app would
   die the first time anyone tapped Scan. Check it in the built `.app`, not in
   the repository: `plutil -p iosApp.app/Info.plist | grep Camera`.
2. **The lifecycle owner exists.** `MainViewController()` returns a
   `ComposeUIViewController`, and `ContentView.swift` wraps *that* in a
   `UIViewControllerRepresentable`. On iOS, Compose Multiplatform installs
   `LocalLifecycleOwner`, the window insets and the frame clock only from inside
   that controller, and the app uses `LifecycleStartEffect` and
   `WindowInsets.safeDrawing`. Confirm it further: background the app and watch
   the hub drop a subscriber, foreground it and watch the list refill. Nothing
   here can test that CMP's iOS lifecycle actually fires.
3. **The Keychain on real hardware.** Write, read back, clear and the status
   check all run in CI now (see above), so what is left is the two a simulator
   cannot answer: that
   `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` is readable on a
   locked-screen background wake, and that the item is **absent** after
   restoring a backup onto a second device. The second is the
   security-relevant half.
4. **Core Foundation retain/release.** `Secrets.ios.kt` calls `CFRelease` by
   hand on every `Create`d object and on the `+1` reference `kSecReturnData`
   hands back. The compiler checks none of it. Run it under Instruments'
   Leaks and Zombies templates.
5. **The camera.** `QrScanner.ios.kt` is `AVCaptureMetadataOutput`. It links now
   — confirmed by both a local `xcodebuild` and the `macos` CI job — but has
   never run, and there is no camera on the machine it was written on. Check
   that it decodes a real `fleet-hub pair` QR, that denying the permission
   leaves the manual field usable, and that the preview layer is oriented and
   sized correctly. Three specific things to watch for, found by reading and
   deliberately **not** changed, because unrun camera code is the worst thing
   to edit on faith:

   - **`startRunning()`, `unavailable(...)` and the double stop are fixed**,
     and the fix is the one a device would have prompted. The capture graph is
     started from a `LaunchedEffect` on `Dispatchers.Default` instead of inside
     `UIKitView`'s `factory`, so the main queue is not blocked for as long as
     the camera takes to come up; the failure path reports through an effect
     rather than writing a `StateFlow` synchronously during composition; and
     the session is stopped in one place, off the main queue. What a device
     still has to confirm is that the preview layer still attaches when the
     session starts *after* the view is built rather than during it — the
     reordering is the part no test here can see.

     `QrScannerCaptureTest` now executes `startCapturing` on the simulator,
     which has no camera, so it takes the first `return false`. That is a
     narrow path and it is the only one reachable without hardware, but it
     moves the file from "has never run" to "links AVFoundation and declines a
     machine with no camera instead of trapping on it" — and the failure mode
     for a wrong cinterop binding is an uncatchable Objective-C trap, not
     something a test could otherwise report.

6. **Layout.** `ContentView` passes `.ignoresSafeArea(.all)` so the Compose view
   owns the window and insets itself, matching `enableEdgeToEdge()` on Android.
   Check the notch, the home indicator, and that the prompt box rises with the
   keyboard exactly once rather than twice.
7. **A hub on the LAN over plain HTTP.** `NSLocalNetworkUsageDescription` and
   `NSAllowsLocalNetworking` are in the plist for this. Nobody has seen the
   local-network permission dialog appear, or confirmed that Ktor's Darwin
   engine reaches `http://192.168.x.x:8899` once it has.
8. **The app icon.** `Assets.xcassets/AppIcon.appiconset` declares the slot and
   holds no image. Xcode will warn.

And the parts that need a **device or emulator on either platform**, or a
**live hub**:

- **One screen has now been rendered, once, on Android.**
  `androidApp/src/androidTest` launches `MainActivity` with a `claudefleet:`
  URL on the emulator and asserts the Pair screen comes back holding the code
  and the hub — the first Compose this repository has drawn anywhere. It proves
  the composition runs and recomposes on a state change from outside it.

  It proves nothing else, and the list of what is still open is almost
  unchanged: every other screen compiles for Android and both iOS targets and
  has never been drawn. Whether the grouped list scrolls, whether the prompt
  box clears a soft keyboard, whether auto-scroll behaves when someone has
  scrolled up, and whether the status chip colours are legible in both themes
  are all still open, and nothing has rendered on iOS at all.
- **`AndroidSecrets` now executes on every push.** It is the only thing the app
  persists — `EncryptedSharedPreferences` over a Keystore master key — and until
  2026-09-19 it had never run a line on real hardware, because the machine it was
  written on has no `/dev/kvm` and cannot start an emulator. It first ran in CI
  on an API 34 emulator that day: **4 tests green**, including that neither the
  key nor the token is stored in the clear, inside a device run of 278 tests with
  no failures. The job was previously allowed to fail without failing CI, so that
  a first red would not be mistaken for a code fault; that allowance has been
  removed now it has been seen to pass. A red there now means the secure store
  broke.
- **The Android camera path.** Two mutations survive an ad-hoc mutation sweep
  on purpose — deleting the permission request, and wiring the Scan button to
  nothing — because no headless JVM test can render a composable or grant a
  permission. The sweep's own script was never checked in.
- **No live hub has ever been contacted, by anyone, at any point.** Every wire
  format in this app was read out of the `claude-fleet` Rust source and pinned
  with a `MockEngine`. "Matches the source" is not "matches the server".

---

## Layout

```
shared/          Kotlin Multiplatform: models, hub client, repository,
                 view models and the whole Compose UI
  commonMain/    everything shared
  commonTest/    everything testable without a device
  jvmTest/       the same, plus the source scans that guard the two hosts
  androidMain/   EncryptedSharedPreferences, CameraX + ZXing scanner
  androidDeviceTest/  the one instrumentation test
  iosMain/       Keychain, AVFoundation scanner, the ComposeUIViewController
androidApp/      one Activity, the manifest, permissions and icons
iosApp/          a SwiftUI App and a UIViewControllerRepresentable. Two files.
docs/            the design this was built from, and what was measured
```

If a change to the app is being made in `androidApp/` or `iosApp/`, it is
probably in the wrong place. Those two directories exist to give the shared code
a process to live in.

## Documentation

- [`docs/2026-09-18-fleet-mobile-design.md`](docs/2026-09-18-fleet-mobile-design.md)
  — the approved design.
- [`docs/measurements.md`](docs/measurements.md) — the things that were measured
  rather than assumed: what the APK actually asks for, what it actually weighs,
  what a deferred dependency would cost, and a Gradle flake investigation.

## Licence

MIT — see [LICENSE](LICENSE). Same as `claude-fleet`, which this app is a client of.

## Working on this repo with an AI assistant

`skills/fleet-mobile-repo/SKILL.md` is a Claude Code skill holding the things
that are not in this README: which test host proves what, how to get CI to run
without opening a pull request, the traps that have cost real time here, and the
conventions a change has to keep. Install it with

```bash
cp -r skills/fleet-mobile-repo ~/.claude/skills/
```

It is kept in the repository rather than only in a home directory so that it is
reviewed with the code it describes — a skill that drifts from the build is
worse than none, because it is believed.
