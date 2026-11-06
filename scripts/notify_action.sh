#!/usr/bin/env bash
# Audible cue immediately before asking the owner to take an action.
set -uo pipefail
sound=/usr/share/sounds/freedesktop/stereo/bell.oga
if command -v pw-play >/dev/null && [[ -f "$sound" ]]; then
    timeout 5 pw-play "$sound" && exit 0
fi
printf '\a'
echo "Desktop bell unavailable; terminal bell emitted." >&2
