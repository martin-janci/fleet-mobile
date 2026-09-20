#!/usr/bin/env bash
#
# Bring a machine from "has a JDK" to "can build and test this app".
#
# Everything here was done by hand first, and every step that looks like
# pointless detail is a thing that actually went wrong:
#
#   - `platforms;android-37` DOES NOT EXIST. The package is `android-37.0`,
#     with the minor version, and sdkmanager's error names the package rather
#     than the mistake.
#   - `unzip` is not installed everywhere. The command-line tools ship as a zip
#     and Python is a safer bet than unzip on a headless box.
#   - The zip extracts to `cmdline-tools/` and sdkmanager refuses to run unless
#     it sits at `cmdline-tools/latest/`.
#   - Licences must be accepted before anything installs, and the prompt is
#     interactive unless fed.
#
# Idempotent: re-running is a no-op except for anything genuinely missing.
#
# Usage:  scripts/bootstrap.sh [--sdk-dir DIR]
set -euo pipefail

SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
while [ $# -gt 0 ]; do
  case "$1" in
    --sdk-dir) SDK_DIR="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"

# The two package names this build needs. `compileSdk` is 37 because Compose
# Multiplatform 1.12's androidx artifacts and okhttp-android 5.5 both fail
# their AAR-metadata check below it — see README.md → Building.
PLATFORM="platforms;android-37.0"
BUILD_TOOLS="build-tools;37.0.0"

say() { printf '\n==> %s\n' "$*"; }

# ---------------------------------------------------------------------------

say "JDK"
if ! command -v java >/dev/null 2>&1; then
  echo "No 'java' on PATH. Install a JDK 21 and re-run." >&2
  echo "  Debian/Ubuntu: apt install temurin-21-jdk   macOS: brew install temurin@21" >&2
  exit 1
fi
java -version 2>&1 | head -1
case "$(java -version 2>&1 | head -1)" in
  *\"21*|*\"2[2-9]*) ;;
  *) echo "warning: this build wants JDK 21 (jvmToolchain(21)); Gradle may download one." >&2 ;;
esac

say "Android command-line tools"
SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
if [ -x "$SDKMANAGER" ]; then
  echo "already at $SDKMANAGER"
else
  mkdir -p "$SDK_DIR/cmdline-tools"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  echo "downloading…"
  curl -fsSL -o "$tmp/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"
  # Python rather than unzip: a headless box often has the former and not the
  # latter, and this script is mostly run on headless boxes.
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$tmp/cmdline-tools.zip" -d "$SDK_DIR/cmdline-tools"
  else
    python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" \
      "$tmp/cmdline-tools.zip" "$SDK_DIR/cmdline-tools"
  fi
  # The archive unpacks to `cmdline-tools/`; sdkmanager requires `latest/`.
  if [ -d "$SDK_DIR/cmdline-tools/cmdline-tools" ]; then
    rm -rf "$SDK_DIR/cmdline-tools/latest"
    mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  fi
  chmod +x "$SDK_DIR/cmdline-tools/latest/bin/"* || true
  echo "installed at $SDKMANAGER"
fi

export ANDROID_HOME="$SDK_DIR"

say "Licences"
yes 2>/dev/null | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
echo "accepted (or already were)"

say "SDK packages"
installed="$("$SDKMANAGER" --list_installed 2>/dev/null || true)"
want=()
# Matched against the variables, never against a second copy of the version.
# Writing the literal here as well is how a script comes to install one package
# and check for another — which is precisely what a mutation of $PLATFORM
# survived until this line stopped repeating it.
for pkg in "$PLATFORM" "$BUILD_TOOLS"; do
  case "$installed" in
    *"${pkg#*;}"*) echo "$pkg already installed" ;;
    *) want+=("$pkg") ;;
  esac
done
if [ ${#want[@]} -gt 0 ]; then
  echo "installing: ${want[*]}"
  "$SDKMANAGER" --install "${want[@]}" >/dev/null
fi
"$SDKMANAGER" --list_installed 2>/dev/null | sed -n '/Installed packages/,$p' | head -8

say "local.properties"
props="$REPO_ROOT/local.properties"
if grep -qs "^sdk.dir=" "$props" 2>/dev/null; then
  echo "already points at: $(sed -n 's/^sdk.dir=//p' "$props")"
else
  printf 'sdk.dir=%s\n' "$SDK_DIR" >> "$props"
  echo "wrote sdk.dir=$SDK_DIR"
fi

say "Verifying"
cd "$REPO_ROOT"
./gradlew :shared:jvmTest --console=plain -q
echo
echo "Ready. What you can and cannot run on this machine:"
echo
"$REPO_ROOT/scripts/doctor.sh" || true
