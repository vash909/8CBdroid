#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source scripts/env.sh
./gradlew --no-daemon :core:test :protocol:wsjtcb:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug "$@"
