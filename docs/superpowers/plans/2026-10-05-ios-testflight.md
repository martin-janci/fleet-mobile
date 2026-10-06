# iOS TestFlight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Take fleet-mobile's existing iOS target to a signed TestFlight build on every release tag, give it best-effort "a session needs you" alerts through `BGAppRefreshTask`, and prove it on a real iPhone.

**Architecture:** Everything testable stays in `shared/commonMain` (`NeedsYouCheck`, the foreground seen-writer, the notification content, the Settings note). `shared/iosMain` adds the platform glue (`UNUserNotificationCenter`, `BGTaskScheduler` submit/cancel, the permission prompt, two Swift entry points). The Swift host gains one file, `AppDelegate.swift`, for the two things iOS insists happen there: task registration at launch and the notification delegate. Signing is a committed optional `#include?` xcconfig for local builds and an App Store Connect API key in a separate `testflight.yml` workflow.

**Tech Stack:** Kotlin Multiplatform 2.x, Compose Multiplatform, Ktor (MockEngine in tests), kotlinx.coroutines, Kotlin/Native iOS platform libs (`platform.UserNotifications`, `platform.BackgroundTasks`, `platform.UIKit`), SwiftUI, Xcode 26 `xcodebuild`/`devicectl`, GitHub Actions `macos-15`.

**Spec:** `docs/superpowers/specs/2026-10-05-ios-testflight-design.md`

## Global Constraints

- Repository: `martin-janci/fleet-mobile`, worktree `.claude/worktrees/ios-testflight`, branch `feat/ios-testflight`. Every git command runs with `-C <worktree>` or from inside it. No pull, push, rebase, checkout or stash by subagents.
- No change to `claude-fleet`, the hub, or the hub contract.
- Bundle ID `dev.claudefleet.mobile`. Never changed by a script.
- Task identifier `dev.claudefleet.mobile.needs-you`, spelled identically in Kotlin, Swift and `Info.plist`.
- Notification identifiers `needs-you-<sessionId>`, thread `needs_you`, `userInfo` key `sessionId`.
- Prefs key for the iOS seen set: `needs_you_seen`. NSUserDefaults key for the toggle: `needs_you`.
- Background check: one `listSessions()`, timeout **20 seconds**; next request `earliestBeginDate` **15 minutes** out.
- Build number: `X*10000 + Y*100 + Z`; `Y` and `Z` must be below 100. A tag with a `-suffix` never uploads. `workflow_dispatch` never uploads.
- No `.p8`, `.p12`, `.mobileprovision` or `Signing.local.xcconfig` is ever committed. No `aps-environment` entitlement.
- Settings note copy, verbatim:
  - `iOS decides when to check — often within the hour, sometimes not at all. Keep the app open for prompt alerts.`
  - `Notifications are off for Fleet in iOS Settings.`
  - `Background App Refresh is off, so iOS will not check while the app is closed.`
- Every test function name is `lower_case_with_underscores` and annotated `@Test` (`EveryTestIsRunTest` enforces it).
- Exceptions crossing into Swift terminate the process: no Kotlin function called from Swift may throw.
- **Xcode lives on the CargoSD card** on the development Mac. Before any `xcodebuild`, `iosSimulatorArm64Test` or `devicectl` step: `export DEVELOPER_DIR=/Volumes/CargoSD/Applications/Xcode-26.5.0.app/Contents/Developer` and probe `timeout 30 xcodebuild -version`. If the card is not mounted, those steps are proven in CI instead (Task 11), and the task report says so.
- `./gradlew :shared:jvmTest` runs anywhere and is the per-task gate; run the **whole** of it before each commit, not only the filtered test.

## Spec deltas (applied to the spec in this plan's first commit)

1. The TestFlight job is its own workflow, `.github/workflows/testflight.yml`, not a job in `release.yml`. `ReleaseWorkflowTest.it_triggers_only_on_pushed_v_tags` forbids `workflow_dispatch` in `release.yml`, and the spec wants a dispatch dry run.
2. Swift does not `await` a suspend function. Kotlin exports `startNeedsYouCheck(onDone: (Boolean) -> Unit): NeedsYouRun` with a `cancel()`, because cancelling a Swift `Task` does not cancel a Kotlin coroutine behind a completion handler, and an exported suspend function must be called on the main thread.
3. `BackgroundNotifier.sharesSeenWithForeground: Boolean` becomes `BackgroundNotifier.poster: AlertPoster?`. Non-null means "no always-on watcher here: the open app keeps the seen set, and withdraws through this". One property instead of two.
4. The `iosAppTests` case for `IosAlertPoster` is dropped: a simulator XCTest cannot grant notification permission, so delivered notifications cannot be observed. The content mapping is tested in `commonTest` (which also runs on Kotlin/Native), and posting is checklist item 9.

## File map

| File | Responsibility | Task |
|---|---|---|
| `iosApp/Signing.xcconfig` | Optional include of the local signing file | 1 |
| `iosApp/iosApp.xcodeproj/project.pbxproj` | Base config, drop `DEVELOPMENT_TEAM = ""`, add `AppDelegate.swift` | 1, 9 |
| `.gitignore` | Local signing file, `.p8`, `.mobileprovision` | 1 |
| `shared/src/jvmTest/.../host/IosSigningTest.kt` | Signing scans | 1 |
| `scripts/ios-device.sh` | Build, install, launch on a connected iPhone | 2 |
| `shared/src/jvmTest/.../host/IosDeviceScriptTest.kt` | Script scans | 2 |
| `scripts/render-ios-icon.swift`, `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/*` | The 1024 px icon | 3 |
| `shared/src/jvmTest/.../host/IosAppIconTest.kt` | PNG exists, 1024², no alpha | 3 |
| `iosApp/ExportOptions.plist`, `.github/workflows/testflight.yml`, `iosApp/iosApp/Info.plist` (`ITSAppUsesNonExemptEncryption`) | TestFlight | 4 |
| `shared/src/jvmTest/.../host/TestFlightWorkflowTest.kt` | Workflow scans | 4 |
| `shared/src/commonMain/.../notify/NeedsYouContent.kt` | Pure notification content | 5 |
| `shared/src/commonMain/.../notify/NeedsYouCheck.kt` | `AlertPoster`, seen-set prefs, `NeedsYouCheck`, `keepSeenWhileOpen` | 5, 6 |
| `shared/src/commonTest/.../notify/NeedsYouCheckTest.kt` | Their tests | 5, 6 |
| `shared/src/commonMain/.../notify/BackgroundNotifier.kt` | `note`, `poster`, `refreshNote`, `backgroundNote` | 7 |
| `shared/src/commonMain/.../App.kt` | Foreground seen-writer in `FleetRoute` | 7 |
| `shared/src/commonMain/.../ui/SettingsScreen.kt` | Note under the switch | 7 |
| `shared/src/iosMain/.../notify/IosAlertPoster.kt`, `IosBackgroundNotifier.kt`, `NeedsYouRefresh.kt`, `NotificationPermission.ios.kt` | iOS glue | 8 |
| `shared/src/iosMain/.../MainViewController.kt` | Notifier wiring, Swift entry points | 8 |
| `iosApp/iosApp/AppDelegate.swift`, `iOSApp.swift`, `Info.plist` | Swift host | 9 |
| `shared/src/jvmTest/.../host/IosHostTest.kt`, `IosBackgroundTaskTest.kt` | Host scans | 9 |
| `README.md` | What the device showed | 10 |

`...` is `kotlin/dev/claudefleet/mobile`.

---

### Task 0: Record the spec deltas

**Files:**
- Modify: `docs/superpowers/specs/2026-10-05-ios-testflight-design.md`
- Create: `docs/superpowers/plans/2026-10-05-ios-testflight.md` (this file)

- [x] **Step 1: Apply the four deltas to the spec** (done when the plan was written)

In the spec:
- *CI: TestFlight on every release tag*: replace "A new job, **`ios`**, in `.github/workflows/release.yml`" with "A new workflow, **`.github/workflows/testflight.yml`** (job `testflight`)", and add after its first paragraph: "It is a separate file because `release.yml` is held by `ReleaseWorkflowTest` to tag pushes only, and the dry run below needs `workflow_dispatch`."
- *The Swift entry points*: replace the `needsYouCheckOnce()` bullet with: "`startNeedsYouCheck(onDone: (Boolean) -> Unit): NeedsYouRun` launches `NeedsYouCheck.once()` on `Dispatchers.Default` and calls `onDone(true)` when it finishes, `onDone(false)` when cancelled; `NeedsYouRun.cancel()` cancels it. Swift never awaits a Kotlin suspend function: cancelling a Swift `Task` would not reach the coroutine, and exported suspend functions must start on the main thread." Also add "`scheduleNeedsYouRefreshIfEnabled()`" as a third bullet, and in *Swift host* replace "runs `needsYouCheckOnce()` in a Swift `Task`. `expirationHandler` cancels that `Task`" with "calls `startNeedsYouCheck`; `expirationHandler` calls the returned run's `cancel()`".
- *Shared: the foreground writes the seen set too*: replace `notifier.sharesSeenWithForeground` is true with `notifier.poster` is non-null, and the sentence defining `sharesSeenWithForeground` with: "`poster` is a new nullable `BackgroundNotifier` property, `null` by default and on Android, whose service keeps its own seen set in its own `SharedPreferences`."
- *iOS platform code*: in the `IosBackgroundNotifier` bullet replace "`sharesSeenWithForeground = true`" with "`poster` = its `IosAlertPoster`".
- *Testing* table, `iosAppTests` row: replace with "A refresh `submit` that fails with `unavailable` leaves the toggle on. (`IosAlertPoster` is not tested here: a simulator XCTest cannot grant notification permission, so nothing is delivered to observe. Its content mapping is in `commonTest`; posting is checklist item 9.)"

- [x] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-10-05-ios-testflight-design.md docs/superpowers/plans/2026-10-05-ios-testflight.md
git commit -m "docs(plan): iOS TestFlight implementation plan, and the spec deltas it found"
```

Done in the same commit as this plan.

---

### Task 1: Local signing that CI never sees

**Files:**
- Create: `iosApp/Signing.xcconfig`
- Modify: `iosApp/iosApp.xcodeproj/project.pbxproj`
- Modify: `.gitignore`
- Test: `shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/IosSigningTest.kt`

**Interfaces:**
- Produces: `iosApp/Signing.local.xcconfig` is the one place `DEVELOPMENT_TEAM` lives locally (Task 2 writes it).

Why the pbxproj lines go: a target's own build settings beat any xcconfig. Every target configuration has `DEVELOPMENT_TEAM = "";`, which would silently override the included team.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Local builds are signed from a file nobody commits; CI builds are not signed
 * at all, exactly as before. Both halves are a few lines of configuration that
 * nothing else would notice going wrong.
 */
class IosSigningTest {

    private val project: String by lazy { Repo.file("iosApp/iosApp.xcodeproj/project.pbxproj").readText() }
    private val gitignore: String by lazy { Repo.file(".gitignore").readText() }

    @Test
    fun the_signing_include_is_optional() {
        val xcconfig = Repo.file("iosApp/Signing.xcconfig").readText()
        assertTrue(
            "#include? \"Signing.local.xcconfig\"" in xcconfig,
            "without the '?', every checkout without the local file fails to build, CI first",
        )
    }

    @Test
    fun both_project_configurations_are_based_on_it() {
        val base = Regex("""baseConfigurationReference = 5FE0A10000000000000000E0 /\* Signing.xcconfig \*/;""")
        assertEquals(2, base.findAll(project).count(), "the project's Debug and Release must both include Signing.xcconfig")
    }

    @Test
    fun no_target_pins_an_empty_team() {
        assertTrue(
            "DEVELOPMENT_TEAM = \"\";" !in project,
            "a target's own DEVELOPMENT_TEAM beats the xcconfig, so an empty one there throws the local team away",
        )
    }

    @Test
    fun the_local_file_and_apple_credentials_are_ignored() {
        for (pattern in listOf("iosApp/Signing.local.xcconfig", "*.p8", "*.mobileprovision")) {
            assertTrue(gitignore.lines().any { it.trim() == pattern }, ".gitignore must list $pattern")
        }
    }

    @Test
    fun no_apple_credential_is_tracked() {
        val offenders = Repo.root.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "build" && it.name != ".gradle" }
            // Extensions only: the ignored Signing.local.xcconfig legitimately exists
            // on a developer's Mac, and the_local_file_and_apple_credentials_are_ignored covers it.
            .filter { it.isFile && it.extension in setOf("p8", "p12", "mobileprovision") }
            .map { it.relativeTo(Repo.root).path }
            .toList()
        assertEquals(emptyList(), offenders)
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.host.IosSigningTest'`
Expected: FAIL. `expected iosApp/Signing.xcconfig under …`, `the project's Debug and Release must both include`, `a target's own DEVELOPMENT_TEAM`, `.gitignore must list`.

