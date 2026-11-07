#!/usr/bin/env bash
# Software-only host catalog and Dummy lifecycle check. No radio ports are opened.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
output="${1:-.tooling/hamlib-host}"
jobs="${CMAKE_BUILD_PARALLEL_LEVEL:-2}"
python3 scripts/build_hamlib.py --abi host --output "$output/hamlib" --jobs "$jobs"
cmake -S radio/hamlib/native -B "$output/adapter" -DCMAKE_BUILD_TYPE=Release \
    -DHAMLIB_PREFIX="$(cd "$output/hamlib" && pwd)"
cmake --build "$output/adapter" --parallel "$jobs"
ctest --test-dir "$output/adapter" --output-on-failure --timeout 20
