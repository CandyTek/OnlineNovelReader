#!/bin/sh
# Builds the debug app and the on-device tests, installs both on ONE device and runs the suite there.
#   sh tools/devtest.sh [full.class.Name[,another]]          DEVICE=<serial> to pick another device
# The tests write into the app's data: the device's copy of the app is cleared before and after.
# Never point this at a phone that is in use, and never run "gradlew connectedAndroidTest" (every attached device).
set -e
D=${DEVICE:-192.168.0.123:5555}
case "$D" in 192.168.0.208*) echo "that is the owner's phone"; exit 1;; esac
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain -q 2>&1 \
  | grep -v -e 'value 8' -e deprecat -e Xlint -e '^Note:' -e 'warnings$' || true
[ app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk -nt app/src/androidTest ] || echo "WARNING: the test package may be stale (did the build fail?)"
adb -s "$D" install -r app/build/outputs/apk/debug/NovelSniffer-debug.apk >/dev/null
adb -s "$D" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb -s "$D" shell pm clear com.rc.novelreader >/dev/null
adb -s "$D" logcat -c
adb -s "$D" shell am instrument -w ${1:+-e class "$1"} com.rc.novelreader.test/com.rc.novelreader.testing.SuiteRunner 2>&1 \
  | grep -v -e '^\s*at org.junit' -e '^\s*at java.lang.reflect' -e '^\s*at java.util.concurrent' -e '^\s*at java.lang.Thread'
echo "--- crashes in logcat:"
adb -s "$D" logcat -d -s AndroidRuntime:E | grep -e FATAL -e 'Caused by' -e 'at com.rc' | head -20
adb -s "$D" shell pm clear com.rc.novelreader >/dev/null