- [ ] **Step 3: Create `iosApp/Signing.xcconfig`**

```
// Signing for builds made on a developer's Mac, and nothing else.
//
// The included file holds DEVELOPMENT_TEAM and is never committed; see
// scripts/ios-device.sh, which writes it on first run. The `?` makes the
// include optional: without the file — every CI job, every fresh clone —
// no setting changes, and the simulator build stays unsigned exactly as the
// `macos` CI job expects. TestFlight builds pass the team on the command line.
#include? "Signing.local.xcconfig"
```

- [ ] **Step 4: Edit `project.pbxproj`**

1. In `/* Begin PBXFileReference section */`, add after the `Info.plist` line:
   ```
   		5FE0A10000000000000000E0 /* Signing.xcconfig */ = {isa = PBXFileReference; lastKnownFileType = text.xcconfig; path = Signing.xcconfig; sourceTree = "<group>"; };
   ```
2. In the root group `5FE0A10000000000000000A2`'s `children`, add as the first child:
   ```
   				5FE0A10000000000000000E0 /* Signing.xcconfig */,
   ```
3. In the project-level configurations `5FE0A10000000000000000A9 /* Debug */` and `5FE0A10000000000000000AA /* Release */`, add directly after `isa = XCBuildConfiguration;`:
   ```
   			baseConfigurationReference = 5FE0A10000000000000000E0 /* Signing.xcconfig */;
   ```
4. Delete every line `				DEVELOPMENT_TEAM = "";` (six target configurations: `CB`, `CC`, `DA`, `DB`, `AB`, `AC`).

- [ ] **Step 5: Edit `.gitignore`**

Append under `# Xcode / iOS`:

```
# Local signing (scripts/ios-device.sh writes it) and Apple credentials.
# The TestFlight job reads its App Store Connect key from a secret into
# $RUNNER_TEMP; nothing Apple issues ever belongs in the tree.
iosApp/Signing.local.xcconfig
*.p8
*.mobileprovision
```

- [ ] **Step 6: Run the test, then the whole JVM suite**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.host.IosSigningTest'` → PASS (5 tests).
Run: `./gradlew :shared:jvmTest` → BUILD SUCCESSFUL.

- [ ] **Step 7: Prove the project still opens and the unsigned build is unchanged (needs Xcode)**

Run from `iosApp/`:
```bash
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' ARCHS=arm64 ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO build
```
Expected: `** BUILD SUCCEEDED **`. Without Xcode, record "proven in CI, Task 11".

- [ ] **Step 8: Commit**

```bash
git add iosApp/Signing.xcconfig iosApp/iosApp.xcodeproj/project.pbxproj .gitignore shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/IosSigningTest.kt
git commit -m "build(ios): sign local builds from an uncommitted xcconfig; CI stays unsigned"
```

---

### Task 2: `scripts/ios-device.sh`

**Files:**
- Create: `scripts/ios-device.sh` (mode 755)
- Test: `shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/IosDeviceScriptTest.kt`

**Interfaces:**
- Consumes: `iosApp/Signing.xcconfig` includes `iosApp/Signing.local.xcconfig` (Task 1).
- Produces: `scripts/ios-device.sh [--team TEAMID] [--device UDID]`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.claudefleet.mobile.host

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The one command that puts a development build on the iPhone. */
class IosDeviceScriptTest {

    private val file by lazy { Repo.file("scripts/ios-device.sh") }
    private val script: String by lazy { file.readText() }

    @Test
    fun it_is_executable_and_strict() {
        assertTrue(file.canExecute(), "chmod +x scripts/ios-device.sh")
        assertTrue("set -euo pipefail" in script)
    }

