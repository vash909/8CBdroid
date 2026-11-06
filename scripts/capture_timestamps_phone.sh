#!/usr/bin/env bash
# Diagnostic metadata only. Requires the verified matching CI probe APK and an unlocked phone.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source scripts/env.sh
umask 077
mkdir -p .tooling
original_stay_awake="$(timeout 10 adb shell settings get global stay_on_while_plugged_in | tr -d '\r')"
cleanup() {
    timeout 5 adb shell am force-stop --user 0 net.vash.ft8cbdroid.engineprobe >/dev/null 2>&1 || true
    if [[ "$original_stay_awake" == null ]]; then
        timeout 5 adb shell settings delete global stay_on_while_plugged_in >/dev/null 2>&1 || true
    else
        timeout 5 adb shell settings put global stay_on_while_plugged_in "$original_stay_awake" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT
timeout 10 adb shell svc power stayon usb
timeout 10 adb shell pm grant --user 0 net.vash.ft8cbdroid.engineprobe android.permission.RECORD_AUDIO
timeout 25 adb shell am instrument --user 0 -w -r \
    net.vash.ft8cbdroid.engineprobe/.AudioTimestampProbe > .tooling/audio-timestamps-output.txt
cat .tooling/audio-timestamps-output.txt
python3 - <<'PY'
from pathlib import Path
text=Path('.tooling/audio-timestamps-output.txt').read_text()
assert 'timestamp_result=COLLECTED' in text and 'INSTRUMENTATION_CODE: -1' in text, 'Timestamp diagnostic did not complete'
PY
timeout 10 adb exec-out run-as net.vash.ft8cbdroid.engineprobe cat files/r11-timestamps.json > .tooling/audio-timestamps-report.json
python3 - <<'PY'
import json
from pathlib import Path
value=json.loads(Path('.tooling/audio-timestamps-report.json').read_text())
assert value['result']=='DIAGNOSTIC' and len(value['points']) >= 100
print('Collected',len(value['points']),'timestamp observations; this is not a UTC-slot qualification pass.')
PY
