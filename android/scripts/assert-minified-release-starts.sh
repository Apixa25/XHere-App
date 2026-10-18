#!/usr/bin/env bash
# Install the minified release APK and confirm the process stays up.
# Used by the android / release job. The APK is debug-signed when the
# upload-key secrets are absent, which is what makes install work in CI.
set -euo pipefail

APK="${1:?path to app-release.apk}"
PKG=world.xhere.app
ACTIVITY=world.xhere.app/.MainActivity

if [[ ! -f "$APK" ]]; then
  echo "Missing $APK"
  exit 1
fi

if ! unzip -l "$APK" | grep -E 'lib/.*/libmaplibre' >/dev/null; then
  echo "Minified APK is missing MapLibre native code"
  unzip -l "$APK" | head -80
  exit 1
fi

adb install -r -d "$APK"
adb logcat -c || true

set +e
START="$(adb shell am start -W -n "$ACTIVITY" 2>&1)"
CODE=$?
set -e
printf '%s\n' "$START"
if [[ "$CODE" -ne 0 ]] || ! printf '%s' "$START" | grep -q "Status: ok"; then
  echo "Activity did not start"
  adb logcat -d -t 400 || true
  exit 1
fi

sleep 8

if ! adb shell ps -A | grep -F "$PKG" >/dev/null; then
  echo "Process exited after start"
  adb logcat -d -t 400 || true
  exit 1
fi

# aosp_atd logs FATAL EXCEPTION from unrelated system processes. Only ours counts.
if adb logcat -d | grep -F "Process: $PKG" >/tmp/xhere-fatal.txt; then
  echo "Fatal exception in $PKG"
  cat /tmp/xhere-fatal.txt
  adb logcat -d -t 400 || true
  exit 1
fi

if ! adb logcat -d | grep -F "XHere: process started" >/dev/null; then
  echo "Application onCreate did not finish"
  adb logcat -d -t 400 || true
  exit 1
fi

echo "Minified release is running"
adb shell ps -A | grep -F "$PKG" || true
