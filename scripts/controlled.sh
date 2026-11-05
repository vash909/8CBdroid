#!/usr/bin/env bash
# Run one development job with limits covering all descendant processes.
set -euo pipefail
if [[ $# -lt 2 || ! $1 =~ ^[1-9][0-9]*$ ]]; then
    echo "Usage: bash scripts/controlled.sh TIMEOUT_SECONDS COMMAND [ARG...]" >&2
    exit 2
fi
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$project_root/.tooling"
exec 9>"$project_root/.tooling/controlled.lock"
if ! flock --nonblock 9; then
    echo "Another controlled job is running; wait for it to finish." >&2
    exit 1
fi
limit_seconds=$1
shift
job_unit="8cbdroid-job-$$.scope"
cleanup() {
    timeout 5s systemctl --user stop "$job_unit" >/dev/null 2>&1 || true
}
trap cleanup EXIT
export CMAKE_BUILD_PARALLEL_LEVEL=2
export OMP_NUM_THREADS=2
export OPENBLAS_NUM_THREADS=2
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=2"
echo "Limits: 2 CPUs, 4 GiB RAM, no swap, ${limit_seconds}s deadline" >&2
started=$SECONDS
status=0
systemd-run --user --scope --quiet --unit="$job_unit" \
    -p CPUQuota=200% -p MemoryHigh=3G -p MemoryMax=4G -p MemorySwapMax=0 \
    timeout --signal=TERM --kill-after=5s "${limit_seconds}s" "$@" || status=$?
echo "Job finished: exit $status, $((SECONDS - started))s elapsed" >&2
exit "$status"
