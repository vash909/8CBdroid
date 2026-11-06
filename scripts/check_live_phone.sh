#!/usr/bin/env bash
# Uses installed matching CI APKs; plays pinned desktop audio through the PC speakers.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source scripts/env.sh
python3 scripts/live_receive_phone.py
