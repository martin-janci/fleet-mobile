#!/usr/bin/env bash
#
# What this machine can and cannot run, and what each gap costs.
#
# The point is not to tell somebody off for missing a tool. Most machines are
# missing something here on purpose — there is no Mac on the box this app was
# written on, and no `/dev/kvm` either — and the useful thing is to say which
# checks therefore only ever run in CI, so that "it passed locally" is read
# with the right amount of confidence.
#
# Exits 0 unless something needed for the *ordinary* loop is missing.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' "$REPO_ROOT/local.properties" 2>/dev/null)}"
SDK_DIR="${SDK_DIR:-$HOME/Android/Sdk}"
fatal=0

ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
no()   { printf '  \033[31m✗\033[0m %s\n' "$*"; }
note() { printf '  \033[33m•\033[0m %s\n' "$*"; }

echo "Ordinary loop — these must work:"

if command -v java >/dev/null 2>&1; then
  ok "JDK: $(java -version 2>&1 | head -1)"
else
  no "no JDK on PATH — nothing builds. scripts/bootstrap.sh explains."; fatal=1
fi

if [ -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
  ok "Android SDK: $SDK_DIR"
else
  no "no Android SDK at $SDK_DIR — run scripts/bootstrap.sh"; fatal=1
fi

# One place each, so the check and the sentence describing it cannot disagree.
PLATFORM_VERSION="37.0"
BUILD_TOOLS_MAJOR="37"

if [ -d "$SDK_DIR/platforms/android-$PLATFORM_VERSION" ]; then
  ok "platform android-$PLATFORM_VERSION (note the minor version; 'android-${PLATFORM_VERSION%%.*}' does not exist)"
else
  no "platform android-$PLATFORM_VERSION missing — run scripts/bootstrap.sh"; fatal=1
fi

if ls -d "$SDK_DIR"/build-tools/"$BUILD_TOOLS_MAJOR".* >/dev/null 2>&1; then
  ok "build-tools $BUILD_TOOLS_MAJOR.x"
else
  no "build-tools $BUILD_TOOLS_MAJOR.x missing — run scripts/bootstrap.sh"; fatal=1
fi

if grep -qs "^sdk.dir=" "$REPO_ROOT/local.properties" 2>/dev/null; then
  ok "local.properties points at the SDK"
else
  note "local.properties has no sdk.dir — fine if ANDROID_HOME is exported"
fi

echo
echo "Runs here: ./gradlew :shared:jvmTest, :shared:compileTestKotlinIosSimulatorArm64,"
echo "           :shared:compileAndroidDeviceTest, :androidApp:assembleDebug"
echo

echo "Only in CI on this machine — not a fault, just a limit:"

if [ -e /dev/kvm ] && [ -r /dev/kvm ]; then
  ok "/dev/kvm — an Android emulator can run here"
  echo "      ./gradlew :shared:connectedAndroidDeviceTest"
else
  note "no usable /dev/kvm → no Android emulator here."
  echo "      AndroidSecrets and QrDecodeTest run only in the 'AndroidSecrets on an"
  echo "      emulator' CI job. A green local run says nothing about them."
fi

if [ "$(uname -s)" = "Darwin" ] && command -v xcrun >/dev/null 2>&1; then
  ok "macOS with Xcode — the iOS suites can run here"
  echo "      ./gradlew :shared:iosSimulatorArm64Test"
  echo "      (cd iosApp && xcodebuild test -scheme iosApp -destination 'platform=iOS Simulator,name=…')"
else
  note "not macOS → no Kotlin/Native tests, no XCTest, no Xcode build here."
  echo "      The shared suite on Kotlin/Native and the Keychain round trip run"
  echo "      only in the 'iOS simulator build and test' CI job."
fi

echo
echo "Getting CI to run without opening a pull request:"
echo "  gh workflow run CI --ref \$(git branch --show-current)"
echo "  gh run download <run-id> -n ios-xctest-log      # the iOS test output"
echo "  gh run download <run-id> -n instrumentation-reports"
echo

if [ "$fatal" -ne 0 ]; then
  echo "Something the ordinary loop needs is missing — see ✗ above." >&2
  exit 1
fi
echo "Ordinary loop is ready."
