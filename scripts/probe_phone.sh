#!/usr/bin/env bash
# Install only the offline probe; preserve the main app and its saved station.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source scripts/env.sh
cleanup() {
    timeout 5s adb shell am force-stop net.vash.ft8cbdroid.engineprobe >/dev/null 2>&1 || true
}
trap cleanup EXIT
timeout 5s adb get-state
timeout 30s adb install -r engineprobe/build/outputs/apk/debug/engineprobe-debug.apk
# am instrument may exit zero on a failed assertion: inspect the explicit PASS.
result=$(timeout 60s adb shell am instrument -w -r net.vash.ft8cbdroid.engineprobe/.OfflineEngineProbe)
printf '%s\n' "$result"
if [[ "$result" != *"PASS: independent desktop WAVs"* || "$result" != *"INSTRUMENTATION_CODE: -1"* ]]; then
    echo "Offline phone probe failed." >&2
    exit 1
fi
