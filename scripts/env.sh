#!/usr/bin/env bash
# Source this file from any directory: source /path/to/8CBdroid/scripts/env.sh
EIGHTCB_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -d "$EIGHTCB_ROOT/.tooling/jdk-17" ]]; then
    export JAVA_HOME="$EIGHTCB_ROOT/.tooling/jdk-17"
fi
if [[ -d "$EIGHTCB_ROOT/.tooling/android-sdk" ]]; then
    export ANDROID_HOME="$EIGHTCB_ROOT/.tooling/android-sdk"
fi
export GRADLE_USER_HOME="$EIGHTCB_ROOT/.tooling/gradle-home"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}${ANDROID_HOME:+$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:}$PATH"
unset EIGHTCB_ROOT
