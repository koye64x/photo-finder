#!/usr/bin/env bash
# Guards the hard rules from AGENTS.md that linters cannot see:
# no telemetry/cloud SDKs, no platform face APIs, no broad media permissions.
# Usage: scripts/check-forbidden.sh   (exit code 1 on violation)
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0
check() {
    local description="$1" pattern="$2"
    shift 2
    local hits
    hits=$(grep -rnE "$pattern" "$@" \
        --include='*.kt' --include='*.kts' --include='*.toml' --include='*.xml' --include='*.swift' \
        --exclude-dir=build --exclude-dir=.gradle 2>/dev/null || true)
    if [[ -n "$hits" ]]; then
        echo "FORBIDDEN: $description"
        echo "$hits"
        echo
        fail=1
    fi
}

SRC=(core feature composeApp androidApp desktopApp gradle)

check "telemetry / cloud SDKs (rule 1)" \
    'firebase|crashlytics|com\.google\.android\.gms|sentry|appcenter|amplitude|mixpanel|segment\.analytics' "${SRC[@]}"
check "ML Kit / Apple Vision instead of the shared ML runtime (rule 5)" \
    'com\.google\.mlkit|mlkit|import Vision|VNDetectFace|platform\.Vision' "${SRC[@]}"
check "broad media/storage permissions (rule 4)" \
    'permission\.(READ_MEDIA_IMAGES|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|READ_CONTACTS)' \
    "${SRC[@]}"
check "cloud backup enabled (rule 3)" 'allowBackup="true"' "${SRC[@]}"

if [[ $fail -eq 0 ]]; then
    echo "check-forbidden: OK"
fi
exit $fail
