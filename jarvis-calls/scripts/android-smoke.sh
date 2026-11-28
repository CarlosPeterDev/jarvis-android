#!/usr/bin/env bash
set -euo pipefail
mkdir -p jarvis-smoke
collect_logs() { adb logcat -d -s AndroidRuntime:E > jarvis-smoke/crashes.txt 2>&1 || true; }
trap collect_logs EXIT
apk="$(find jarvis-artifacts -maxdepth 1 -name '*.apk' -print -quit)"
test -s "$apk"
app_package=ai.jarvis.calls.debug
if [[ "${PRODUCTION:-false}" == true ]]; then app_package=ai.jarvis.calls; fi
adb logcat -c
adb install -r "$apk"
component="$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$app_package" | tr -d '\r' | tail -n 1)"
[[ "$component" == */* ]] || { echo 'No launcher activity found'; exit 1; }
adb shell am start -W -n "$component" | tee jarvis-smoke/launch.txt
grep -q 'Status: ok' jarvis-smoke/launch.txt
adb shell uiautomator dump /sdcard/jarvis-smoke.xml
adb pull /sdcard/jarvis-smoke.xml jarvis-smoke/ui.xml
adb shell pidof "$app_package" > jarvis-smoke/process.txt
grep -q 'Call Jarvis' jarvis-smoke/ui.xml
adb exec-out screencap -p > jarvis-smoke/home.png
printf 'Android 14 emulator: APK installed, process running, Jarvis call dashboard visible.\n' | tee jarvis-smoke/result.txt
