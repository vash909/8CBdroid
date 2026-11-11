#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source scripts/env.sh
python3 scripts/manual_messages_reference.py --check
python3 scripts/qso_progression_reference.py --check
./gradlew --no-daemon :core:test :protocol:wsjtcb:test :protocol:ntp:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :dsp:ft8:lintDebug :audio:lintDebug :radio:hamlib:lintDebug :engineprobe:lintDebug :engineprobe:assembleDebug "$@"