    @Test
    fun it_parses() {
        val p = ProcessBuilder("bash", "-n", file.path).redirectErrorStream(true).start()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue(), p.inputStream.bufferedReader().readText())
    }

    @Test
    fun it_never_touches_the_bundle_identifier() {
        assertTrue(
            "PRODUCT_BUNDLE_IDENTIFIER" !in script,
            "a different bundle identifier is a different Keychain group: every pairing would be lost",
        )
    }

    @Test
    fun it_writes_only_the_local_signing_file() {
        val writes = Regex(""">\s*"?\$?\{?([A-Za-z_./-]+)""").findAll(script)
            .map { it.groupValues[1] }
            .filter { it.endsWith(".xcconfig") || it == "LOCAL" }
            .toSet()
        assertEquals(setOf("LOCAL"), writes, "the script may write \$LOCAL (iosApp/Signing.local.xcconfig) and nothing else")
        assertTrue("LOCAL=iosApp/Signing.local.xcconfig" in script)
    }

    @Test
    fun it_refuses_to_change_a_team_it_did_not_get_told_about() {
        assertTrue("--team" in script)
        assertTrue("refusing to change DEVELOPMENT_TEAM" in script)
    }

    @Test
    fun xcode_is_probed_with_a_deadline() {
        assertTrue(
            Regex("""probe\s+\d+\s+xcodebuild -version""").containsMatchIn(script),
            "Xcode on an external card can hang on exec; the script must give up and say so",
        )
    }

    @Test
    fun it_signs_with_automatic_provisioning_and_installs_with_devicectl() {
        assertTrue("-allowProvisioningUpdates" in script)
        assertTrue("devicectl device install app" in script)
        assertTrue("devicectl device process launch" in script)
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.host.IosDeviceScriptTest'`
Expected: FAIL with `expected scripts/ios-device.sh under …`.

- [ ] **Step 3: Write `scripts/ios-device.sh`**

```bash
#!/usr/bin/env bash
# Build fleet-mobile (Debug) for a connected iPhone, install it, and launch it.
#
#   scripts/ios-device.sh                 first iPhone devicectl lists
#   scripts/ios-device.sh --device UDID   a specific one
#   scripts/ios-device.sh --team TEAMID   (re)write the local signing team
#
# Signing comes from iosApp/Signing.local.xcconfig, which this script writes
# on first run from the Apple Development certificate in the login keychain
# and never commits (see iosApp/Signing.xcconfig). The bundle identifier is
# never touched: the Keychain item holding the pairing is keyed by it.
set -euo pipefail
cd "$(dirname "$0")/.."

LOCAL=iosApp/Signing.local.xcconfig
DERIVED=build/ios-device
TEAM_ARG=""
DEVICE=""

die() { echo "ios-device: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --team) TEAM_ARG="${2:?--team needs a value}"; shift 2 ;;
    --device) DEVICE="${2:?--device needs a value}"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

# Run "$@" for at most $1 seconds. Returns 124 on timeout, like timeout(1),
# which macOS does not ship.
probe() {
  local limit=$1; shift
  "$@" >/dev/null 2>&1 &
  local pid=$! i
  for ((i = 0; i < limit; i++)); do
    if ! kill -0 "$pid" 2>/dev/null; then wait "$pid"; return; fi
    sleep 1
  done
  kill "$pid" 2>/dev/null || true
  return 124
}

# 1. Xcode. On the machine this was written on it lives on an external card
#    whose exec can hang outright; a probe with a deadline says so instead.
if [[ -z "${DEVELOPER_DIR:-}" ]]; then
  selected=$(xcode-select -p 2>/dev/null || true)
  if [[ "$selected" == *CommandLineTools* || -z "$selected" ]]; then
    candidate=$(ls -d /Applications/Xcode*.app /Volumes/*/Applications/Xcode*.app 2>/dev/null | sort -V | tail -1 || true)
    [[ -n "$candidate" ]] || die "no Xcode found (xcode-select points at the Command Line Tools). Mount the volume Xcode is on, or set DEVELOPER_DIR."
    export DEVELOPER_DIR="$candidate/Contents/Developer"
  fi
fi
if ! probe 30 xcodebuild -version; then
  die "xcodebuild did not answer within 30 s. If Xcode is on an external card, reseat it and try again."
fi

# 2. A JDK, which the Kotlin framework build phase needs and Xcode does not
#    inherit from a login shell.
if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)
  [[ -n "$JAVA_HOME" ]] || die "no JDK found; install JDK 21 or set JAVA_HOME"
  export JAVA_HOME
fi

# 3. The signing team.
current=""
[[ -f "$LOCAL" ]] && current=$(sed -n 's/^DEVELOPMENT_TEAM *= *//p' "$LOCAL" | tr -d '[:space:]')
team_from_keychain() {
  security find-certificate -c "Apple Development" -p 2>/dev/null \
    | openssl x509 -noout -subject -nameopt multiline 2>/dev/null \
    | sed -n 's/^ *organizationalUnitName *= *//p' | head -1
}
if [[ -n "$TEAM_ARG" ]]; then
  team="$TEAM_ARG"
elif [[ -n "$current" ]]; then
  team="$current"
else
  team=$(team_from_keychain)
  [[ -n "$team" ]] || die "no Apple Development certificate in the keychain; sign in to Xcode → Settings → Accounts once, or pass --team"
fi
if [[ -n "$current" && "$team" != "$current" && -z "$TEAM_ARG" ]]; then
  die "refusing to change DEVELOPMENT_TEAM from $current to $team without --team"
fi
if [[ "$team" != "$current" ]]; then
  printf '// Written by scripts/ios-device.sh. Not committed.\nDEVELOPMENT_TEAM = %s\n' "$team" > "$LOCAL"
  echo "ios-device: signing team $team written to $LOCAL"
fi

# 4. The device.
if [[ -z "$DEVICE" ]]; then
  list=$(mktemp)
  trap 'rm -f "$list"' EXIT
  xcrun devicectl list devices --json-output "$list" >/dev/null
  i=0
  while kind=$(plutil -extract "result.devices.$i.hardwareProperties.deviceType" raw "$list" 2>/dev/null); do
    if [[ "$kind" == iPhone ]]; then
      DEVICE=$(plutil -extract "result.devices.$i.hardwareProperties.udid" raw "$list")
      break
    fi
    i=$((i + 1))
  done
  [[ -n "$DEVICE" ]] || die "no iPhone paired with this Mac. Connect it by cable once and tap Trust."
fi
echo "ios-device: device $DEVICE"

# 5. Build, install, launch.
log=$(mktemp)
if ! xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
    -destination "id=$DEVICE" -derivedDataPath "$DERIVED" -allowProvisioningUpdates build 2>&1 | tee "$log"; then
  if grep -q "Developer Mode" "$log"; then
    die "turn on Developer Mode on the iPhone: Settings → Privacy & Security → Developer Mode, then restart it"
  fi
  die "build failed; the log is $log"
fi
app="$DERIVED/Build/Products/Debug-iphoneos/iosApp.app"
bundle=$(plutil -extract CFBundleIdentifier raw "$app/Info.plist")
xcrun devicectl device install app --device "$DEVICE" "$app"
if ! xcrun devicectl device process launch --device "$DEVICE" "$bundle" 2>&1 | tee "$log"; then
  if grep -qiE "not been explicitly trusted|invalid code signature|profile has not been" "$log"; then
    die "trust the developer on the iPhone: Settings → General → VPN & Device Management → your Apple ID → Trust"
  fi
  die "launch failed; the log is $log"
fi
echo "ios-device: $bundle is running on $DEVICE"
```

Then: `chmod +x scripts/ios-device.sh`.

- [ ] **Step 4: Run the test, then the whole JVM suite**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.host.IosDeviceScriptTest'` → PASS (7 tests).
Run: `./gradlew :shared:jvmTest` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add scripts/ios-device.sh shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/IosDeviceScriptTest.kt
git commit -m "feat(ios): scripts/ios-device.sh builds, installs and launches on a connected iPhone"
```

---

### Task 3: The app icon

**Files:**
- Create: `scripts/render-ios-icon.swift`
- Create: `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` (generated)
- Modify: `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json`
- Test: `shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/IosAppIconTest.kt`

App Store Connect rejects an upload without a 1024×1024 icon, and rejects one with an alpha channel.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An upload without a 1024 px opaque icon is refused by App Store Connect. */
class IosAppIconTest {

    private val dir = "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"

    @Test
    fun the_catalog_names_the_image() {
        val contents = Repo.file("$dir/Contents.json").readText()
        assertTrue("\"filename\" : \"AppIcon-1024.png\"" in contents)
    }

    @Test
    fun the_image_is_1024_square_and_opaque() {
        val png = Repo.file("$dir/AppIcon-1024.png").readBytes()
        assertEquals("PNG", String(png, 1, 3))
        fun int(at: Int) = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (png[at + i].toInt() and 0xFF) }
        assertEquals(1024, int(16), "width")
        assertEquals(1024, int(20), "height")
        val colorType = png[25].toInt()
        assertTrue(colorType == 2 || colorType == 0, "PNG colour type $colorType carries alpha; App Store Connect refuses it")
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.host.IosAppIconTest'`
Expected: FAIL (`"filename"` missing, PNG not found).

- [ ] **Step 3: Write `scripts/render-ios-icon.swift`**

```swift
// Renders the iOS app icon from the same drawing as Android's launcher glyph
// (androidApp/src/main/res/drawable/ic_launcher_foreground.xml on
// @color/ic_launcher_background): a white shell prompt on #1B2430, in the
// same 108-unit viewport, scaled to 1024 px. Opaque on purpose: App Store
// Connect refuses an icon with an alpha channel.
//
//   swift scripts/render-ios-icon.swift iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png
import CoreGraphics
import Foundation
import ImageIO

let size = 1024
let scale = CGFloat(size) / 108
let ctx = CGContext(
    data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
    space: CGColorSpace(name: CGColorSpace.sRGB)!,
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
)!
ctx.setFillColor(CGColor(srgbRed: 0x1B / 255.0, green: 0x24 / 255.0, blue: 0x30 / 255.0, alpha: 1))
ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))
// Android's viewport is y-down; Core Graphics is y-up.
ctx.translateBy(x: 0, y: CGFloat(size))
ctx.scaleBy(x: scale, y: -scale)
ctx.setStrokeColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1))
ctx.setLineWidth(7)
ctx.setLineCap(.round)
ctx.setLineJoin(.round)
ctx.move(to: CGPoint(x: 34, y: 40)); ctx.addLine(to: CGPoint(x: 50, y: 54)); ctx.addLine(to: CGPoint(x: 34, y: 68))
ctx.strokePath()
ctx.move(to: CGPoint(x: 56, y: 68)); ctx.addLine(to: CGPoint(x: 74, y: 68))
ctx.strokePath()

let out = URL(fileURLWithPath: CommandLine.arguments[1])
let dest = CGImageDestinationCreateWithURL(out as CFURL, "public.png" as CFString, 1, nil)!
CGImageDestinationAddImage(dest, ctx.makeImage()!, nil)
guard CGImageDestinationFinalize(dest) else { fatalError("could not write \(out.path)") }
```

- [ ] **Step 4: Render it and update the catalog**

Run: `swift scripts/render-ios-icon.swift iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`
(`swift` from the Command Line Tools is enough; no Xcode needed.)

Replace `Contents.json` with:

```json
{
  "images" : [
    {
      "filename" : "AppIcon-1024.png",
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
```

Look at the PNG (open it). It should show a white `>_` on dark slate.

- [ ] **Step 5: Run the test, then the whole JVM suite**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.host.IosAppIconTest'` → PASS.
Run: `./gradlew :shared:jvmTest` → BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add scripts/render-ios-icon.swift iosApp/iosApp/Assets.xcassets/AppIcon.appiconset shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/IosAppIconTest.kt
git commit -m "feat(ios): an app icon, drawn from the Android launcher glyph"
```

---

### Task 4: The TestFlight workflow

**Files:**
- Create: `iosApp/ExportOptions.plist`
- Create: `.github/workflows/testflight.yml`
- Modify: `iosApp/iosApp/Info.plist` (add `ITSAppUsesNonExemptEncryption`)
- Test: `shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/TestFlightWorkflowTest.kt`

**Interfaces:**
- Consumes: repository secrets `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`, `APPLE_TEAM_ID` (the operator creates them; see the spec's *Prerequisites*).

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The TestFlight workflow holds the App Store Connect key. These are the rules
 * that keep it held: where it comes from, where it is written, that it is
 * removed, that a tag is never interpolated, and that only a release tag
 * uploads.
 */
class TestFlightWorkflowTest {

    private val wf: String by lazy { Repo.file(".github/workflows/testflight.yml").readText() }
    private val ci: String by lazy { Repo.file(".github/workflows/ci.yml").readText() }

    private fun stepAt(marker: String): String {
        val start = wf.indexOf("- name: $marker")
        assertTrue(start >= 0, "expected a step named '$marker' in testflight.yml")
        val next = wf.indexOf("\n      - ", start)
        return if (next >= 0) wf.substring(start, next) else wf.substring(start)
    }

    @Test
    fun it_runs_on_v_tags_and_by_hand_and_on_nothing_else() {
        val on = wf.substringAfter("\non:").substringBefore("\n\n")
        assertTrue("'v*'" in on)
        assertTrue("workflow_dispatch" in on)
        assertTrue("pull_request" !in wf, "a fork's PR must never reach the App Store Connect key")
        assertTrue("branches:" !in on)
    }

    @Test
    fun permissions_are_read_only() {
        assertTrue("permissions:\n  contents: read" in wf)
        assertTrue("contents: write" !in wf)
    }

    @Test
    fun the_tag_reaches_shell_only_through_env() {
        for (line in wf.lines().filter { it.trimStart().startsWith("run:") || it.startsWith("          ") }) {
            assertTrue("\${{ github.ref_name }}" !in line || line.trimStart().startsWith("TAG:"), "tag interpolated into a run body: $line")
        }
    }

    @Test
    fun the_build_number_rule_is_the_specs() {
        val version = stepAt("Derive the version and build number")
        assertTrue("10#\$x * 10000 + 10#\$y * 100 + 10#\$z" in version)
        assertTrue(">= 100" in version, "Y or Z of 100 would collide with the next minor's build numbers")
        assertTrue("upload=false" in version)
    }

    @Test
    fun only_a_pushed_release_tag_uploads() {
        val version = stepAt("Derive the version and build number")
        assertTrue("[[ -n \"\$suffix\" ]] && upload=false" in version, "a -suffix tag must not upload")
        assertTrue("dispatch" in version && "upload=false" in version, "workflow_dispatch must not upload")
        val export = stepAt("Export, and upload when this is a release tag")
        assertTrue("Set :destination export" in export, "without upload, the export options must say export")
    }

    @Test
    fun the_key_lives_under_runner_temp_and_is_always_removed() {
        val write = stepAt("Write the App Store Connect key")
        assertTrue("umask 077" in write)
        assertTrue("\$RUNNER_TEMP/asc_key.p8" in write)
        val remove = stepAt("Remove the App Store Connect key")
        assertTrue("if: always()" in remove)
        assertTrue("rm -f \"\$RUNNER_TEMP/asc_key.p8\"" in remove)
    }

    @Test
    fun secrets_reach_the_shell_through_env_only() {
        for (line in wf.lines()) {
            if ("secrets." in line) {
                assertTrue(Regex("""^\s+[A-Z_]+:\s*\$\{\{ secrets\.[A-Z_0-9]+ }}\s*$""").matches(line), "secret used outside an env: mapping: $line")
            }
        }
    }

    @Test
    fun a_missing_secret_fails_before_building() {
        val check = stepAt("Check the App Store Connect secrets")
        for (name in listOf("ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_KEY_P8", "APPLE_TEAM_ID")) assertTrue(name in check)
        assertTrue("exit 1" in check)
    }

    @Test
    fun export_options_upload_to_app_store_connect() {
        val opts = Repo.file("iosApp/ExportOptions.plist").readText()
        assertTrue("<string>app-store-connect</string>" in opts)
        assertTrue(Regex("""<key>destination</key>\s*<string>upload</string>""").containsMatchIn(opts))
        assertTrue("teamID" !in opts, "the team comes from the secret at run time")
    }

    @Test
    fun builds_skip_the_export_compliance_question() {
        val plist = Repo.file("iosApp/iosApp/Info.plist").readText()
        assertTrue(Regex("""<key>ITSAppUsesNonExemptEncryption</key>\s*<false/>""").containsMatchIn(plist))
    }

    @Test
    fun actions_are_pinned_as_in_ci() {
        val pins = { t: String -> Regex("""uses:\s*([\w./-]+)@(\S+)""").findAll(t).associate { it.groupValues[1] to it.groupValues[2] } }
        val ciPins = pins(ci)
        for ((action, pin) in pins(wf)) {
            assertTrue(Regex("""^v?\d+(\.\d+){0,2}$""").matches(pin), "$action@$pin")
            ciPins[action]?.let { assertEquals(it, pin, "$action pinned differently from ci.yml") }
        }
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.host.TestFlightWorkflowTest'`
Expected: FAIL with `expected .github/workflows/testflight.yml under …`.

- [ ] **Step 3: Create `iosApp/ExportOptions.plist`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>destination</key>
	<string>upload</string>
	<key>signingStyle</key>
	<string>automatic</string>
	<key>uploadSymbols</key>
	<true/>
	<key>manageAppVersionAndBuildNumber</key>
	<false/>
</dict>
</plist>
```

- [ ] **Step 4: Create `.github/workflows/testflight.yml`**

```yaml
name: TestFlight

# A separate workflow from release.yml on purpose: release.yml is held to tag
# pushes only (ReleaseWorkflowTest), and signing needs a dry run that can be
# started by hand before the first real tag. A dispatch never uploads.
on:
  push:
    tags:
      - 'v*'
  workflow_dispatch:

concurrency:
  group: testflight-${{ github.ref_name }}
  cancel-in-progress: false

permissions:
  contents: read

jobs:
  testflight:
    name: Archive, sign and upload to TestFlight
    runs-on: macos-15

    steps:
      - uses: actions/checkout@v4

      - name: JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Select Xcode
        uses: maxim-lobanov/setup-xcode@v1
        with:
          xcode-version: latest-stable

      - name: Gradle
        uses: gradle/actions/setup-gradle@v4

      # The tag is attacker-controlled input; it arrives through env and is
      # validated before anything uses it.
      - name: Derive the version and build number
        id: version
        env:
          EVENT: ${{ github.event_name }}
          TAG: ${{ github.ref_name }}
        run: |
          set -euo pipefail
          if [[ "$EVENT" == "push" ]]; then
            if [[ ! "$TAG" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)(-[0-9A-Za-z.-]+)?$ ]]; then
              echo "::error::tag '$TAG' is not vX.Y.Z[-suffix]" >&2
              exit 1
            fi
            x=${BASH_REMATCH[1]} y=${BASH_REMATCH[2]} z=${BASH_REMATCH[3]} suffix=${BASH_REMATCH[4]}
            upload=true
            [[ -n "$suffix" ]] && upload=false
          else
            # dispatch: prove the signing, never upload
            x=0 y=0 z=1 upload=false
          fi
          if (( 10#$y >= 100 || 10#$z >= 100 )); then
            echo "::error::minor and patch must be below 100 to make a build number" >&2
            exit 1
          fi
          echo "marketing=$x.$y.$z" >> "$GITHUB_OUTPUT"
          echo "build=$(( 10#$x * 10000 + 10#$y * 100 + 10#$z ))" >> "$GITHUB_OUTPUT"
          echo "upload=$upload" >> "$GITHUB_OUTPUT"

      - name: Check the App Store Connect secrets
        env:
          ASC_KEY_ID: ${{ secrets.ASC_KEY_ID }}
          ASC_ISSUER_ID: ${{ secrets.ASC_ISSUER_ID }}
          ASC_KEY_P8: ${{ secrets.ASC_KEY_P8 }}
          APPLE_TEAM_ID: ${{ secrets.APPLE_TEAM_ID }}
        run: |
          set -euo pipefail
          missing=()
          [[ -n "$ASC_KEY_ID" ]] || missing+=(ASC_KEY_ID)
          [[ -n "$ASC_ISSUER_ID" ]] || missing+=(ASC_ISSUER_ID)
          [[ -n "$ASC_KEY_P8" ]] || missing+=(ASC_KEY_P8)
          [[ -n "$APPLE_TEAM_ID" ]] || missing+=(APPLE_TEAM_ID)
          if (( ${#missing[@]} )); then
            echo "::error::missing repository secrets: ${missing[*]}" >&2
            exit 1
          fi

      - name: Write the App Store Connect key
        env:
          ASC_KEY_P8: ${{ secrets.ASC_KEY_P8 }}
        run: |
          set -euo pipefail
          umask 077
          printf '%s\n' "$ASC_KEY_P8" > "$RUNNER_TEMP/asc_key.p8"

      - name: Archive
        working-directory: iosApp
        env:
          ASC_KEY_ID: ${{ secrets.ASC_KEY_ID }}
          ASC_ISSUER_ID: ${{ secrets.ASC_ISSUER_ID }}
          APPLE_TEAM_ID: ${{ secrets.APPLE_TEAM_ID }}
          MARKETING: ${{ steps.version.outputs.marketing }}
          BUILD: ${{ steps.version.outputs.build }}
        run: |
          set -euo pipefail
          xcodebuild archive -project iosApp.xcodeproj -scheme iosApp -configuration Release \
            -destination 'generic/platform=iOS' -archivePath "$RUNNER_TEMP/iosApp.xcarchive" \
            -allowProvisioningUpdates \
            -authenticationKeyPath "$RUNNER_TEMP/asc_key.p8" \
            -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID" \
            DEVELOPMENT_TEAM="$APPLE_TEAM_ID" MARKETING_VERSION="$MARKETING" CURRENT_PROJECT_VERSION="$BUILD"

      - name: Export, and upload when this is a release tag
        working-directory: iosApp
        env:
          ASC_KEY_ID: ${{ secrets.ASC_KEY_ID }}
          ASC_ISSUER_ID: ${{ secrets.ASC_ISSUER_ID }}
          APPLE_TEAM_ID: ${{ secrets.APPLE_TEAM_ID }}
          UPLOAD: ${{ steps.version.outputs.upload }}
        run: |
          set -euo pipefail
          opts="$RUNNER_TEMP/ExportOptions.plist"
          cp ExportOptions.plist "$opts"
          /usr/libexec/PlistBuddy -c "Add :teamID string $APPLE_TEAM_ID" "$opts"
          if [[ "$UPLOAD" != "true" ]]; then
            /usr/libexec/PlistBuddy -c 'Set :destination export' "$opts"
          fi
          xcodebuild -exportArchive -archivePath "$RUNNER_TEMP/iosApp.xcarchive" \
            -exportOptionsPlist "$opts" -exportPath "$RUNNER_TEMP/export" \
            -allowProvisioningUpdates \
            -authenticationKeyPath "$RUNNER_TEMP/asc_key.p8" \
            -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID"

      - name: Remove the App Store Connect key
        if: always()
        run: rm -f "$RUNNER_TEMP/asc_key.p8"
```

- [ ] **Step 5: Add the export-compliance key to `iosApp/iosApp/Info.plist`**

Insert before `<key>UILaunchScreen</key>`:

```xml
	<!--
	  The app's only encryption is HTTPS through the system, which is exempt.
	  Without this key every TestFlight build waits in App Store Connect for
	  someone to answer the export-compliance question by hand.
	-->
	<key>ITSAppUsesNonExemptEncryption</key>
	<false/>

```

- [ ] **Step 6: Run the test, then the whole JVM suite**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.host.TestFlightWorkflowTest'` → PASS (11 tests).
Run: `./gradlew :shared:jvmTest` → BUILD SUCCESSFUL. `ReleaseWorkflowTest` and `CiWorkflowTest` must still pass. They read their own files, but `CiWorkflowTest` may scan every workflow; if it does, adjust only what it requires of *this* file, never its rules.

- [ ] **Step 7: Commit**

```bash
git add iosApp/ExportOptions.plist .github/workflows/testflight.yml iosApp/iosApp/Info.plist shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/TestFlightWorkflowTest.kt
git commit -m "ci(ios): archive, sign and upload to TestFlight on every release tag"
```

---

### Task 5: `NeedsYouCheck` — one look, from the background

**Files:**
- Create: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/notify/NeedsYouContent.kt`
- Create: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/notify/NeedsYouCheck.kt`
- Test: `shared/src/commonTest/kotlin/dev/claudefleet/mobile/notify/NeedsYouCheckTest.kt`

**Interfaces:**
- Consumes: `needsYouAlerts(seen, rows)`, `encodeSeen`, `decodeSeen`, `NeedsYouAlert` (existing, `NeedsYou.kt`); `AppSession.withClient`, `HubClient.listSessions()`; `Prefs`.
- Produces:
  ```kotlin
  interface AlertPoster { fun post(alert: NeedsYouAlert); fun withdraw(sessionId: Long) }
  const val NEEDS_YOU_SEEN: String = "needs_you_seen"
  fun Prefs.readSeen(): Map<Long, String?>?
  fun Prefs.writeSeen(seen: Map<Long, String?>)
  class NeedsYouCheck(session: AppSession, prefs: Prefs, poster: AlertPoster, timeout: Duration = 20.seconds) { suspend fun once() }
  const val NEEDS_YOU_THREAD: String = "needs_you"
  const val NEEDS_YOU_SESSION_KEY: String = "sessionId"
  const val NEEDS_YOU_ID_PREFIX: String = "needs-you-"
  fun needsYouId(sessionId: Long): String
  data class NeedsYouContent(val id: String, val thread: String, val title: String, val body: String, val sessionId: Long)
  fun needsYouContent(alert: NeedsYouAlert): NeedsYouContent
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.data.AppSession
import dev.claudefleet.mobile.store.Credentials
import dev.claudefleet.mobile.store.FakePrefs
import dev.claudefleet.mobile.store.Prefs
import dev.claudefleet.mobile.store.Secrets
import dev.claudefleet.mobile.store.SecretsUnavailable
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val BASE = "https://fleet.example.com"

private fun sse(body: String) = "event: message\ndata: $body\n\n"

private fun okResult(payloadJson: String): String {
    val text = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(payloadJson))
    return """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":$text}]}}"""
}

/** `list_sessions` rows: id → attention reason (null: needs nothing). */
private fun rows(vararg r: Pair<Long, String?>): String = r.joinToString(",", "[", "]") { (id, reason) ->
    val attention = reason?.let { ""","needs_attention":{"reason":"$it"}""" } ?: ""
    """{"id":$id,"tmux_name":"s$id","host_alias":"pine"$attention}"""
}

private class MemSecrets(
    var stored: Credentials? = Credentials(BASE, "tok-phone", "phone", "full"),
    private val unreadable: Boolean = false,
) : Secrets {
    override suspend fun read(): Credentials? {
        if (unreadable) throw SecretsUnavailable("the Keychain is not available before first unlock")
        return stored
    }
    override suspend fun write(credentials: Credentials) { stored = credentials }
    override suspend fun clear() { stored = null }
}

internal class RecordingPoster : AlertPoster {
    val posted = mutableListOf<Long>()
    val withdrawn = mutableListOf<Long>()
    override fun post(alert: NeedsYouAlert) { posted += alert.sessionId }
    override fun withdraw(sessionId: Long) { withdrawn += sessionId }
}

private class Hub {
    var requests = 0
    var reply: suspend () -> Pair<String, HttpStatusCode> = { sse(okResult("[]")) to HttpStatusCode.OK }
}

private fun check(
    hub: Hub,
    secrets: Secrets = MemSecrets(),
    prefs: Prefs = FakePrefs(),
    poster: AlertPoster = RecordingPoster(),
    timeout: Duration = 20.seconds,
): NeedsYouCheck {
    val engine = MockEngine {
        hub.requests++
        val (body, status) = hub.reply()
        val type = if (body.startsWith("event:")) "text/event-stream" else "application/json"
        respond(body, status, headersOf(HttpHeaders.ContentType, type))
    }
    return NeedsYouCheck(AppSession(secrets, HttpClient(engine)), prefs, poster, timeout)
}

class NeedsYouCheckTest {

    @Test
    fun the_first_look_is_a_baseline_and_says_nothing() = runTest {
        val hub = Hub().apply { reply = { sse(okResult(rows(1L to "waiting"))) to HttpStatusCode.OK } }
        val prefs = FakePrefs()
        val poster = RecordingPoster()

        check(hub, prefs = prefs, poster = poster).once()

        assertEquals(emptyList(), poster.posted)
        assertEquals(mapOf(1L to "waiting"), prefs.readSeen())
    }

    @Test
    fun a_session_that_comes_to_need_you_is_posted_once() = runTest {
        val hub = Hub().apply { reply = { sse(okResult(rows(1L to "waiting"))) to HttpStatusCode.OK } }
        val prefs = FakePrefs().apply { writeSeen(mapOf(1L to null)) }
        val poster = RecordingPoster()
        val c = check(hub, prefs = prefs, poster = poster)

        c.once()
        c.once()

        assertEquals(listOf(1L), poster.posted, "still waiting is not news the second time")
    }

    @Test
    fun a_session_that_stops_needing_you_or_goes_away_is_withdrawn() = runTest {
        val hub = Hub().apply { reply = { sse(okResult(rows(1L to null))) to HttpStatusCode.OK } }
        val prefs = FakePrefs().apply { writeSeen(mapOf(1L to "waiting", 2L to "stuck")) }
        val poster = RecordingPoster()

        check(hub, prefs = prefs, poster = poster).once()

        assertEquals(listOf(1L, 2L), poster.withdrawn.sorted(), "1 settled, 2 was killed")
        assertEquals(emptyList(), poster.posted)
    }

    @Test
    fun a_401_unpairs_posts_nothing_and_keeps_the_last_look() = runTest {
        val hub = Hub().apply { reply = { "" to HttpStatusCode.Unauthorized } }
        val secrets = MemSecrets()
        val prefs = FakePrefs().apply { writeSeen(mapOf(1L to null)) }
        val poster = RecordingPoster()

        check(hub, secrets = secrets, prefs = prefs, poster = poster).once()

        assertNull(secrets.stored, "a 401 forgets the credential, as everywhere else in the app")
        assertEquals(emptyList(), poster.posted)
        assertEquals(mapOf(1L to null), prefs.readSeen())
    }

    @Test
    fun a_hub_error_keeps_the_last_look() = runTest {
        val hub = Hub().apply { reply = { "" to HttpStatusCode.InternalServerError } }
        val prefs = FakePrefs().apply { writeSeen(mapOf(1L to null)) }
        val poster = RecordingPoster()

        check(hub, prefs = prefs, poster = poster).once()

        assertEquals(emptyList(), poster.posted)
        assertEquals(mapOf(1L to null), prefs.readSeen())
    }

    @Test
    fun a_hub_that_never_answers_is_given_up_on() = runTest {
        val hub = Hub().apply { reply = { awaitCancellation() } }
        val prefs = FakePrefs().apply { writeSeen(mapOf(1L to null)) }
        val poster = RecordingPoster()

        check(hub, prefs = prefs, poster = poster, timeout = 100.milliseconds).once()

        assertEquals(emptyList(), poster.posted)
        assertEquals(mapOf(1L to null), prefs.readSeen())
    }

    @Test
    fun a_keychain_that_cannot_be_read_is_a_quiet_no() = runTest {
        val hub = Hub()
        val poster = RecordingPoster()

        check(hub, secrets = MemSecrets(unreadable = true), poster = poster).once()

        assertEquals(0, hub.requests)
        assertEquals(emptyList(), poster.posted)
    }

    @Test
    fun an_unpaired_device_asks_nothing() = runTest {
        val hub = Hub()
        val secrets = MemSecrets(stored = null)

        check(hub, secrets = secrets).once()

        assertEquals(0, hub.requests)
    }

    @Test
    fun the_seen_set_tells_never_stored_from_stored_empty() {
        val prefs = FakePrefs()
        assertNull(prefs.readSeen())
        prefs.writeSeen(emptyMap())
        assertEquals(emptyMap(), prefs.readSeen(), "an empty fleet is a look, not the absence of one")
    }

    @Test
    fun the_notification_content_is_keyed_by_session() {
        val c = needsYouContent(NeedsYouAlert(42, "hub client", "Waiting for you · pine", "asks: deploy?"))
        assertEquals("needs-you-42", c.id)
        assertEquals("needs_you", c.thread)
        assertEquals("hub client", c.title)
        assertEquals("Waiting for you · pine\nasks: deploy?", c.body)
        assertEquals(42L, c.sessionId)
        assertTrue(c.id.startsWith(NEEDS_YOU_ID_PREFIX))
    }

    @Test
    fun content_without_a_detail_is_one_line() {
        assertEquals("Stuck · pine", needsYouContent(NeedsYouAlert(1, "a", "Stuck · pine")).body)
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.notify.NeedsYouCheckTest'`
Expected: compilation FAIL, `Unresolved reference: NeedsYouCheck` / `AlertPoster` / `readSeen` / `needsYouContent`.

- [ ] **Step 3: Write `NeedsYouContent.kt`**

```kotlin
package dev.claudefleet.mobile.notify

/** The thread every "needs you" notification shares, so the system groups them. */
const val NEEDS_YOU_THREAD: String = "needs_you"

/** Where a notification carries its session, for the tap that opens it. */
const val NEEDS_YOU_SESSION_KEY: String = "sessionId"

const val NEEDS_YOU_ID_PREFIX: String = "needs-you-"

/** One identifier per session: a session that needs you again replaces its own notification. */
fun needsYouId(sessionId: Long): String = "$NEEDS_YOU_ID_PREFIX$sessionId"

/** What a platform's notification says, decided once for every platform that posts one. */
data class NeedsYouContent(
    val id: String,
    val thread: String,
    val title: String,
    val body: String,
    val sessionId: Long,
)

fun needsYouContent(alert: NeedsYouAlert): NeedsYouContent = NeedsYouContent(
    id = needsYouId(alert.sessionId),
    thread = NEEDS_YOU_THREAD,
    title = alert.title,
    // What it is doing, when there is a line for it: the question is half of
    // whether to pick the phone up. Same as Android's BigTextStyle.
    body = listOfNotNull(alert.text, alert.detail).joinToString("\n"),
    sessionId = alert.sessionId,
)
```

- [ ] **Step 4: Write `NeedsYouCheck.kt`**

```kotlin
package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.data.AppSession
import dev.claudefleet.mobile.store.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where "needs you" notifications go on a platform that posts them from shared code. */
interface AlertPoster {
    fun post(alert: NeedsYouAlert)

    fun withdraw(sessionId: Long)
}

/** The seen set the background check and the open app share, in [Prefs]. */
const val NEEDS_YOU_SEEN: String = "needs_you_seen"

/**
 * A line that is always written, so "no look yet" (nothing stored) and "the
 * last look saw no sessions" (only this line) are different answers.
 * [decodeSeen] skips it: it has no `=`.
 */
private const val SEEN_HEADER = "v1"

fun Prefs.readSeen(): Map<Long, String?>? =
    getStringList(NEEDS_YOU_SEEN).takeIf { SEEN_HEADER in it }?.let(::decodeSeen)

fun Prefs.writeSeen(seen: Map<Long, String?>) {
    putStringList(NEEDS_YOU_SEEN, listOf(SEEN_HEADER) + encodeSeen(seen))
}

/**
 * One look at the fleet, for a platform that cannot keep watching it: iOS,
 * woken by `BGAppRefreshTask` for about thirty seconds at a time of the
 * system's choosing.
 *
 * The same rules as Android's always-on watcher, applied once:
 * [needsYouAlerts] decides what is news against what the last look saw; with
 * no last look, this one is the baseline and nothing is said. The seen set is
 * written only after posting, so a run cut short is repeated, not half-applied
 * — and posting is idempotent per session, so the repeat is harmless.
 *
 * It never throws anything but cancellation. A 401 has already unpaired the
 * device through [AppSession.withClient]; every other failure — the hub
 * unreachable, slow past [timeout], a Keychain that cannot be read before the
 * first unlock — keeps the last look and says nothing. On iOS an exception
 * escaping to Swift ends the process, so there is no other honest answer.
 */
class NeedsYouCheck(
    private val session: AppSession,
    private val prefs: Prefs,
    private val poster: AlertPoster,
    private val timeout: Duration = 20.seconds,
) {
    suspend fun once() {
        val rows = try {
            withTimeoutOrNull(timeout) { session.withClient { it.listSessions() } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return

        val before = prefs.readSeen()
        val (alerts, after) = needsYouAlerts(before.orEmpty(), rows)
        if (before != null) {
            alerts.forEach(poster::post)
            before.filter { (id, reason) -> reason != null && after[id] == null }.keys.forEach(poster::withdraw)
        }
        prefs.writeSeen(after)
    }
}
```

- [ ] **Step 5: Run the tests, then the whole JVM suite**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.notify.NeedsYouCheckTest'` → PASS (11 tests).
Run: `./gradlew :shared:jvmTest` → BUILD SUCCESSFUL.

If `an_unpaired_device_asks_nothing` or `a_keychain_that_cannot_be_read_is_a_quiet_no` shows `withClient` throwing something other than `HubError`/`SecretsUnavailable`, that is fine: `catch (_: Exception)` covers it. The assertion that matters is that no request was made.

- [ ] **Step 6: Run on Kotlin/Native (needs Xcode)**

Run: `./gradlew :shared:iosSimulatorArm64Test` → BUILD SUCCESSFUL. Without Xcode, record "proven in CI, Task 11".

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/dev/claudefleet/mobile/notify/NeedsYouContent.kt shared/src/commonMain/kotlin/dev/claudefleet/mobile/notify/NeedsYouCheck.kt shared/src/commonTest/kotlin/dev/claudefleet/mobile/notify/NeedsYouCheckTest.kt
git commit -m "feat(notify): NeedsYouCheck, one look at the fleet for a platform that cannot keep watching"
```

---

### Task 6: The open app keeps the seen set

**Files:**
- Modify: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/notify/NeedsYouCheck.kt`
- Modify: `shared/src/commonTest/kotlin/dev/claudefleet/mobile/notify/NeedsYouCheckTest.kt`

**Interfaces:**
- Consumes: `needsYouEvents(fleet, remembered, onSeen)` (existing), `readSeen`/`writeSeen`, `AlertPoster` (Task 5).
- Produces: `suspend fun keepSeenWhileOpen(fleet: FleetState, prefs: Prefs, poster: AlertPoster)`. It runs until cancelled.

Without this, the first background run after closing the app announces every session the person just looked at.

- [ ] **Step 1: Write the failing tests** (append to `NeedsYouCheckTest.kt`)

Add imports: `dev.claudefleet.mobile.data.ConnectionStatus`, `dev.claudefleet.mobile.data.FleetState`, `dev.claudefleet.mobile.model.Attention`, `dev.claudefleet.mobile.model.HostRow`, `dev.claudefleet.mobile.model.ProjectRow`, `dev.claudefleet.mobile.model.SessionRow`, `kotlinx.coroutines.flow.MutableSharedFlow`, `kotlinx.coroutines.flow.MutableStateFlow`, `kotlinx.coroutines.launch`, `kotlinx.coroutines.test.runCurrent`.

```kotlin
private fun row(id: Long, reason: String? = null) =
    SessionRow(id = id, tmuxName = "s$id", friendlyName = "session $id", hostAlias = "pine", attention = reason?.let { Attention(it) })

private class OpenFleet(vararg initial: SessionRow) : FleetState {
    override val sessions = MutableStateFlow(initial.toList())
    override val hosts = MutableStateFlow(listOf(HostRow("pine", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>(null)
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>()
    override suspend fun refresh() = Unit
}

class KeepSeenWhileOpenTest {

    @Test
    fun what_the_open_app_saw_is_not_news_to_the_background_check() = runTest {
        val prefs = FakePrefs()
        val poster = RecordingPoster()
        val fleet = OpenFleet(row(1, "waiting"))
        val watching = backgroundScope.launch { keepSeenWhileOpen(fleet, prefs, poster) }
        runCurrent()
        watching.cancel()

        val hub = Hub().apply { reply = { sse(okResult(rows(1L to "waiting"))) to HttpStatusCode.OK } }
        check(hub, prefs = prefs, poster = poster).once()

        assertEquals(emptyList(), poster.posted, "the person saw session 1 waiting in the app")
    }

    @Test
    fun the_open_app_posts_nothing_and_withdraws_what_resolves() = runTest {
        val prefs = FakePrefs().apply { writeSeen(mapOf(1L to null)) }
        val poster = RecordingPoster()
        val fleet = OpenFleet(row(1, "waiting"))
        backgroundScope.launch { keepSeenWhileOpen(fleet, prefs, poster) }
        runCurrent()

        fleet.sessions.value = listOf(row(1))
        runCurrent()

        assertEquals(emptyList(), poster.posted, "on screen, the list already says it")
        assertEquals(listOf(1L), poster.withdrawn)
        assertEquals(mapOf(1L to null), prefs.readSeen())
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.notify.KeepSeenWhileOpenTest'`
Expected: compilation FAIL, `Unresolved reference: keepSeenWhileOpen`.

- [ ] **Step 3: Implement** (append to `NeedsYouCheck.kt`; add imports `dev.claudefleet.mobile.data.FleetState`)

```kotlin
/**
 * While the app is open on a platform with no always-on watcher, keep the
 * seen set current — so the next background look compares against what the
 * person last saw, not against what the last background look saw — and take
 * down the notification of any session that stops needing them meanwhile.
 *
 * Posts nothing: on screen, the list already says it. Runs until cancelled.
 */
suspend fun keepSeenWhileOpen(fleet: FleetState, prefs: Prefs, poster: AlertPoster) {
    needsYouEvents(fleet, prefs.readSeen()) { prefs.writeSeen(it) }
        .collect { event -> if (event is NeedsYouResolved) poster.withdraw(event.sessionId) }
}
```

- [ ] **Step 4: Run the tests, then the whole JVM suite**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.notify.*'` → PASS.
Run: `./gradlew :shared:jvmTest` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/dev/claudefleet/mobile/notify/NeedsYouCheck.kt shared/src/commonTest/kotlin/dev/claudefleet/mobile/notify/NeedsYouCheckTest.kt
git commit -m "feat(notify): the open app keeps the seen set, so the background check does not repeat it"
```

---

### Task 7: `BackgroundNotifier` grows a note and a poster; wire them

**Files:**
- Modify: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/notify/BackgroundNotifier.kt`
- Modify: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt` (`FleetRoute`, after the `LifecycleStartEffect(repository)` block near line 449)
- Modify: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SettingsScreen.kt` (`NotifyRow`, near line 185)
- Test: `shared/src/commonTest/kotlin/dev/claudefleet/mobile/notify/BackgroundNoteTest.kt`
- Test: `shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/ForegroundSeenWiringTest.kt`

**Interfaces:**
- Consumes: `keepSeenWhileOpen` (Task 6), `AlertPoster` (Task 5).
- Produces (on `BackgroundNotifier`, all with defaults so Android and `NoBackgroundNotifier` compile unchanged):
  ```kotlin
  val note: StateFlow<String?>      // default: always null
  val poster: AlertPoster?          // default: null
  fun refreshNote()                 // default: no-op
  ```
  and `fun backgroundNote(notificationsAllowed: Boolean?, refreshAvailable: Boolean): String`.

- [ ] **Step 1: Write the failing tests**

`BackgroundNoteTest.kt`:

```kotlin
package dev.claudefleet.mobile.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackgroundNoteTest {

    @Test
    fun normally_it_says_ios_decides() {
        assertEquals(
            "iOS decides when to check — often within the hour, sometimes not at all. Keep the app open for prompt alerts.",
            backgroundNote(notificationsAllowed = true, refreshAvailable = true),
        )
        assertEquals(backgroundNote(true, true), backgroundNote(null, true), "not asked yet reads as the normal case")
    }

    @Test
    fun denied_notifications_win_over_everything() {
        assertEquals("Notifications are off for Fleet in iOS Settings.", backgroundNote(false, false))
    }

    @Test
    fun background_refresh_off_is_said() {
        assertEquals(
            "Background App Refresh is off, so iOS will not check while the app is closed.",
            backgroundNote(true, refreshAvailable = false),
        )
    }

    @Test
    fun a_platform_that_says_nothing_keeps_the_default_line() {
        assertNull(NoBackgroundNotifier.note.value)
        assertNull(NoBackgroundNotifier.poster)
    }
}
```

`ForegroundSeenWiringTest.kt`:

```kotlin
package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The foreground half of the iOS alerts lives in a composable no test can
 * render on the JVM, so its wiring is held here: started with the lifecycle,
 * only when there is a poster and alerts are on.
 */
class ForegroundSeenWiringTest {

    private val app: String by lazy { Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt").readText() }
    private val settings: String by lazy { Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SettingsScreen.kt").readText() }

    @Test
    fun the_open_app_keeps_the_seen_set_with_the_lifecycle() {
        val block = app.substringAfter("val poster = container.notifier.poster").substringBefore("val nav = remember")
        assertTrue("if (poster != null && alertsOn)" in block)
        assertTrue("LifecycleStartEffect(repository, poster)" in block)
        assertTrue("keepSeenWhileOpen(repository, container.prefs, poster)" in block)
        assertTrue("onStopOrDispose { job.cancel() }" in block)
    }

    @Test
    fun settings_shows_the_platforms_note_and_refreshes_it_on_resume() {
        val row = settings.substringAfter("private fun NotifyRow(").substringBefore("\n}\n")
        assertTrue("notifier.note.collectAsState()" in row)
        assertTrue("LifecycleResumeEffect(notifier)" in row && "notifier.refreshNote()" in row)
        assertTrue("note ?:" in row, "a null note keeps the existing line")
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.notify.BackgroundNoteTest' --tests 'dev.claudefleet.mobile.host.ForegroundSeenWiringTest'`
Expected: compilation FAIL (`backgroundNote`, `note`, `poster`); after that compiles, the wiring test fails.

- [ ] **Step 3: Extend `BackgroundNotifier.kt`**

Replace the interface and add the function; keep `NoBackgroundNotifier` as it is (the defaults cover it):

```kotlin
interface BackgroundNotifier {
    val supported: Boolean
    val enabled: StateFlow<Boolean>
    fun setEnabled(on: Boolean)

    /**
     * What to say under the switch instead of the default line, or null to keep
     * it. iOS uses it to be honest that the system, not the app, decides when a
     * check happens.
     */
    val note: StateFlow<String?> get() = NO_NOTE

    /**
     * Non-null on a platform with no always-on watcher. The open app then keeps
     * the seen set itself and withdraws through this (`keepSeenWhileOpen`).
     * Android's service keeps its own, so it leaves this null.
     */
    val poster: AlertPoster? get() = null

    /** Re-read whatever [note] depends on — called when Settings comes back on screen. */
    fun refreshNote() {}
}

private val NO_NOTE: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

/**
 * The iOS line under the switch. [notificationsAllowed] is null before the
 * person has been asked, which reads as the normal case.
 */
fun backgroundNote(notificationsAllowed: Boolean?, refreshAvailable: Boolean): String = when {
    notificationsAllowed == false -> "Notifications are off for Fleet in iOS Settings."
    !refreshAvailable -> "Background App Refresh is off, so iOS will not check while the app is closed."
    else -> "iOS decides when to check — often within the hour, sometimes not at all. Keep the app open for prompt alerts."
}
```

Update the interface's KDoc first paragraph's last sentence from "A platform that cannot do it ([supported] false — iOS, without a push service in the hub) offers nothing." to "iOS cannot keep a connection open in the background; it checks when the system wakes it (`NeedsYouCheck`) and says so through [note]."

- [ ] **Step 4: Wire `FleetRoute` in `App.kt`**

Directly after

```kotlin
    LifecycleStartEffect(repository) {
        repository.start()
        onStopOrDispose { repository.stop() }
    }
```

insert:

```kotlin
    // A platform with no always-on watcher (iOS): while the app is open, keep
    // the seen set the background check reads, so the first check after
    // closing the app does not announce what the person just looked at.
    val poster = container.notifier.poster
    val alertsOn by container.notifier.enabled.collectAsState()
    if (poster != null && alertsOn) {
        LifecycleStartEffect(repository, poster) {
            val job = scope.launch { keepSeenWhileOpen(repository, container.prefs, poster) }
            onStopOrDispose { job.cancel() }
        }
    }
```

Add imports `dev.claudefleet.mobile.notify.keepSeenWhileOpen` and `kotlinx.coroutines.launch` if missing.

- [ ] **Step 5: Show the note in `NotifyRow`**

In `SettingsScreen.kt`, inside `NotifyRow`, after `val ask = rememberNotificationPermission()` add:

```kotlin
    val note by notifier.note.collectAsState()
    LifecycleResumeEffect(notifier) {
        notifier.refreshNote()
        onPauseOrDispose { }
    }
```

and change the caption expression to:

```kotlin
                if (refused) "Notifications are turned off for this app in the system's settings."
                else note ?: "Waiting, stuck or failed — even with the app closed. Keeps a connection to the hub open, with its own notification.",
```

Add import `androidx.lifecycle.compose.LifecycleResumeEffect`.

- [ ] **Step 6: Run the tests, then the whole JVM suite and the Android compile**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.notify.BackgroundNoteTest' --tests 'dev.claudefleet.mobile.host.ForegroundSeenWiringTest'` → PASS.
Run: `./gradlew :shared:jvmTest :androidApp:compileDebugKotlin` → BUILD SUCCESSFUL. Android's `AndroidBackgroundNotifier` must compile unchanged.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/dev/claudefleet/mobile/notify/BackgroundNotifier.kt shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SettingsScreen.kt shared/src/commonTest/kotlin/dev/claudefleet/mobile/notify/BackgroundNoteTest.kt shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/ForegroundSeenWiringTest.kt
git commit -m "feat(notify): a platform note under the switch, and the open app's seen-set writer"
```

---

### Task 8: The iOS glue in Kotlin

**Files:**
- Create: `shared/src/iosMain/kotlin/dev/claudefleet/mobile/notify/IosAlertPoster.kt`
- Create: `shared/src/iosMain/kotlin/dev/claudefleet/mobile/notify/NeedsYouRefresh.kt`
- Create: `shared/src/iosMain/kotlin/dev/claudefleet/mobile/notify/IosBackgroundNotifier.kt`
- Modify: `shared/src/iosMain/kotlin/dev/claudefleet/mobile/notify/NotificationPermission.ios.kt`
- Modify: `shared/src/iosMain/kotlin/dev/claudefleet/mobile/MainViewController.kt`
- Test: `shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/IosBackgroundTaskTest.kt` (created here, extended in Task 9)

**Interfaces:**
- Consumes: `AlertPoster`, `needsYouContent`, `needsYouId`, `NEEDS_YOU_ID_PREFIX`, `NEEDS_YOU_SESSION_KEY` (Task 5); `NeedsYouCheck` (Task 5); `BackgroundNotifier.note/poster/refreshNote`, `backgroundNote` (Task 7).
- Produces (Swift sees these through `MainViewControllerKt`):
  ```kotlin
  fun onOpenSession(sessionId: Long)
  fun startNeedsYouCheck(onDone: (Boolean) -> Unit): NeedsYouRun   // NeedsYouRun.cancel()
  fun scheduleNeedsYouRefreshIfEnabled()
  ```
  and `const val NEEDS_YOU_TASK = "dev.claudefleet.mobile.needs-you"` in `NeedsYouRefresh.kt`.

This code is only compiled by the Kotlin/Native link, which needs macOS: `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`. Compiling iosMain on Linux to a klib does not prove it links (see the repo skill).

- [ ] **Step 1: Write the failing scan test**

```kotlin
package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The background check's iOS half: nothing it hands to Swift may throw, and
 * the notifier must be the one the app container holds.
 */
class IosBackgroundTaskTest {

    private val main: String by lazy { Repo.file("shared/src/iosMain/kotlin/dev/claudefleet/mobile/MainViewController.kt").readText() }
    private val refresh: String by lazy { Repo.file("shared/src/iosMain/kotlin/dev/claudefleet/mobile/notify/NeedsYouRefresh.kt").readText() }

    @Test
    fun the_task_identifier_is_declared_once_in_kotlin() {
        assertTrue("const val NEEDS_YOU_TASK = \"dev.claudefleet.mobile.needs-you\"" in refresh)
    }

    @Test
    fun swift_gets_a_handle_not_a_suspend_function() {
        assertTrue("fun startNeedsYouCheck(onDone: (Boolean) -> Unit): NeedsYouRun" in main)
        assertTrue("suspend fun" !in main, "an exported suspend function must start on the main thread and cannot be cancelled from Swift")
        assertTrue("@Throws" !in main, "nothing handed to Swift may throw")
    }

    @Test
    fun the_container_holds_the_ios_notifier() {
        assertTrue("notifier = iosNotifier" in main)
        assertTrue("fun onOpenSession(sessionId: Long)" in main)
        assertTrue("fun scheduleNeedsYouRefreshIfEnabled()" in main)
    }
}
```

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.host.IosBackgroundTaskTest'` → FAIL (`NeedsYouRefresh.kt` missing).

- [ ] **Step 2: `NeedsYouRefresh.kt`**

```kotlin
@file:OptIn(ExperimentalForeignApi::class)

package dev.claudefleet.mobile.notify

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * The background check's identifier. Spelled the same in `AppDelegate.swift`
 * (which registers it) and in `Info.plist`'s `BGTaskSchedulerPermittedIdentifiers`
 * (without which registering it crashes); `IosBackgroundTaskTest` holds all three equal.
 */
const val NEEDS_YOU_TASK = "dev.claudefleet.mobile.needs-you"

/** iOS treats it as "not before"; when it actually runs is the system's call. */
private const val EARLIEST_SECONDS = 15.0 * 60

/**
 * Ask for the next background check. Null when the request was accepted, else
 * the scheduler's reason, which on a simulator is always "unavailable".
 * Submitting replaces any pending request with the same identifier.
 */
fun submitNeedsYouRefresh(): String? = memScoped {
    val request = BGAppRefreshTaskRequest(identifier = NEEDS_YOU_TASK)
    request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(EARLIEST_SECONDS)
    val error = alloc<ObjCObjectVar<NSError?>>()
    if (BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error.ptr)) null
    else error.value?.localizedDescription ?: "the scheduler refused without saying why"
}

fun cancelNeedsYouRefresh() {
    BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(NEEDS_YOU_TASK)
}
```

- [ ] **Step 3: `IosAlertPoster.kt`**

```kotlin
package dev.claudefleet.mobile.notify

import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * "Needs you" notifications through `UNUserNotificationCenter`: one per
 * session (its identifier replaces an older one), all in one thread so iOS
 * groups them, each carrying its session for the tap.
 *
 * The center is looked up per call, never at construction: a process with no
 * app bundle (a bare Kotlin/Native test binary) has no center, and asking for
 * one there is a crash.
 */
class IosAlertPoster : AlertPoster {
    private val center: UNUserNotificationCenter get() = UNUserNotificationCenter.currentNotificationCenter()

    override fun post(alert: NeedsYouAlert) {
        val c = needsYouContent(alert)
        val content = UNMutableNotificationContent().apply {
            setTitle(c.title)
            setBody(c.body)
            setThreadIdentifier(c.thread)
            setUserInfo(mapOf<Any?, Any?>(NEEDS_YOU_SESSION_KEY to c.sessionId))
            setSound(UNNotificationSound.defaultSound)
        }
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(c.id, content, null), withCompletionHandler = null)
    }

    override fun withdraw(sessionId: Long) {
        center.removeDeliveredNotificationsWithIdentifiers(listOf(needsYouId(sessionId)))
    }

    /** Every delivered "needs you" notification — when the person turns alerts off. */
    fun withdrawAll() {
        center.getDeliveredNotificationsWithCompletionHandler { delivered ->
            val ids = delivered.orEmpty()
                .mapNotNull { (it as? UNNotification)?.request?.identifier }
                .filter { it.startsWith(NEEDS_YOU_ID_PREFIX) }
            if (ids.isNotEmpty()) center.removeDeliveredNotificationsWithIdentifiers(ids)
        }
    }
}
```

- [ ] **Step 4: `IosBackgroundNotifier.kt`**

```kotlin
package dev.claudefleet.mobile.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundRefreshStatus
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNUserNotificationCenter

/**
 * [BackgroundNotifier] on iOS: the person's choice in `NSUserDefaults`, a
 * `BGAppRefreshTask` requested while it is on, and an honest [note], because
 * iOS — not the app — decides when the check runs.
 */
class IosBackgroundNotifier(
    val alertPoster: IosAlertPoster,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : BackgroundNotifier {
    private val _enabled = MutableStateFlow(defaults.boolForKey(KEY))
    private val _note = MutableStateFlow<String?>(backgroundNote(notificationsAllowed = null, refreshAvailable = true))

    override val supported: Boolean = true
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override val note: StateFlow<String?> = _note.asStateFlow()
    override val poster: AlertPoster? get() = alertPoster

    override fun setEnabled(on: Boolean) {
        defaults.setBool(on, forKey = KEY)
        _enabled.value = on
        if (on) {
            // On a simulator this always fails with "unavailable"; the choice
            // still stands, and a device schedules it.
            submitNeedsYouRefresh()?.let { println("needs-you: refresh not scheduled: $it") }
        } else {
            cancelNeedsYouRefresh()
            alertPoster.withdrawAll()
        }
    }

    /** Main thread only: `backgroundRefreshStatus` is UIKit. Settings calls it on resume. */
    override fun refreshNote() {
        val refresh = UIApplication.sharedApplication.backgroundRefreshStatus ==
            UIBackgroundRefreshStatus.UIBackgroundRefreshStatusAvailable
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val allowed = settings?.let { it.authorizationStatus != UNAuthorizationStatusDenied }
            _note.value = backgroundNote(allowed, refresh)
        }
    }

    private companion object {
        const val KEY = "needs_you"
    }
}
```

- [ ] **Step 5: Ask for real in `NotificationPermission.ios.kt`**

```kotlin
package dev.claudefleet.mobile.notify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS asks once; after a refusal it answers no without asking, and only the
 * Settings app can change that — which is what the note under the switch says.
 * The answer arrives on an arbitrary queue and goes back to the main one,
 * where Compose state may be written.
 */
@Composable
actual fun rememberNotificationPermission(): (onResult: (Boolean) -> Unit) -> Unit =
    remember<(onResult: (Boolean) -> Unit) -> Unit> {
        { onResult ->
            UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
                UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
            ) { granted, _ -> dispatch_async(dispatch_get_main_queue()) { onResult(granted) } }
        }
    }
```

- [ ] **Step 6: Wire `MainViewController.kt`**

1. In `iosContainer`'s `AppContainer(...)`, add the argument `notifier = iosNotifier,`.
2. Add, after `iosContainer`:

```kotlin
/** The one notifier, shared by the container (Settings, the open app) and the background check. */
private val iosNotifier: IosBackgroundNotifier by lazy { IosBackgroundNotifier(IosAlertPoster()) }

/**
 * A tapped "needs you" notification, from `AppDelegate`'s notification
 * delegate. Held by the container until the paired screens take it — a tap can
 * start the app cold.
 */
fun onOpenSession(sessionId: Long) {
    iosContainer.onOpenSession(sessionId)
}

/** A running background check; `AppDelegate` cancels it when iOS's time is up. */
class NeedsYouRun internal constructor(private val job: Job) {
    fun cancel() {
        job.cancel()
    }
}

/**
 * Start one background check for `AppDelegate`'s `BGAppRefreshTask` handler.
 * [onDone] is called exactly once — true when the check finished, false when
 * it was cancelled — on an arbitrary thread; `setTaskCompleted` is safe from
 * any.
 *
 * A handle rather than a suspend function on purpose: an exported suspend
 * function must start on the main thread, and cancelling the Swift `Task`
 * awaiting one does not cancel the coroutine behind it.
 */
fun startNeedsYouCheck(onDone: (Boolean) -> Unit): NeedsYouRun {
    val job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        if (iosNotifier.enabled.value) {
            NeedsYouCheck(iosContainer.session, iosContainer.prefs, iosNotifier.alertPoster).once()
        }
    }
    job.invokeOnCompletion { cause -> onDone(cause == null) }
    return NeedsYouRun(job)
}

/** Request the next check if alerts are on — from the task handler, and whenever the app goes to the background. */
fun scheduleNeedsYouRefreshIfEnabled() {
    if (iosNotifier.enabled.value) submitNeedsYouRefresh()?.let { println("needs-you: refresh not scheduled: $it") }
}
```

3. Imports: `dev.claudefleet.mobile.notify.IosAlertPoster`, `dev.claudefleet.mobile.notify.IosBackgroundNotifier`, `dev.claudefleet.mobile.notify.NeedsYouCheck`, `dev.claudefleet.mobile.notify.submitNeedsYouRefresh`, `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.Job`, `kotlinx.coroutines.SupervisorJob`, `kotlinx.coroutines.launch`.
4. In the file's KDoc, replace the paragraph headed **Linked, never run.** with: "**Run on a device since 2026-10.** See `README.md` → *What a Mac still has to check* for what a device has and has not shown."

- [ ] **Step 7: Run the scan test and the JVM suite**

Run: `./gradlew :shared:jvmTest` → BUILD SUCCESSFUL (including `IosBackgroundTaskTest`, 3 tests).

- [ ] **Step 8: Link on macOS (needs Xcode)**

Run: `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 :shared:iosSimulatorArm64Test` → BUILD SUCCESSFUL.

If a platform binding's name differs from the code above (for example `setTitle` vs a `title` property, `defaultSound` vs `defaultSound()`, or the `BGAppRefreshTaskRequest(identifier =)` constructor), the link error names the actual declaration; use that and change nothing else. Without Xcode: record "link proven in CI, Task 11" and do not mark this step done.

- [ ] **Step 9: Commit**

```bash
git add shared/src/iosMain shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/IosBackgroundTaskTest.kt
git commit -m "feat(ios): post, withdraw and schedule needs-you checks; a real notification permission"
```

---

### Task 9: The Swift host — `AppDelegate`, `Info.plist`, the project

**Files:**
- Create: `iosApp/iosApp/AppDelegate.swift`
- Modify: `iosApp/iosApp/iOSApp.swift`
- Modify: `iosApp/iosApp/Info.plist`
- Modify: `iosApp/iosApp.xcodeproj/project.pbxproj`
- Modify: `shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/IosHostTest.kt` (`the_host_is_two_files`)
- Modify: `shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/IosBackgroundTaskTest.kt`
- Modify: `iosApp/iosAppTests/KeychainRoundTripTests.swift` (append `NeedsYouToggleTests`; a new file would need its own pbxproj entries for no gain)

**Interfaces:**
- Consumes: `MainViewControllerKt.startNeedsYouCheck(onDone:)`, `.scheduleNeedsYouRefreshIfEnabled()`, `.onOpenSession(sessionId:)` (Task 8).

- [ ] **Step 1: Extend the scan tests (failing)**

Append to `IosBackgroundTaskTest`:

```kotlin
    private val plist: String by lazy { Repo.file("iosApp/iosApp/Info.plist").readText() }
    private val delegate: String by lazy { Repo.file("iosApp/iosApp/AppDelegate.swift").readText() }
    private val app: String by lazy { Repo.file("iosApp/iosApp/iOSApp.swift").readText() }
    private val project: String by lazy { Repo.file("iosApp/iosApp.xcodeproj/project.pbxproj").readText() }

    @Test
    fun the_identifier_is_the_same_in_kotlin_swift_and_the_plist() {
        val id = "dev.claudefleet.mobile.needs-you"
        assertTrue(Regex("""<key>BGTaskSchedulerPermittedIdentifiers</key>\s*<array>\s*<string>${Regex.escape(id)}</string>""").containsMatchIn(plist))
        assertTrue("forTaskWithIdentifier: \"$id\"" in delegate)
    }

    @Test
    fun background_fetch_is_declared_and_push_is_not() {
        assertTrue(Regex("""<key>UIBackgroundModes</key>\s*<array>\s*<string>fetch</string>""").containsMatchIn(plist))
        assertTrue("remote-notification" !in plist)
        val entitlements = Repo.root.walkTopDown().filter { it.extension == "entitlements" }.toList()
        assertTrue(entitlements.none { "aps-environment" in it.readText() }, "push is the next sub-project, not this one")
    }

    @Test
    fun the_delegate_registers_at_launch_and_routes_taps() {
        assertTrue("didFinishLaunchingWithOptions" in delegate)
        assertTrue("UNUserNotificationCenter.current().delegate = self" in delegate)
        assertTrue("userInfo[\"sessionId\"]" in delegate, "the key is NEEDS_YOU_SESSION_KEY")
        assertTrue("MainViewControllerKt.onOpenSession" in delegate)
        assertTrue("task.expirationHandler = { run.cancel() }" in delegate)
        assertTrue("completionHandler([])" in delegate, "no banner while the app is on screen")
    }

    @Test
    fun the_app_uses_the_delegate_and_schedules_on_background() {
        assertTrue("@UIApplicationDelegateAdaptor(AppDelegate.self)" in app)
        assertTrue("MainViewControllerKt.scheduleNeedsYouRefreshIfEnabled()" in app)
    }

    @Test
    fun the_delegate_is_compiled_into_the_app() {
        assertTrue("/* AppDelegate.swift in Sources */ = {isa = PBXBuildFile;" in project)
        val appSources = project.substringAfter("5FE0A10000000000000000AD /* Sources */ = {").substringBefore("};")
        assertTrue("AppDelegate.swift in Sources" in appSources)
    }
```

In `IosHostTest.kt`, change `the_host_is_two_files` to:

```kotlin
    /**
     * The host is three files and stays three files.
     *
     * Not a style rule: every screen, view model and network call is in
     * `:shared` so that Android and iOS cannot drift. The third file,
     * `AppDelegate.swift`, exists only because iOS requires background-task
     * registration before launch finishes and a notification delegate, both of
     * which belong to an app delegate; the check it runs is shared Kotlin.
     * Any fourth file deserves someone's attention.
     */
    @Test
    fun the_host_is_three_files() {
        assertEquals(
            listOf("AppDelegate.swift", "ContentView.swift", "iOSApp.swift"),
            Repo.shippedSwift.map { it.name }.sorted(),
            "app logic belongs in shared/src/commonMain, not in the iOS host",
        )
    }
```

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.host.IosBackgroundTaskTest' --tests 'dev.claudefleet.mobile.host.TheIosHostAsksForNothingAtLaunchTest'` → FAIL.

- [ ] **Step 2: `iosApp/iosApp/AppDelegate.swift`**

```swift
import BackgroundTasks
import Shared
import UIKit
import UserNotifications

/// The two things iOS will only take from an app delegate, and nothing else.
///
/// 1. Background-task registration, which must happen before launch finishes.
///    The check it runs is shared Kotlin (`NeedsYouCheck`); this file only
///    hands iOS's thirty seconds to it and takes them back when they run out.
/// 2. The notification delegate: no banner while the app is on screen (the
///    list already says it), and a tap opens the session it was about.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Same identifier as NEEDS_YOU_TASK in NeedsYouRefresh.kt and
        // BGTaskSchedulerPermittedIdentifiers in Info.plist; IosBackgroundTaskTest
        // holds all three equal. A mismatch with the plist crashes here.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: "dev.claudefleet.mobile.needs-you", using: nil) { task in
            AppDelegate.run(task)
        }
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    private static func run(_ task: BGTask) {
        // Ask for the next one first, so a check that runs out of time does
        // not end the chain.
        MainViewControllerKt.scheduleNeedsYouRefreshIfEnabled()
        let run = MainViewControllerKt.startNeedsYouCheck { finished in
            task.setTaskCompleted(success: finished.boolValue)
        }
        task.expirationHandler = { run.cancel() }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if let id = response.notification.request.content.userInfo["sessionId"] as? NSNumber {
            MainViewControllerKt.onOpenSession(sessionId: id.int64Value)
        }
        completionHandler()
    }
}
```

- [ ] **Step 3: `iOSApp.swift`**

```swift
import SwiftUI
import Shared

/// The iOS host, and deliberately almost nothing.
///
/// Every screen, every view model and all of the networking live in `:shared`
/// and are written in Kotlin; this target exists to give them a bundle, an
/// `Info.plist` and a launch, plus the app delegate iOS requires for background
/// checks and notification taps (`AppDelegate.swift`). If a change to the app is
/// being made here rather than in `shared/src/commonMain`, it is probably in the
/// wrong place.
@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        // A pending request must exist whenever the app is away, even if the
        // last check's own resubmission failed.
        .onChange(of: phase) { newPhase in
            if newPhase == .background {
                MainViewControllerKt.scheduleNeedsYouRefreshIfEnabled()
            }
        }
    }
}
```

- [ ] **Step 4: `Info.plist`**

Insert before `<key>UILaunchScreen</key>`:

```xml
	<!--
	  The "needs you" check when the app is closed: iOS wakes the app for about
	  thirty seconds at a time of its choosing and AppDelegate hands that to
	  NeedsYouCheck. The identifier must match AppDelegate.swift and
	  NEEDS_YOU_TASK in NeedsYouRefresh.kt (IosBackgroundTaskTest); registering
	  an identifier missing from this list crashes at launch. `fetch` is the
	  background mode BGAppRefreshTask needs. No push: that is the next
	  sub-project, and it needs a paid-only entitlement this app does not have.
	-->
	<key>BGTaskSchedulerPermittedIdentifiers</key>
	<array>
		<string>dev.claudefleet.mobile.needs-you</string>
	</array>
	<key>UIBackgroundModes</key>
	<array>
		<string>fetch</string>
	</array>

```

- [ ] **Step 5: `project.pbxproj`**

1. PBXBuildFile section, add:
   ```
   		5FE0A10000000000000000B8 /* AppDelegate.swift in Sources */ = {isa = PBXBuildFile; fileRef = 5FE0A10000000000000000B9 /* AppDelegate.swift */; };
   ```
2. PBXFileReference section, add:
   ```
   		5FE0A10000000000000000B9 /* AppDelegate.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = AppDelegate.swift; sourceTree = "<group>"; };
   ```
3. Group `5FE0A10000000000000000A4 /* iosApp */` children, add first:
   ```
   				5FE0A10000000000000000B9 /* AppDelegate.swift */,
   ```
4. Sources phase `5FE0A10000000000000000AD`, files, add:
   ```
   				5FE0A10000000000000000B8 /* AppDelegate.swift in Sources */,
   ```

- [ ] **Step 6: An XCTest for the simulator's refusal**

Append to `iosApp/iosAppTests/KeychainRoundTripTests.swift`, as a new class in the same file:

```swift
/// On a simulator `BGTaskScheduler.submit` always fails with `unavailable`.
/// Turning alerts on must survive that: the choice stands, and a device
/// schedules the check.
final class NeedsYouToggleTests: XCTestCase {
    func testTurningAlertsOnSurvivesASchedulerThatRefuses() {
        let notifier = IosBackgroundNotifier(alertPoster: IosAlertPoster(), defaults: UserDefaults(suiteName: "needs-you-tests")!)
        notifier.setEnabled(on: true)
        XCTAssertTrue((notifier.enabled.value as! KotlinBoolean).boolValue)
        notifier.setEnabled(on: false)
        XCTAssertFalse((notifier.enabled.value as! KotlinBoolean).boolValue)
    }
}
```

(`setEnabled(on: false)` calls `withdrawAll()`, which needs the app's notification center; this test runs hosted by the app, so the center exists.)

- [ ] **Step 7: Run the scans and the JVM suite**

Run: `./gradlew :shared:jvmTest` → BUILD SUCCESSFUL.

- [ ] **Step 8: Build and test the app on a simulator (needs Xcode)**

From `iosApp/`, exactly the CI job's two commands (`.github/workflows/ci.yml`, job `macos`): the unsigned `build`, then `xcodebuild test` with ad-hoc signing on an available simulator. Expected: `** BUILD SUCCEEDED **` and `** TEST SUCCEEDED **`, including `NeedsYouToggleTests`. Without Xcode: "proven in CI, Task 11".

- [ ] **Step 9: Commit**

```bash
git add iosApp shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/IosHostTest.kt shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/IosBackgroundTaskTest.kt
git commit -m "feat(ios): an app delegate for the background check and notification taps"
```

---

### Task 10: The device — run the checklist, fix what it finds, rewrite the README section

**Operator-assisted.** It needs the iPhone, a person to tap system dialogs, and the hub on the NAS. The agent drives the Mac side and records results; each defect found becomes its own fix commit with a test where any host can hold one.

**Files:**
- Modify: `README.md` (section *What a Mac still has to check*)
- Modify: whatever a finding requires

- [ ] **Step 1: Install a development build**

Run: `scripts/ios-device.sh`. Expected: `ios-device: dev.claudefleet.mobile is running on <udid>`. If it prints Developer Mode or Trust instructions, the operator does them and the script is run again.

- [ ] **Step 2: Pair**

On the NAS (see the `fleet-hub-deploy-nas` notes): `fleet-hub pair --name iphone`. The operator scans the QR with the app (item 5).

- [ ] **Step 3: Work through items 1–8** of the spec's *The device checklist*, recording for each: date, iOS version, device model, result, and the evidence (command output, screenshot path, `xctrace` summary).

  - Item 1: `plutil -p build/ios-device/Build/Products/Debug-iphoneos/iosApp.app/Info.plist | grep -E 'Camera|ITSApp|BGTask'`.
  - Item 2: background the app; on the NAS, the hub's subscriber count for client `iphone` drops (`fleet-hub client list`, *last seen*, or the `/metrics` subscriber gauge); foreground it and the list refills.
  - Item 3: lock the phone, force a background check (Step 4), and confirm in the device console that the Keychain read did not return `errSecInteractionNotAllowed`.
  - Item 4: `xcrun xctrace record --template Leaks --device <udid> --attach iosApp --time-limit 60s` while pairing and forgetting once; the same for Zombies. Save the traces outside the repo.
  - Items 5–8 by hand.

- [ ] **Step 4: Item 9: force a background check**

In Xcode, run the app on the phone under the debugger, enable alerts in Settings, background the app, pause in the debugger and run:

```
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"dev.claudefleet.mobile.needs-you"]
```

then continue. With a session made to wait first (send it a prompt that asks a question), a notification appears; tapping it opens that session. Run it twice: the second time posts nothing.

- [ ] **Step 5: Item 10, after Task 11's first real upload:** install the TestFlight build over the development build; the app is still paired.

- [ ] **Step 6: Item 11: one night**

Alerts on, phone used normally, app closed. The next morning, read the device log for `needs-you` lines (Console.app filtered on the process `iosApp`) and record each run's timestamp.

- [ ] **Step 7: Rewrite the README section**

Replace the section's opening paragraph and the numbered list with the results. Each item states what the device showed, with the date and iOS version, or what is still open and why. Remove items that are now proven from the "still has to check" list and move them under a new `### What a device has shown` subsection. Keep the literal strings `What a Mac still has to check`, `NSCameraUsageDescription` and `ComposeUIViewController` in the README (`TheIosHostIsUnbuiltTest` requires them). Add a short `### iOS releases` subsection: TestFlight on every `vX.Y.Z` via `.github/workflows/testflight.yml`, the four secrets, and `scripts/ios-device.sh` for development builds.

- [ ] **Step 8: Run the JVM suite and commit**

Run: `./gradlew :shared:jvmTest` → BUILD SUCCESSFUL.

```bash
git add README.md
git commit -m "docs: what a real iPhone showed, and how iOS builds ship"
```

---

### Task 11: CI, the dry run, and the first upload

**Controller-only** (pushes and dispatches need the user's go).

- [ ] **Step 1: Push the branch and open a draft PR** against `main`, once the user says so. Then `gh workflow run ci.yml --ref feat/ios-testflight -R martin-janci/fleet-mobile` if the PR trigger has not started it. Expected: `build` (Linux, JVM suite) and `macos` (simulator build, `iosSimulatorArm64Test`, XCTest) green. Any step marked "proven in CI" in Tasks 1, 5, 8, 9 is checked off here, with the run URL.

- [ ] **Step 2: Dry-run the signing.** After the operator has added the four secrets: `gh workflow run testflight.yml --ref feat/ios-testflight -R martin-janci/fleet-mobile`. Expected: archive and export succeed, and the log shows `destination export` (no upload).

- [ ] **Step 3: First upload.** After merge, the next `scripts/release-mobile.sh X.Y.Z` from claude-fleet tags fleet-mobile. The tag runs `release.yml` (APK) and `testflight.yml` (upload). Expected: the build shows in App Store Connect → TestFlight within 30 minutes, installable by the internal tester. Then do Task 10, Step 5.

---

## Self-review notes

- **Spec coverage:**
  - Signing → Task 1. Device loop → Task 2. TestFlight → Task 4 (+ delta 1).
  - `ITSAppUsesNonExemptEncryption` → Task 4. Icon (checklist 8) → Task 3.
  - `NeedsYouCheck` and its failure table → Task 5. Foreground seen set → Tasks 6, 7.
  - iOS poster, notifier, permission and note → Tasks 7, 8. Swift entry points → Task 8 (+ delta 2). Swift host and `Info.plist` keys → Task 9.
  - Checklist 1–11 → Task 10. Testing table → tests in Tasks 1–9 plus Task 11's CI and dry run. *What cannot be proven* → Task 10, Step 6.
- **Prerequisites** (enrolment, app record, API key, secrets) are the operator's and gate only Task 11 Steps 2–3. Tasks 0–10 do not wait for them, but Task 2's first device build does need the paid team to be active.
