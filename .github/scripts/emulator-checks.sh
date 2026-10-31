#!/usr/bin/env bash
# Runs inside the emulator job:
#   1. the instrumented tests (debug build, against the Firebase emulators);
#   2. the self-update path (a fake "newer" release served locally);
#   3. a smoke test of the signed release APK: launch, rotate, resume, no crash.
set -uo pipefail

APP=io.github.ocpabdurrahim.electropos
DEBUG_APP=$APP.debug
ACTIVITY=com.example.MainActivity
OUT=emulator-out
mkdir -p "$OUT/release"
status=0

# A slow system app (e.g. the launcher) can pop an "isn't responding" dialog over
# everything after a long run. Hide those; real crashes are still caught below
# from the crash log and by checking the app process.
adb shell settings put global hide_error_dialogs 1 || true

adb install -r apks/debug/app-debug.apk
adb install -r apks/androidTest/debug/app-debug-androidTest.apk

echo "::group::Instrumented tests"
adb shell am instrument -w "$DEBUG_APP.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/instrumentation.txt"
echo "::endgroup::"
if ! grep -q "^OK (" "$OUT/instrumentation.txt"; then
  echo "::error::Instrumented tests failed"
  adb logcat -d > "$OUT/logcat-tests.txt"
  status=1
fi

echo "::group::Self-update test"
# Serve a fake "latest release" (version 99.0.0) the way GitHub's API would,
# then let the installed debug app find, verify and install it by itself.
mkdir -p update-server
cp apks/update/ElectroPOS-99.0.0.apk update-server/
size=$(stat -c %s update-server/ElectroPOS-99.0.0.apk)
sha=$(sha256sum update-server/ElectroPOS-99.0.0.apk | cut -d' ' -f1)
cat > update-server/latest.json <<JSON
{"tag_name": "v99.0.0", "body": "Self-update test", "assets": [
  {"name": "ElectroPOS-99.0.0.apk", "size": $size, "digest": "sha256:$sha",
   "browser_download_url": "http://10.0.2.2:8000/ElectroPOS-99.0.0.apk"}]}
JSON
python3 -m http.server 8000 --bind 127.0.0.1 --directory update-server > "$OUT/update-server.log" 2>&1 &
server=$!
adb shell appops set "$DEBUG_APP" REQUEST_INSTALL_PACKAGES allow
adb shell am force-stop "$DEBUG_APP"
adb logcat -c
adb shell am start -W -n "$DEBUG_APP/$ACTIVITY" > /dev/null
sleep 6
adb shell input keyevent KEYCODE_HOME   # updates install once the app is in the background
updated=0
for _ in $(seq 1 60); do
  if adb shell dumpsys package "$DEBUG_APP" | grep -q "versionName=99.0.0"; then updated=1; break; fi
  sleep 2
done
kill "$server" 2>/dev/null
adb logcat -d -s ElectroPosUpdate > "$OUT/self-update.log"
cat "$OUT/self-update.log"
if [ "$updated" = 1 ]; then
  echo "The app downloaded, verified and installed its own update without any tap."
else
  echo "::error::The app did not update itself to 99.0.0"
  status=1
fi
echo "::endgroup::"

echo "::group::Release APK smoke test"
adb install -r apks/release/app-release.apk
adb logcat -c

alive() { [ -n "$(adb shell pidof "$APP" | tr -d '\r')" ]; }
shot() { adb exec-out screencap -p > "$OUT/release/$1.png"; }
check() {
  if ! alive; then
    echo "::error::The release app is not running after: $1"
    status=1
  fi
}

adb shell am start -W -n "$APP/$ACTIVITY"
sleep 8
shot 01_launch
check "launch"

# Rotate and come back, to shake out state-saving problems in the release build.
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 3
shot 02_landscape
adb shell settings put system user_rotation 0
sleep 3
check "rotation"
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell am start -W -n "$APP/$ACTIVITY" > /dev/null
sleep 4
shot 03_resumed
check "resume"

if adb logcat -d -b crash | grep -q "$APP"; then
  echo "::error::The release build crashed"
  adb logcat -d -b crash
  status=1
fi
echo "::endgroup::"

exit $status
