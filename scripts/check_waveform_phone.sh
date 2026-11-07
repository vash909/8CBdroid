#!/usr/bin/env bash
# Requires matching successful CI app and device-check APKs already installed.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source scripts/env.sh
umask 077
mkdir -p .tooling
original_stay_awake="$(timeout 10 adb shell settings get global stay_on_while_plugged_in | tr -d '\r')"
cleanup() {
    timeout 5 adb shell am force-stop --user 0 net.vash.ft8cbdroid >/dev/null 2>&1 || true
    if [[ "$original_stay_awake" == null ]]; then
        timeout 5 adb shell settings delete global stay_on_while_plugged_in >/dev/null 2>&1 || true
    else
        timeout 5 adb shell settings put global stay_on_while_plugged_in "$original_stay_awake" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT
# USB-C can report AC charging; the EXIT trap restores the original setting.
timeout 10 adb shell svc power stayon true
timeout 90 adb shell am instrument --user 0 -w -r \
    net.vash.ft8cbdroid.test/net.vash.ft8cbdroid.validation.WaveformPhoneChecks > .tooling/waveform-phone-output.txt
cat .tooling/waveform-phone-output.txt
python3 - <<'PY'
from pathlib import Path
text=Path('.tooling/waveform-phone-output.txt').read_text()
assert 'waveform_result=PASS' in text and 'INSTRUMENTATION_CODE: -1' in text, 'R07 device checks did not pass'
PY
timeout 10 adb exec-out run-as net.vash.ft8cbdroid cat cache/r07-waveform-checks.json > .tooling/waveform-phone-report.json
python3 - <<'PY'
import json
from pathlib import Path
text=Path('.tooling/waveform-phone-output.txt').read_text()
line=next(line for line in text.splitlines() if line.startswith('INSTRUMENTATION_RESULT: waveform_checks='))
assert json.loads(line.split('=',1)[1])==json.loads(Path('.tooling/waveform-phone-report.json').read_text())
print('R07 device report verified: .tooling/waveform-phone-report.json')
PY
