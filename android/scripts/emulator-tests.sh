#!/usr/bin/env bash
# Runs the instrumented tests on the CI emulator with a host-side driver for the emulator's
# modem. A test logs "TetherEmu: <id> <command>"; the driver runs it and then sets the
# debug.tether.ack property to <id>, which the test waits for:
#   CALL <n>    `adb emu gsm call <n>`: the modem rings the phone like a real incoming call
#   CANCEL <n>  `adb emu gsm cancel <n>`: the caller hangs up
#   RESET       hang up everything `gsm list` still shows until it is empty, then wait for the
#               system's broadcast queue to drain (phone-state broadcasts lag badly on a busy
#               emulator, and a test must not start while the last one's are still queued)
#
# Usage: emulator-tests.sh [test class or class#method]   (default: the ordered DeviceSuite)
#
# The emulator console (which `adb emu` talks to) sometimes isn't up when the runner starts the
# tests. The console auth token is emptied by the workflow (no auth handshake to fail), this
# script waits for the console to answer before starting, and modem commands are retried.
set -u
TESTS="${1:-dev.benfranklin.tether.DeviceSuite}"

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

# Numbers of the calls the modem still has ("inbound from 5551001 : incoming", "outbound to ...").
modem_calls() {
  adb emu gsm list 2>/dev/null | tr -d '\r' | grep -oE '(from|to)[[:space:]]+[+0-9]+' | awk '{print $2}'
}

# Hangs up every call the modem lists until it lists none (≤ 20 rounds).
clear_modem() {
  local i nums n
  for ((i = 1; i <= 20; i++)); do
    nums=$(modem_calls)
    if [[ -z "$nums" ]]; then
      [[ $i -gt 1 ]] && echo "driver: modem clear after $((i - 1)) round(s)"
      return 0
    fi
    echo "driver: modem still lists: $(echo $nums)"
    for n in $nums; do adb emu gsm cancel "$n" >/dev/null 2>&1 || true; done
    sleep 1
  done
  echo "::warning::driver: modem still lists calls after 20 rounds: $(echo $(modem_calls))"
  return 1
}

# Waits (bounded) until broadcasts sent so far have been delivered: the barrier (Android 14+)
# when the device has it, else full broadcast idle.
settle_broadcasts() {
  local limit=${1:-60} t0 out
  t0=$(date +%s)
  out=$(timeout "$limit" adb shell am wait-for-broadcast-barrier 2>&1)
  local code=$?
  if printf '%s' "$out" | grep -qi 'unknown\|error'; then
    out=$(timeout "$limit" adb shell am wait-for-broadcast-idle 2>&1)
    code=$?
  fi
  if [[ $code -eq 124 ]]; then
    echo "driver: broadcast queue still busy after ${limit} s (continuing)"
  else
    echo "driver: broadcasts settled in $(($(date +%s) - t0)) s"
  fi
}

ack() { adb shell setprop debug.tether.ack "$1" >/dev/null 2>&1 || echo "::warning::driver: could not ack $1"; }

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
clear_modem || true

# No ring-volume step: API 34 has no `media volume`, and `cmd media_session volume --stream 2
# --set 0` is accepted but leaves the ring stream at 5 (run 38031677427). The ringtone race on
# -noaudio (AsyncRingtonePlayer NPE in system_server) is avoided by the tests waiting 2.5 s after
# the ring before they act.

# A freshly booted emulator is still delivering boot broadcasts for minutes; let that finish.
settle_broadcasts 180

adb logcat -c || true
(
  adb logcat -v raw -s TetherEmu:I | while read -r line; do
    line="${line%$'\r'}"
    id="${line%% *}"
    cmd="${line#* }"
    case "$cmd" in
      "CALL "*)
        echo "driver: [$id] gsm call ${cmd#CALL }"
        emu 10 gsm call "${cmd#CALL }" || echo "::warning::driver: gsm call ${cmd#CALL } failed"
        ack "$id"
        ;;
      "CANCEL "*)
        echo "driver: [$id] gsm cancel ${cmd#CANCEL }"
        # Cancelling a call that already ended answers KO; a couple of tries is enough.
        emu 2 gsm cancel "${cmd#CANCEL }" 2>/dev/null || true
        ack "$id"
        ;;
      "RESET")
        echo "driver: [$id] reset"
        clear_modem || true
        settle_broadcasts 60
        ack "$id"
        ;;
    esac
  done
) &
driver=$!
./gradlew --stacktrace :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=$TESTS"
code=$?
kill "$driver" 2>/dev/null || true
pkill -f "adb logcat -v raw -s TetherEmu" 2>/dev/null || true
adb logcat -d -s TetherEmu:I TetherTest:I Tether:* AndroidRuntime:E > "app/build/emulator-logcat-${2:-main}.txt" 2>/dev/null || true
exit $code
