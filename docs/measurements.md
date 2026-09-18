# Measurements

Things that were measured rather than assumed, with the command that measured
them. They are here because each one has already been got wrong once by someone
reasoning from the documentation, and because the next person to wonder should
be able to check rather than re-derive.

---

## The app declares two permissions and the APK asks for four

`androidApp/src/main/AndroidManifest.xml` asks for `INTERNET` and `CAMERA`, and
nothing else. The installed package asks for four:

```console
$ aapt2 dump permissions androidApp/build/outputs/apk/debug/androidApp-debug.apk
package: dev.claudefleet.mobile
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.CAMERA'
permission: dev.claudefleet.mobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='dev.claudefleet.mobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
```

Identical for the release variant.

- `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is `androidx.core`'s own
  self-signature permission, for its internal receiver registration.
- **`ACCESS_NETWORK_STATE` is merged in by `androidx.media3:media3-common:1.9.0`**,
  which the app never asked for. From the merger's own blame report:

  ```console
  $ grep -A1 ACCESS_NETWORK_STATE \
      androidApp/build/outputs/logs/manifest-merger-debug-report.txt
  uses-permission#android.permission.ACCESS_NETWORK_STATE
  ADDED from [androidx.media3:media3-common:1.9.0] …/AndroidManifest.xml:22:5-79
  ```

Both are install-time permissions and neither is shown to the person using the
app, but the store listing shows them. `TheAndroidManifestTest` is therefore
worded as a claim about the app's *own* manifest, with this measured list in its
KDoc: a test that claimed the shipped list would have been false.

---

## Where `ACCESS_NETWORK_STATE` comes from, and how that was checked

`media3-common` arrives through `camera-view -> camera-video -> media3-container
-> media3-common`.

**`./gradlew dependencyInsight` makes this look untrue.** Its reverse tree shows
`camera-video` reached from `camera-core`, `camera-camera2`,
`camera-camera2-pipe` and `camera-lifecycle` as well, which would mean removing
`camera-view` changes nothing. Those are *constraint* edges: CameraX 1.6 is
published as an atomic module group, so every module constrains the versions of
all the others, and `dependencyInsight` prints constraints and dependencies in
the same tree. The selection reasons say so — "By constraint: camera-lifecycle
is in atomic group androidx.camera" — but it is easy to read past.

The module metadata settles it. Only `camera-view` has a real dependency edge:

```console
$ python3 - <<'PY'
import json
for m in ["camera-lifecycle", "camera-view", "camera-core",
          "camera-camera2", "camera-camera2-pipe"]:
    path = f"~/.gradle/caches/modules-2/files-2.1/androidx.camera/{m}/…/{m}-1.6.2.module"
    d = json.load(open(path))
    v = next(v for v in d["variants"]
             if v["name"] == "releaseVariantReleaseRuntimePublication")
    print(m, "dep on camera-video:",
          any(x["module"] == "camera-video" for x in v.get("dependencies", [])))
