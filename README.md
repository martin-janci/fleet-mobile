# fleet-mobile

A phone client for [`claude-fleet`](https://github.com/martin-janci/claude-fleet).

Every Claude Code session on every machine, on a phone: what each one is doing,
which ones are blocked, what an agent just said, and a box to answer it with.
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
and disables the prompt box rather than making a call it knows would be refused.

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
  trying this. The short version: use HTTPS. Android blocks cleartext outright
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
  destination unless the app ships an exception. **This app ships none**, so a
  plain-`http` hub does not work on Android as built — including on the LAN.
  The fix is HTTPS on the hub.

  If you genuinely need cleartext to one LAN host, add a
  [network security configuration](https://developer.android.com/privacy-and-security/security-config)
  naming that host and wire it up in the manifest. Note that the format matches
  domains and IP *literals*, not CIDR ranges, so "all of 192.168/16" cannot be
  expressed — you name the host you actually use. **Do not set
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
configuration for their own host.

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

## What it deliberately does not do

- **No terminal.** The desktop app owns the PTY. A phone shows the conversation,
  not the raw pane.
- **No host administration.** No provisioning, no adding or removing hosts, no
  secrets, no pairing other clients. The hub refuses a client token all of
  those, so the app does not offer a button that would produce an error. The
  tools it may call are pinned by a test (`ToolsTheAppMayCallTest`), as an
  allow-list rather than a list of things someone thought to forbid.
- **No file browsing or diffs.**
- **No push notifications while the app is closed.** That needs a vendor push
  service and a sender in the hub; it is its own piece of work.
- **No offline mirror.** The last snapshot stays on screen when the hub is
  unreachable, with a banner, and actions are disabled rather than hidden.
- **It never holds the master token**, only a paired client token, so a lost
  phone is revoked from the terminal without touching anything else.

---

## Building

You need a JDK 21 and the Android SDK. Point Gradle at the SDK with a
`local.properties` in the repository root (it is git-ignored):

```
sdk.dir=/path/to/Android/Sdk
```

or export `ANDROID_HOME`. The SDK needs **platform `android-37.0`** and
**build-tools `37.0.0`**:

```bash
sdkmanager --install "platforms;android-37.0" "build-tools;37.0.0"
```

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

**The shared code now runs on iOS. Nothing that needs a screen, a camera or an
app bundle does, and no Compose has ever been rendered on any platform.**

What changed: the `macos` job runs `:shared:iosSimulatorArm64Test` on a booted
simulator, so the whole shared suite — **313 tests** — executes on
Kotlin/Native on every push rather than only on the JVM. That is the app's
entire logic layer: the address and transport rules, the SSE framing, the
conversation merge, every view model, and the token-hygiene rules. All 313 pass
on Kotlin/Native exactly as they do on the JVM, which is the first evidence
that the two platforms agree about any of it. Before this they were only ever
*compiled* for iOS.

One gap is worth naming precisely, because it looks like it should have closed
with the rest:

- **The Keychain round trip still has not happened.** The Kotlin plugin runs a
  Kotlin/Native test through `simctl spawn`, so the binary is not an installed
  app, holds no `keychain-access-group` entitlement, and `securityd` refuses
  every request with `errSecNotAvailable` (-25291). `KeychainSecretsTest`
  therefore asserts the **refusal** path: that an unreadable store reads as
  "not paired" rather than crashing the app on every cold start, and that a
  write or a clear that cannot land throws rather than lying about it. That
  half had no coverage anywhere and is a real production case — a background
  wake before the device's first unlock is exactly what
  `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` invites. Storing and
  reading a real item back needs an XCTest target hosted by `iosApp`. This is
  why Android has the round trip and iOS does not: an Android instrumentation
  test *is* installed as an app, and a `simctl spawn`-ed executable is not.

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
3. **The Keychain round trip.** `KeychainSecrets` now executes in CI, but only
   its refusal path — see above for why a `simctl spawn`-ed binary cannot hold
   a Keychain item. So the storing half is still unrun: write, read back,
   clear, and confirm `SecItemDelete`'s status is checked rather than
   discarded. Then the two that matter:
   `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` is readable on a
   locked-screen background wake, and the item is **absent** after restoring a
   backup onto a second device. The second is the security-relevant half.
   Adding an XCTest target to `iosApp` would move the first three of these into
   CI and leave only the last two needing a person.
4. **Core Foundation retain/release.** `Secrets.ios.kt` calls `CFRelease` by
   hand on every `Create`d object and on the `+1` reference `kSecReturnData`
   hands back. The compiler checks none of it. Run it under Instruments'
   Leaks and Zombies templates.
5. **The camera.** `QrScanner.ios.kt` is `AVCaptureMetadataOutput`. It links now
   — confirmed by both a local `xcodebuild` and the `macos` CI job — but has
   never run, and there is no camera on the machine it was written on. Check
   that it decodes a real `fleet-hub pair` QR, that denying the permission
   leaves the manual field usable, and that the preview layer is oriented and
   sized correctly.
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

- **No Compose has ever been rendered, anywhere.** Every screen compiles for
  Android and both iOS targets and has never been drawn. Whether the grouped
  list scrolls, whether the prompt box clears a soft keyboard, whether
  auto-scroll behaves when someone has scrolled up, and whether the status chip
  colours are legible in both themes are all open.
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
