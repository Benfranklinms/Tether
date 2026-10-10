#!/usr/bin/env bash
# Runs the instrumented tests on the CI emulator with a host-side call driver: when a test logs
# "TetherEmu: CALL <n>" / "TetherEmu: CANCEL <n>", the driver runs `adb emu gsm call|cancel <n>`
# so the emulator's modem rings (or hangs up) the phone like a real incoming call.
set -u
adb logcat -c || true
(
  adb logcat -v raw -s TetherEmu:I | while read -r line; do
    line="${line%$'\r'}"
    case "$line" in
      "CALL "*)   echo "driver: gsm call ${line#CALL }";   adb emu gsm call "${line#CALL }" ;;
      "CANCEL "*) echo "driver: gsm cancel ${line#CANCEL }"; adb emu gsm cancel "${line#CANCEL }" || true ;;
    esac
  done
) &
driver=$!
./gradlew --stacktrace :app:connectedDebugAndroidTest
code=$?
kill "$driver" 2>/dev/null || true
pkill -f "adb logcat -v raw -s TetherEmu" 2>/dev/null || true
adb logcat -d -s TetherEmu:I tether:* AndroidRuntime:E > app/build/emulator-logcat.txt 2>/dev/null || true
exit $code
