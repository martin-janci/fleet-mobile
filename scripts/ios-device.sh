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
  team=$(team_from_keychain || true)
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
