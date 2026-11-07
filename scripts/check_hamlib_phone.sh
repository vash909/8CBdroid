#!/usr/bin/env bash
# Matching successful CI probe APK must already be installed. Software-only check.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source scripts/env.sh
umask 077
mkdir -p .tooling
timeout 45 adb shell am instrument --user 0 -w -r \
    net.vash.ft8cbdroid.engineprobe/net.vash.ft8cbdroid.engineprobe.HamlibProbe > .tooling/hamlib-phone-output.txt
python3 - <<'PY'
import json
import subprocess
from pathlib import Path
text = Path('.tooling/hamlib-phone-output.txt').read_text()
assert 'hamlib_result=PASS' in text and 'INSTRUMENTATION_CODE: -1' in text, text
line = next(line for line in text.splitlines() if line.startswith('INSTRUMENTATION_RESULT: hamlib_report='))
report = json.loads(line.split('=', 1)[1])
stored = json.loads(subprocess.check_output(['adb', 'exec-out', 'run-as',
    'net.vash.ft8cbdroid.engineprobe', 'cat', 'cache/hamlib-probe.json'], timeout=10))
assert report == stored and report['result'] == 'PASS' and report['hardware_tested'] is False
Path('.tooling/hamlib-phone-report.json').write_text(json.dumps(report, indent=2))
print('Verified software-only Hamlib catalog/Dummy probe:', report['catalog_entries'], 'models;', report['dummy_cycles'], 'cycles.')
PY
