#!/usr/bin/env bash
# Use only after installing and verifying the selected CI probe APK. Does not install or clear data.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source scripts/env.sh
mkdir -p .tooling
cleanup() { timeout 5 adb shell am force-stop --user 0 net.vash.ft8cbdroid.engineprobe >/dev/null 2>&1 || true; }
trap cleanup EXIT
timeout 10 adb get-state
timeout 180 adb shell am instrument --user 0 -w -r -e benchmark true \
  net.vash.ft8cbdroid.engineprobe/.OfflineEngineProbe > .tooling/profile-output.txt
cat .tooling/profile-output.txt
python3 - <<'PY'
from pathlib import Path
text=Path('.tooling/profile-output.txt').read_text()
assert 'profile_result=PASS' in text and 'INSTRUMENTATION_CODE: -1' in text, 'Phone profile did not pass'
PY
timeout 10 adb exec-out run-as net.vash.ft8cbdroid.engineprobe cat files/r09-profile.json > .tooling/profile-report.json
python3 - <<'PY'
import hashlib,re
from pathlib import Path
expected=re.search(r'profile_report_sha256=([0-9a-f]{64})',Path('.tooling/profile-output.txt').read_text()).group(1)
assert hashlib.sha256(Path('.tooling/profile-report.json').read_bytes()).hexdigest()==expected
print('Profile report checksum verified: .tooling/profile-report.json')
PY
