#!/usr/bin/env bash
# Runs the instrumented tests on the CI emulator with a host-side call driver: when a test logs
# "TetherEmu: CALL <n>" / "TetherEmu: CANCEL <n>", the driver runs `adb emu gsm call|cancel <n>`
# so the emulator's modem rings (or hangs up) the phone like a real incoming call.
#
# The emulator console (which `adb emu` talks to) sometimes isn't up when the runner starts the
# tests, and AGP logs "Failed to start Emulator console" when it can't reach it. The console
# auth token is emptied by the workflow (no auth handshake to fail), this script waits for the
# console to answer before starting, and every modem command is retried until the console takes it.
set -u

# Runs `adb emu <args>` until the console takes it (exit 0, no KO/error) (up to $1 attempts, 1 s apart).
emu() {
  local tries=$1; shift
  local out i
  for ((i = 1; i <= tries; i++)); do
    if out=$(adb emu "$@" 2>&1); then
      out=$(printf '%s' "$out" | tr -d '\r')
      if ! printf '%s\n' "$out" | grep -qiE '^KO|error|unable|refused'; then return 0; fi
    fi
    echo "emu: '$*' attempt $i/$tries: ${out:-no answer}" >&2
    sleep 1
  done
  return 1
}

echo "Waiting for the emulator console…"
if ! emu 90 gsm list >/dev/null; then
  echo "::warning::emulator console did not answer after 90 s; restarting adb and trying once more"
  adb kill-server || true
  adb start-server || true
  adb wait-for-device
  if ! emu 60 gsm list >/dev/null; then
    echo "::error::emulator console unreachable: the modem can't be driven, call tests can't run"
    exit 1
  fi
fi
echo "Emulator console ready."
# Start from a quiet modem.
adb emu gsm cancel 5551234 >/dev/null 2>&1 || true

adb logcat -c || true
(
  adb logcat -v raw -s TetherEmu:I | while read -r line; do
    line="${line%$'\r'}"
    case "$line" in
      "CALL "*)
        echo "driver: gsm call ${line#CALL }"
        emu 10 gsm call "${line#CALL }" || echo "::warning::driver: gsm call ${line#CALL } failed"
        ;;
      "CANCEL "*)
        echo "driver: gsm cancel ${line#CANCEL }"
        # Cancelling a call that already ended answers KO; a couple of tries is enough.
        emu 2 gsm cancel "${line#CANCEL }" 2>/dev/null || true
        ;;
    esac
  done
) &
driver=$!
./gradlew --stacktrace :app:connectedDebugAndroidTest
code=$?
kill "$driver" 2>/dev/null || true
pkill -f "adb logcat -v raw -s TetherEmu" 2>/dev/null || true
adb logcat -d -s TetherEmu:I Tether:* AndroidRuntime:E > app/build/emulator-logcat.txt 2>/dev/null || true
exit $code
