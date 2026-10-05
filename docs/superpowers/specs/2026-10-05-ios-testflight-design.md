# iOS: from "links in CI" to TestFlight

**Date:** 2026-10-05
**Status:** Approved design, awaiting implementation plan
**Scope:** `martin-janci/fleet-mobile` only. No change to `claude-fleet`, the
hub, or the hub contract.

## Why

The iOS target has existed since the first design
(`docs/2026-09-18-fleet-mobile-design.md`): the Swift host, the Keychain store,
the camera scanner and the `claudefleet:` link all compile, link, and run
their tests on a simulator in CI. It has never been on a phone. Every release
since v0.2.41 ships an Android APK and nothing for iOS, and the README's *What
a Mac still has to check* is a list nobody has worked through.

This spec takes the existing target to a TestFlight build per release, works
through that list on a real iPhone, and gives iOS the best "a session needs
you" alert it can have without a push service.

## Decisions

| | Decision | Rejected |
|---|---|---|
| D1 | **Ship the existing KMP target.** All screens stay in `shared/commonMain`. | A separate SwiftUI app; a Tauri build of the desktop app. |
| D2 | **TestFlight, internal testing**, through the paid Apple Developer Program. | Free-team sideloading (7-day expiry, 3-app limit); ad-hoc IPAs. |
| D3 | **Best-effort background alerts** through `BGAppRefreshTask` and local notifications. No hub change. | Foreground only; APNs push now. |
| D4 | **APNs is the next sub-project**, as the pager spec's phase 3 already says ("iOS via an APNs relay in the hub, later"). This spec adds nothing that push would have to undo. | — |
| D5 | **The background-task glue lives in Swift**, about 40 lines; the check itself is shared Kotlin. | Registering `BGTaskScheduler` from Kotlin through cinterop: launch-time registration and Obj-C block lifetimes in Kotlin/Native are where this repo has been hurt before (an `object` extending an Obj-C class fails only at link time). |
| D6 | **No install-expiry UI.** A TestFlight build lives 90 days and releases are roughly weekly; the TestFlight app warns on its own. | Reading `embedded.mobileprovision` for a banner and a reminder (only needed for D2's rejected free-team route). |

## Non-goals

- Push notifications (D4).
- App Store release, external TestFlight testers, beta review.
- iPad-specific layout. The app runs on iPad as it does today; nothing is tuned for it.
- Widgets, Live Activities, a Share extension, Siri.
- Automating the steps that need a person: enrolling in the program, creating the app record, generating the API key.

## Prerequisites (the operator, once)

These need an Apple ID's owner and a payment, so they are the operator's, not
an agent's:

1. Enrol in the **Apple Developer Program** (individual). Approval is usually one to two days.
2. In **App Store Connect**, create the app record with bundle ID
   `dev.claudefleet.mobile`. If the ID is taken, choose another and record it
   in this spec, in `iosApp.xcodeproj` and in the release job together. A
   bundle ID change later loses every pairing (see *Pairing survives*).
3. Create an **App Store Connect API key** with the *App Manager* role. Store
   four repository secrets on `fleet-mobile`: `ASC_KEY_ID`, `ASC_ISSUER_ID`,
   `ASC_KEY_P8` (the key file's contents) and `APPLE_TEAM_ID`.
4. Add yourself as an internal tester and install the TestFlight app on the phone.

## 1. Signing and distribution

### Local: the device loop

- **`iosApp/Signing.xcconfig`** (committed) is set as the base configuration
  of the `iosApp`, `iosAppTests` and `iosAppUITests` targets, in both
  configurations. Its only content is `#include? "Signing.local.xcconfig"`.
  The `?` makes the include optional, so without the local file every build
  setting is exactly what the project has today.
- **`iosApp/Signing.local.xcconfig`** (gitignored) holds `DEVELOPMENT_TEAM`.
  CI never has it, so the existing `macos` CI job's unsigned simulator build
  is unchanged.
- **`scripts/ios-device.sh`** builds Debug for a connected iPhone and runs it:
  1. Resolves Xcode. On the development Mac it lives on an external volume
     whose exec can hang, so the script probes `xcodebuild -version` with a
     timeout and says so instead of hanging. Resolves a JDK the same way the
     Xcode build phase does.
  2. On first run, writes `Signing.local.xcconfig` with the Team ID read from
     the keychain's *Apple Development* identity, and refuses to change an
     existing `DEVELOPMENT_TEAM` without `--team`.
  3. `xcodebuild -configuration Debug -allowProvisioningUpdates -destination id=<udid> build`.
  4. `xcrun devicectl device install app`, then `… device process launch`.
  5. Detects the two first-run states only the phone's owner can clear —
     Developer Mode off, developer not trusted — and prints which one and
     where it is in Settings.

  A Debug build keeps `autoPairFromLink` on, as on Android, so a
  `claudefleet:` link pairs the development build without a tap.

### CI: TestFlight on every release tag

A new job, **`ios`**, in `.github/workflows/release.yml`, on `macos-15`,
triggered by the same `v*` tag that builds the APK. It is independent of the
APK job: a failure in one does not stop the other.

1. **Version.** The tag is validated by the same rule as the APK job, through
   `env:`, never interpolated. `MARKETING_VERSION` is `X.Y.Z`.
   `CURRENT_PROJECT_VERSION` is `X*10000 + Y*100 + Z`, which is monotonic and
   reproducible; the step fails if `Y` or `Z` is 100 or more. A tag with a
   `-suffix` builds and exports but **does not upload**, because it would
   collide with its release's build number.
2. **Archive.** `xcodebuild archive -configuration Release` with automatic
   signing driven by the API key: `-allowProvisioningUpdates
   -authenticationKeyPath … -authenticationKeyID … -authenticationKeyIssuerID …`,
   with `DEVELOPMENT_TEAM` from the secret. Apple creates and holds the
   distribution certificate and profile. **No `.p12`, no profile and no
   keychain import exist in the repository or the secrets.** The `.p8` is
   written to `$RUNNER_TEMP` with mode 600 and deleted in an `always()` step.
3. **Export and upload.** `xcodebuild -exportArchive` with an
   `ExportOptions.plist` (committed, `method: app-store-connect`,
   `destination: upload`). One step, no `altool`.
4. **`workflow_dispatch`** runs steps 1–3 with the upload skipped (export to
   a local destination instead), so signing can be proven before the first
   real tag.

`Info.plist` gains `ITSAppUsesNonExemptEncryption = false`: the app uses only
HTTPS through the system, which is exempt, and without the key every build
waits in App Store Connect for the export-compliance question.

`scripts/release-mobile.sh` in `claude-fleet` does not change: it pushes the
tag and the tag now produces both artifacts.

### Pairing survives

The paired token is a Keychain item whose access group is
`<TeamID>.dev.claudefleet.mobile`. A development build from the device loop
and a TestFlight build are signed by the same team, so installing one over
the other keeps the pairing. Changing the bundle ID or the team does not, and
there is no migration for it.

## 2. Background alerts

### Shared: `NeedsYouCheck`

A new class in `shared/commonMain/.../notify/NeedsYouCheck.kt`:

```kotlin
class NeedsYouCheck(session: AppSession, prefs: Prefs, poster: AlertPoster) {
    @Throws(CancellationException::class)
    suspend fun once()
}

interface AlertPoster {
    fun post(alert: NeedsYouAlert)
    fun withdraw(sessionId: Long)
}
```

`once()`:

1. Restores the credential. Not paired → returns.
2. One `listSessions()`, bounded by a 20-second timeout (iOS gives a refresh
   task about 30).
3. `needsYouAlerts(seen, rows)` — the existing pure function — for alerts;
   for resolutions, the same rule `needsYouEvents` applies (a reason before,
   none now).
4. Posts and withdraws through the poster. Both are idempotent per session
   (a repost replaces by identifier), so a run cut short and repeated is safe.
5. Only then writes the new seen set through `Prefs` under `needs_you_seen`,
   encoded with the existing `encodeSeen`.

| Situation | Result |
|---|---|
| No seen set stored yet | The look becomes the baseline; nothing is posted. Same rule as `needsYouEvents`. |
| `401` | `AppSession`'s existing 401 rule unpairs. Nothing is posted; the seen set is not written. |
| Network error, timeout, hub unreachable | No-op. The old seen set is kept, so the next run compares against the last successful look. |
| Keychain unavailable (`errSecNotAvailable`, a wake before first unlock) | No-op, as above. |
| Cancelled by the task's expiration | Propagates `CancellationException`; the seen set is written only after posting succeeds, so nothing is half-applied. |

`once()` throws nothing else to Swift: every other failure is caught and
reported as a no-op, because an uncaught Kotlin exception across the
`@Throws` boundary terminates the process.

### Shared: the foreground writes the seen set too

On Android the service watches all the time, so its seen set is always
current. On iOS nothing watches while the app is open, so without this the
first background run after closing the app would announce every session the
person just looked at.

While the paired screens are started (the same `LifecycleStartEffect` that
starts the event stream) **and** `notifier.enabled` is true **and**
`notifier.sharesSeenWithForeground` is true, `App` collects
`needsYouEvents(fleet, remembered = prefs[needs_you_seen], onSeen = write)`,
posting nothing and calling `poster.withdraw` for each `NeedsYouResolved`.
`sharesSeenWithForeground` is a new `BackgroundNotifier` property, `false` by
default and on Android, whose service keeps its own seen set in its own
`SharedPreferences`.

### iOS platform code (`iosMain`)

- **`IosAlertPoster`** posts a `UNNotificationRequest` with identifier
  `needs-you-<sessionId>` (a session that needs you again replaces its own),
  `threadIdentifier = "needs_you"` (iOS groups them; no summary to maintain),
  title and body from the alert, and `userInfo["sessionId"]`. `withdraw`
  removes the delivered notification by identifier. The mapping from alert
  to content is a pure function, tested apart from the notification center.
- **`IosBackgroundNotifier`**: `supported = true`,
  `sharesSeenWithForeground = true`, the setting in `NSUserDefaults`.
  Turning it on submits a refresh request. Turning it off cancels the request
  and removes every delivered `needs_you` notification. A `submit` failure
  (always `unavailable` on a simulator) is logged and does not turn the toggle
  off.
- **`rememberNotificationPermission`** calls
  `UNUserNotificationCenter.requestAuthorization([.alert, .sound, .badge])`
  instead of answering yes.
- **`note`**: a new nullable `BackgroundNotifier.note: StateFlow<String?>`,
  `null` on Android, shown under the Settings toggle. On iOS:
  - normally: *"iOS decides when to check — often within the hour, sometimes
    not at all. Keep the app open for prompt alerts."*
  - notifications denied in iOS Settings: *"Notifications are off for Fleet
    in iOS Settings."*
  - `UIApplication.backgroundRefreshStatus` not available (Background App
    Refresh off, Low Power Mode, a restriction): *"Background App Refresh is
    off, so iOS will not check while the app is closed."*

  Recomputed when the app comes to the foreground.

### The Swift entry points

`MainViewController.kt` gains two top-level functions beside `onPairLink`,
both working on the one process-wide `iosContainer`, which a background
launch builds lazily without any UI:

- `onOpenSession(sessionId: Long)` → `iosContainer.onOpenSession`.
- `@Throws(CancellationException::class) suspend fun needsYouCheckOnce()` →
  `NeedsYouCheck(iosContainer.session, iosContainer.prefs, IosAlertPoster()).once()`.
  Swift sees it as `async throws`.

### Swift host

`iosApp/iosApp/AppDelegate.swift`, attached with
`@UIApplicationDelegateAdaptor` in `iOSApp.swift`:

- In `application(_:didFinishLaunchingWithOptions:)`, which is where iOS
  requires it: register the task identifier `dev.claudefleet.mobile.needs-you`,
  and become the `UNUserNotificationCenter` delegate.
- The task handler first submits the next request (`earliestBeginDate` 15
  minutes out), then runs `needsYouCheckOnce()` in a Swift `Task`. `expirationHandler`
  cancels that `Task`. Either way the handler calls `setTaskCompleted`.
- `willPresent`: no banner while the app is on screen. The list already shows
  it, the same rule as Android's "on screen, no second word".
- `didReceive`: reads `sessionId` and calls `MainViewControllerKt.onOpenSession`,
  which hands it to the existing `AppContainer.onOpenSession`.
- On `scenePhase == .background`, submit a request if alerts are on, so a
  request exists even if the last run's resubmission failed.

`Info.plist` gains `BGTaskSchedulerPermittedIdentifiers` (that one identifier)
and `UIBackgroundModes` (`fetch`). Neither needs a paid entitlement, and no
`aps-environment` entitlement is added (D3).

The identifier is spelled in three places — Kotlin, Swift, `Info.plist` — and
a source-scan test holds them equal.

## 3. The device checklist

The README's *What a Mac still has to check*, in its own order, on a real
iPhone with a TestFlight build and a hub paired as client `iphone`:

1. The camera purpose string is in the built `.app`.
2. Lifecycle: backgrounding drops the hub subscriber; foregrounding refills the list.
3. Keychain: readable on a locked-screen background wake; absent after
   restoring a backup onto another device, if one is to hand.
4. `Secrets.ios.kt` under the Leaks and Zombies templates (`xctrace`).
5. Camera: a real `fleet-hub pair` QR decodes; denying the permission leaves
   manual entry usable; the preview is oriented and sized correctly.
6. Layout: notch, home indicator, the prompt box rises with the keyboard once.
7. A LAN hub over plain HTTP: the local-network dialog appears and the Darwin engine connects.
8. App icon: rendered from Android's vector foreground at 1024×1024 (required
   for an App Store Connect upload, not just a warning).

Plus, for this spec:

9. A background run, forced with lldb's
   `e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"dev.claudefleet.mobile.needs-you"]`:
   a waiting session produces a notification, and tapping it opens that session.
10. A TestFlight build installed over a development build keeps the pairing.
11. One night with alerts on: how many times iOS ran the task, recorded with
    timestamps. This is the input to the APNs decision (D4).

Each failure is fixed separately, with a test where any host can hold one.
The README section is rewritten to say what the device showed, with dates,
and the items that are now proven move out of it.

## Testing

| Host | What |
|---|---|
| `commonTest` (JVM and `iosSimulatorArm64Test`) | `NeedsYouCheck` with `MockEngine`, a fake poster and in-memory `Prefs`: baseline first run; new reason alerts; same reason does not; resolved withdraws; 401 unpairs and posts nothing; network error, timeout and an unavailable Keychain keep the old seen set; a seen set written by the foreground collector suppresses a repeat. The foreground collector itself: writes, withdraws, posts nothing. |
| `jvmTest` source scans | The task identifier matches across Kotlin, Swift and `Info.plist`; `UIBackgroundModes` has `fetch`; `ITSAppUsesNonExemptEncryption` is `false`; no `aps-environment` entitlement; `Signing.xcconfig`'s include is optional; no `.p8`, `.p12` or `.mobileprovision` is tracked; `Signing.local.xcconfig` is ignored; the `ios` release job reads the key only from secrets, deletes it in an `always()` step, and uploads only for a tag without a suffix. |
| `iosTest` (Kotlin/Native) | Alert-to-content mapping; seen-set round trip through `IosPrefs`. |
| `iosAppTests` (XCTest in the app) | `IosAlertPoster` adds and removes a delivered notification; a refresh `submit` that fails with `unavailable` leaves the toggle on. |
| CI | `workflow_dispatch` on the `ios` job: archive, sign and export without upload. |
| Device | The checklist above. |

## What cannot be proven

When, or whether, iOS runs the refresh task. It is Apple's heuristic, driven
by how often the app is opened, Low Power Mode, battery and network, and no
test can hold it. Checklist item 11 measures it once. If it turns out to be
rare, the answer is APNs (D4), not a cleverer schedule.

## Out of this spec, next

- **APNs** — a hub-side sender and device-token registration, as its own
  sub-project, with the measurement from item 11.
- The pager spec's later phases on iOS: Live Activities, notification actions.
