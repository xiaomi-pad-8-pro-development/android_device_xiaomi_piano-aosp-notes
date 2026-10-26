#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${ANDROID_BUILD_TOP:-}" && -f "$ANDROID_BUILD_TOP/build/envsetup.sh" ]]; then
    cd "$ANDROID_BUILD_TOP"
    m PianoAospNotes
    exit $?
fi

echo "PianoAospNotes uses Material 3 Expressive resources and classes from Soong." >&2
echo "From an Android build environment, run: m PianoAospNotes" >&2
exit 2