PY
camera-lifecycle    dep on camera-video: False
camera-view         dep on camera-video: True
camera-core         dep on camera-video: False
camera-camera2      dep on camera-video: False
camera-camera2-pipe dep on camera-video: False
```

`camera-view` is also the only source of `appcompat`, `fragment`,
`lifecycle-livedata` and `viewfinder-core`.

---

## The camera-view deferral

`androidx.camera:camera-view` is used for exactly **one class**, `PreviewView`,
which draws the viewfinder in `QrScanner.android.kt`. It costs:

| What | Size |
|---|---|
| `appcompat` / `fragment` / `viewpager` / `loader` view stack | +0.67 MB |
| `camera-video -> media3 -> Guava` | +1.63 MB |
| and one extra permission on the store listing | `ACCESS_NETWORK_STATE` |

About **2.3 MB and a permission, for video recording this app does not do.**

Replacing `PreviewView` with a plain `SurfaceView` and
`Preview.SurfaceProvider` would reclaim all of it. **It was not done, and the
reason is the honest one:** whether the replacement actually renders a camera
preview is exactly the question no headless box can answer, and removing a
library so that a permission goes away is how a `SecurityException` gets
shipped to users. It is left for someone with a device, with the numbers above
so that they do not have to find them again.

---

## APK size

**Measure it by building the commits in one clone with one toolchain.** Two
agents once reported 14 MB and 19.4 MB for what turned out to be the same
artifact: one figure was decimal MB and the other was MiB. Comparing numbers out
of two reports is how that happens; comparing bytes out of one clone is how it
does not.

```console
$ stat -c '%s' androidApp/build/outputs/apk/debug/androidApp-debug.apk
20294236
```

Built in one clone, one toolchain, at three points in the history:

| Commit | Debug APK | |
|---|---|---|
| `b05eb4e` | 14,252,397 B | the skeleton, before anything |
| `adf6d81` | 20,292,347 B | after the UI, the scanner and the secure store |
| `e6382dc` | 20,294,236 B | after the Android host — **+1,889 B**, icons and a button |

Task 8 adds nothing: the APK is byte-identical at 20,294,236 B, because the iOS
framework declaration and the instrumentation source set produce no Android
artifact.

Where the 6.04 MB since the skeleton went, from `apkanalyzer dex packages` on
both APKs:

| | |
|---|---|
| CameraX | +1.87 MB |
| **Tink + Gson** | +1.38 MB — that is `androidx.security:security-crypto`, i.e. `EncryptedSharedPreferences`. **Not** the scanner. |
| Guava | +1.19 MB |
| AppCompat / fragment / viewpager / loader | +0.67 MB |
| media3 | +0.44 MB |
| ZXing | +0.35 MB |
| **the app's own five screens** | **+0.22 MB** |
| everything else | +0.17 MB |

The size of this app is almost entirely its dependencies, and the single
largest line is the secure store rather than anything to do with the camera.

---

## Why ZXing and not ML Kit

The plan named ML Kit. Measured on the debug runtime classpath,
`com.google.mlkit:barcode-scanning:17.3.0` pulls **seventeen** `play-services`
entries — including `transport-backend-cct`, Google's Cloud Client Telemetry
uploader — and took the debug APK from 14 MB to **40.1 MB**. The
bundled/unbundled distinction is only about where the *model* lives; the Task
API underneath is Play Services either way.

After the swap: **19.4 MiB** (the 20.3 MB above) and **zero**
play-services / firebase / transport-backend entries, on the release runtime
classpath as well as the debug one.

The deciding argument was not the size. A phone paired to a self-hosted hub
should not have to carry a Google telemetry client to read one QR code, and may
have no Play Services at all.

---

## The Gradle flakes

Three infrastructure failures were seen during earlier tasks, each green on a
clean re-run, all with `org.gradle.caching=true` and `org.gradle.parallel=true`:

1. `mergeDexRelease` — "could not pack tree 'd8Metadata'"
2. `jvmTest` — "Cannot access output property 'binaryResultsDirectory'"
3. a bare `java.util.concurrent.TimeoutException` with no message

Two questions had to be answered before they could be called a known flake
rather than a mystery: do they survive with caching disabled, and do they appear
at all when only one build is running.

### The experiment

Every run is `./gradlew build --rerun-tasks` at the same commit, raw output to
its own log. Five arms:

| Arm | Setup | Runs | Failures |
|---|---|---|---|
| A | caching **on**, one build at a time, its own clone | 10 | **0** |
| B | caching **off** (`--no-build-cache`), one at a time, its own clone | 10 | **0** |
| C | caching on, **two concurrent** builds in **two clones** (separate build directories, shared Gradle user home) | 10 | **0** |
| D | caching on, **two concurrent** builds in **one clone** (shared build directory) | 10 | **5** |
| E | caching **off**, two concurrent builds in one clone | 10 | **4** |

### What arm D produced

Five failures in ten runs, in three signatures, none of which appeared anywhere
else in fifty-odd runs:

```
Execution failed for task ':androidApp:mergeExtDexDebug'.
> Failed to store cache entry … Could not pack tree 'outputDir':
  java.io.IOException: Request to write '65536' bytes exceeds size in header of
  '10092544' bytes for entry 'tree-outputDir/classes.dex'
