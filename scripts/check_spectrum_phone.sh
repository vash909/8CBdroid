#!/usr/bin/env bash
# Requires matching successful CI APKs and an unlocked phone near the PC speakers.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source scripts/env.sh
python3 scripts/spectrum_phone.py
