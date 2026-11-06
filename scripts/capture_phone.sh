#!/usr/bin/env bash
# Requires verified matching CI probe APK and an unlocked phone. Does not install or clear data.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source scripts/env.sh
umask 077
mkdir -p .tooling
cleanup() { timeout 5 adb shell am force-stop --user 0 net.vash.ft8cbdroid.engineprobe >/dev/null 2>&1 || true; }
trap cleanup EXIT
timeout 10 adb get-state
timeout 10 adb shell pm grant --user 0 net.vash.ft8cbdroid.engineprobe android.permission.RECORD_AUDIO
timeout 60 adb shell am instrument --user 0 -w -r \
  net.vash.ft8cbdroid.engineprobe/.AudioCaptureProbe > .tooling/audio-capture-output.txt
cat .tooling/audio-capture-output.txt
python3 - <<'PY'
from pathlib import Path
text=Path('.tooling/audio-capture-output.txt').read_text()
assert 'audio_result=PASS' in text and 'INSTRUMENTATION_CODE: -1' in text, 'Phone audio capture did not pass'
PY
timeout 10 adb exec-out run-as net.vash.ft8cbdroid.engineprobe cat files/r10-capture.json > .tooling/audio-capture-report.json
python3 - <<'PY'
import json
from pathlib import Path
text=Path('.tooling/audio-capture-output.txt').read_text()
line=next(line for line in text.splitlines() if line.startswith('INSTRUMENTATION_RESULT: audio_report='))
assert json.loads(line.split('=',1)[1])==json.loads(Path('.tooling/audio-capture-report.json').read_text())
print('Phone audio report verified: .tooling/audio-capture-report.json')
PY
