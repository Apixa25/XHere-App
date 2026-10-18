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

# aosp_atd sometimes returns "Status: timeout" from `am start -W` even when
# the APK is fine. Try again before failing the job.
started=0
for attempt in 1 2 3; do
  set +e
  START="$(adb shell am start -W -n "$ACTIVITY" 2>&1)"
  CODE=$?
  set -e
  printf '%s\n' "$START"
  if [[ "$CODE" -eq 0 ]] && printf '%s' "$START" | grep -q "Status: ok"; then
    started=1
    break
  fi
  echo "Activity did not start (attempt $attempt)"
  adb shell am force-stop "$PKG" || true
  sleep 5
done
if [[ "$started" -ne 1 ]]; then
  adb logcat -d -t 400 || true
  exit 1
fi

sleep 8

dump_failure() {
  echo "$1"
  adb shell pidof "$PKG" || true
  # aosp_atd is noisy. Keep the failure log to our runtime and the activity manager.
  adb logcat -d -t 120 -s AndroidRuntime:E ActivityManager:I || true
  exit 1
}

set +e
PID="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')"
set -e
if [[ -z "$PID" ]]; then
  dump_failure "Process exited after start"
fi

# aosp_atd logs FATAL EXCEPTION from unrelated system processes. Only ours counts.
if adb logcat -d | grep -F "Process: $PKG" >/tmp/xhere-fatal.txt; then
  echo "Fatal exception in $PKG"
  cat /tmp/xhere-fatal.txt
  dump_failure "See the fatal above"
fi

# The activity manager still has the task. R8 strips android.util.Log calls,
# so this does not depend on a log line.
if ! adb shell dumpsys activity activities | grep -F "$PKG/" >/dev/null; then
  dump_failure "Activity is not running"
fi

echo "Minified release is running (pid $PID)"
adb shell ps -A | grep -F "$PKG" || true
