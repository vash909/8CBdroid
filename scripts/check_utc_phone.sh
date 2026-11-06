#!/usr/bin/env bash
# Uses installed, matching verified CI app/test APKs. Records only on explicit ntp-audio invocation.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source scripts/env.sh
scenario=${1:-ntp-audio}
[[ "$scenario" == ntp-audio || "$scenario" == gps ]] || { echo "Usage: $0 [ntp-audio|gps]" >&2; exit 2; }
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
timeout 110 adb shell am instrument --user 0 -w -r -e scenario "$scenario" \
    net.vash.ft8cbdroid.test/net.vash.ft8cbdroid.validation.WaveformPhoneChecks > ".tooling/utc-$scenario-output.txt"
cat ".tooling/utc-$scenario-output.txt"
python3 - "$scenario" <<'PY'
import json,subprocess,sys
from pathlib import Path
scenario=sys.argv[1]
text=Path(f'.tooling/utc-{scenario}-output.txt').read_text()
assert 'utc_result=PASS' in text and 'INSTRUMENTATION_CODE: -1' in text, 'R11 device checks did not pass'
report=subprocess.run(['adb','exec-out','run-as','net.vash.ft8cbdroid','cat',f'cache/r11-{scenario}.json'],check=True,capture_output=True,timeout=10).stdout
line=next(line for line in text.splitlines() if line.startswith('INSTRUMENTATION_RESULT: utc_report='))
assert json.loads(line.split('=',1)[1])==json.loads(report)
Path(f'.tooling/utc-{scenario}-report.json').write_bytes(report)
print(f'Verified R11 device report: .tooling/utc-{scenario}-report.json')
PY
