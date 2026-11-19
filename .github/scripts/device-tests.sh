#!/usr/bin/env bash
# Runs on the CI emulator (cwd: android/). Installs debug + test APKs, runs instrumentation, pulls screenshots,
# then installs the minified release APK and checks it launches.
set -uo pipefail
OUT=device-out
mkdir -p "$OUT/screenshots"
APP=ca.saskevents.app

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb shell am instrument -w -r "$APP.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/instrumentation.txt"
STATUS=0
grep -q "FAILURES!!!" "$OUT/instrumentation.txt" && STATUS=1
grep -q "^OK (" "$OUT/instrumentation.txt" || STATUS=1

adb pull "/sdcard/Android/data/$APP/files/screenshots/." "$OUT/screenshots/" || true
adb logcat -d -t 2000 > "$OUT/logcat-tests.txt" || true

# Release (R8-minified) smoke test: fresh install, launch, wait for the first sync, screenshot.
adb uninstall "$APP.test" || true
adb uninstall "$APP" || true
adb install app/build/outputs/apk/release/app-release.apk
adb logcat -c
adb shell am start -W -n "$APP/.ui.MainActivity"
sleep 25
adb exec-out screencap -p > "$OUT/screenshots/00_release_first_launch.png"
if adb shell pidof "$APP" >/dev/null; then echo "release app running" | tee -a "$OUT/instrumentation.txt"; else echo "RELEASE APP NOT RUNNING" | tee -a "$OUT/instrumentation.txt"; STATUS=1; fi
adb logcat -d -t 1500 > "$OUT/logcat-release.txt" || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat-release.txt"; then echo "RELEASE CRASH" | tee -a "$OUT/instrumentation.txt"; STATUS=1; fi
exit $STATUS