```

```
Execution failed for task ':androidApp:mergeDebugResources'.
> java.io.IOException: Unable to delete directory '…/incremental/debug/mergeDebugResources'
  Failed to delete some children. This might happen because a process has files
  open or has its working directory set in the target directory.
```

```
Execution failed for task ':shared:compileCommonMainKotlinMetadata'.
> Internal compiler error. / Compilation error.
```

The first is the ledger's signature 1, and its *message* names the mechanism
exactly: the archiver wrote a header saying the file was 10,092,544 bytes and
then found more bytes to write, because another build was rewriting
`classes.dex` while this one was packing it into the cache. The second says the
same thing without the cache involved at all — one build deleting a directory
the other is filling. The third is the "1,240 unresolved references to code that
plainly exists" that a previous task nearly filed as a Gradle bug.

### What arm E produced: the same collision with the cache turned off

Four failures in ten runs, and **not one of them is a cache-pack failure** —
`grep -l "Could not pack tree" E*.log` matches nothing. What is left:

```
Execution failed for task ':shared:compileAndroidMain'.
> Cannot access output property 'destinationDirectory' … Accessing unreadable
  inputs or outputs is not supported.
   > Failed to create MD5 hash for file: …/AndroidSecrets.class (No such file or directory)
```

```
Execution failed for task ':androidApp:compileReleaseKotlin'.
> Kotlin compiler: UNRESOLVED_IMPORT
```

The first is the ledger's signature 2, in the same words on a different task.
The second is the "1,240 unresolved references to code that plainly exists" that
a previous task nearly filed as a compiler bug — it is one build deleting class
files while the other resolves against them.

### The answer

**The shared build directory is the cause; the build cache is only where one of
the three symptoms happens to surface.** The evidence, in the order it settles
the question:

- Twenty runs, one build at a time, caching on and caching off: **clean**, both
  arms. So neither caching nor `--rerun-tasks` is the problem.
- Ten runs, two builds at once in *separate* clones sharing the Gradle user home
  and its build cache: **clean**. So it is not the cache being shared, and it is
  not concurrency as such.
- Ten runs, two builds at once through *one* build directory, caching on:
  **5 failed**, in three signatures, one of which is a resource-merge failure
  with no cache involved.
- The same ten with caching **off**: **4 failed**, the cache-pack signature
  gone and the other two still there.

So the fix is **never share a build directory**, and disabling caching is not a
fix at all: it removes one of three symptoms and leaves the build just as
broken. Turning caching off to make the "could not pack tree" message go away
would have been the most natural conclusion from signature 1 alone, and would
have left two failure modes in place while everyone believed the flake was
fixed.

`gradle.properties` therefore keeps `org.gradle.caching=true` and
`org.gradle.parallel=true` as they are. The rule that follows from this lives in
the process ledger and is worth repeating here: **any repeated build — a
mutation sweep, a flake experiment, a `--rerun-tasks` loop — runs in a private
clone.** A second agent building in the same checkout is indistinguishable, from
inside, from a compiler bug.

### What is still open

The bare `TimeoutException` (signature 3) was **not** reproduced, in any arm, in
fifty runs. A previous task saw it inside a private clone with its own build
directory, so a shared directory does not explain it; the load average on this
128-core box was 116 at the time. It fired immediately after `Problems report is
taking too long to write…` and after `:shared:jvmTest` had already completed,
which points at Gradle's own reporting rather than at anything this project
compiles. The fifty runs here were at a load average of roughly 35. It stays
open, and "do not run this box at a load average of 116" is the only advice
available.

Signature 2 *was* reproduced, in arm E, on `compileAndroidMain` rather than on
`jvmTest` — same message, same cause: one build asked for the hash of a file the
other had just deleted.
