#!/usr/bin/env bash
set -euo pipefail
# Desktop compatibility check; pass a standard org.json runtime JAR.
# Does not test Android UI, SQLite, backups, or APK resource linking.
if [[ $# != 1 || ! -f "$1" ]]; then
    echo "Usage: $0 /absolute/path/to/org-json.jar" >&2
    exit 2
fi
json_jar="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
check_dir="$(mktemp -d)"
trap 'rm -rf "$check_dir"' EXIT
javac --release 17 -classpath "$json_jar" -d "$check_dir" \
    "$repo_root/src/com/alxp/pianonotes/InkDocument.java" \
    "$repo_root/tools/tests/InkDocumentCheck.java"
java -classpath "$json_jar:$check_dir" com.alxp.pianonotes.InkDocumentCheck
