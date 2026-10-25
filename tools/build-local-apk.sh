#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-}"
KEYSTORE="${NOTES_KEYSTORE:-}"
KEY_ALIAS="${NOTES_KEY_ALIAS:-}"
OUTPUT_APK="${OUTPUT_APK:-}"

if [[ -z "$SDK_ROOT" || -z "$KEYSTORE" || -z "$KEY_ALIAS" || -z "$OUTPUT_APK" ]]; then
    echo "Set ANDROID_SDK_ROOT, NOTES_KEYSTORE, NOTES_KEY_ALIAS, and OUTPUT_APK." >&2
    exit 2
fi
if [[ -z "${NOTES_KEYSTORE_PASS:-}" ]]; then
    echo "Set NOTES_KEYSTORE_PASS in the environment." >&2
    exit 2
fi

BUILD_TOOLS="$SDK_ROOT/build-tools/34.0.0"
ANDROID_JAR="$SDK_ROOT/platforms/android-34/android.jar"
for tool in aapt2 d8 zipalign apksigner; do
    if [[ ! -x "$BUILD_TOOLS/$tool" ]]; then
        echo "Missing Android SDK tool: $BUILD_TOOLS/$tool" >&2
        exit 2
    fi
done
if [[ ! -f "$ANDROID_JAR" || ! -f "$KEYSTORE" ]]; then
    echo "Android 34 platform jar or signing keystore not found." >&2
    exit 2
fi

OUT="$(mktemp -d "${TMPDIR:-/tmp}/piano-notes-build.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes" "$OUT/generated" "$OUT/dex" "$(dirname "$OUTPUT_APK")"

"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/res" -o "$OUT/resources.zip"
"$BUILD_TOOLS/aapt2" link \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/AndroidManifest.xml" \
    -R "$OUT/resources.zip" \
    --min-sdk-version 29 \
    --target-sdk-version 36 \
    --java "$OUT/generated" \
    -o "$OUT/base.apk"

find "$ROOT/src" -name '*.java' -print > "$OUT/sources.txt"
javac --release 17 -classpath "$ANDROID_JAR" -d "$OUT/classes" "@$OUT/sources.txt"
find "$OUT/classes" -name '*.class' -print > "$OUT/classes.txt"
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --output "$OUT/dex" "@$OUT/classes.txt"
(cd "$OUT/dex" && zip -q "$OUT/base.apk" classes.dex)

"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:NOTES_KEYSTORE_PASS \
    --out "$OUTPUT_APK" \
    "$OUT/aligned.apk"
"$BUILD_TOOLS/apksigner" verify "$OUTPUT_APK"
"$BUILD_TOOLS/zipalign" -c -p 4 "$OUTPUT_APK"
shasum -a 256 "$OUTPUT_APK"
