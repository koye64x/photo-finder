#!/usr/bin/env bash
# Guards the hard rules from AGENTS.md that linters cannot see:
# no telemetry/cloud SDKs, no network access, no platform face APIs, no broad media permissions,
# no cloud backup, no logging of biometric data.
#
# Usage:
#   scripts/check-forbidden.sh            check sources (run before the build)
#   scripts/check-forbidden.sh --merged   also check merged Android manifests (run after the build);
#                                         fails if no merged manifest was found
# Exit code 1 on violation.
set -euo pipefail
cd "$(dirname "$0")/.."

merged=0
if [[ "${1:-}" == "--merged" ]]; then
    merged=1
elif [[ $# -gt 0 ]]; then
    echo "Unknown argument: $1" >&2
    exit 2
fi

fail=0
report() {
    echo "FORBIDDEN: $1"
    echo "$2"
    echo
    fail=1
}

check() {
    local description="$1" pattern="$2"
    shift 2
    local hits
    hits=$(grep -rnE "$pattern" "$@" \
        --include='*.kt' --include='*.kts' --include='*.toml' --include='*.xml' --include='*.swift' \
        --exclude-dir=build --exclude-dir=.gradle 2>/dev/null || true)
    if [[ -n "$hits" ]]; then
        report "$description" "$hits"
    fi
}

SRC=(core feature composeApp androidApp desktopApp gradle)
APP_MANIFEST=androidApp/src/main/AndroidManifest.xml

TELEMETRY='firebase|crashlytics|com\.google\.android\.gms|sentry|appcenter|amplitude|mixpanel|segment\.analytics'
MEDIA_PERMISSIONS='permission\.(READ_MEDIA_IMAGES|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|READ_CONTACTS)'
# Rule 1: no network at all until the local Ktor share server exists.
# Remove this pattern in the same PR that adds the share server, and only then.
NETWORK_PERMISSIONS='permission\.INTERNET"'

check "telemetry / cloud SDKs (rule 1)" "$TELEMETRY" "${SRC[@]}"
check "network permission before the local share server exists (rule 1)" "$NETWORK_PERMISSIONS" "${SRC[@]}"
check "ML Kit / Apple Vision instead of the shared ML runtime (rule 5)" \
    'com\.google\.mlkit|mlkit|import Vision|VNDetectFace|platform\.Vision' "${SRC[@]}"
check "broad media/storage permissions (rule 4)" "$MEDIA_PERMISSIONS" "${SRC[@]}"
check "cloud backup enabled (rule 3)" 'allowBackup="true"' "${SRC[@]}"
# Heuristic: a log/print call on the same line as biometric data or camera pixels.
check "logging of embeddings, face crops or camera frames (rule 3)" \
    '(println|print|Log\.[vdiwe]|Napier\.[a-z]+|logger\.[a-z]+|NSLog)\(.*(mbedding|RgbImage|CameraFrame|pixels|toFloatArray|alignedFace)' \
    "${SRC[@]}"

# A missing allowBackup defaults to true, so require it explicitly.
# On Android 12+ it does not block device-to-device transfer: dataExtractionRules must exclude everything.
if [[ -f "$APP_MANIFEST" ]]; then
    if ! grep -q 'android:allowBackup="false"' "$APP_MANIFEST"; then
        report "android:allowBackup=\"false\" is missing (rule 3)" "$APP_MANIFEST"
    fi
    if ! grep -q 'android:dataExtractionRules="@xml/data_extraction_rules"' "$APP_MANIFEST"; then
        report "android:dataExtractionRules is missing (rule 3)" "$APP_MANIFEST"
    fi
fi

# Merged manifests include permissions and components contributed by dependencies.
if [[ $merged -eq 1 ]]; then
    manifests=$(find androidApp/build/intermediates -path '*merged_manifest*' -name AndroidManifest.xml \
        2>/dev/null || true)
    if [[ -z "$manifests" ]]; then
        report "no merged Android manifest found; run the Android build first" "androidApp/build/intermediates"
    else
        while IFS= read -r manifest; do
            for rule in "network permission (rule 1)|$NETWORK_PERMISSIONS" \
                "telemetry component (rule 1)|$TELEMETRY" \
                "broad media/storage permissions (rule 4)|$MEDIA_PERMISSIONS"; do
                hits=$(grep -nE "${rule#*|}" "$manifest" || true)
                if [[ -n "$hits" ]]; then
                    report "${rule%%|*} in merged manifest $manifest" "$hits"
                fi
            done
            if ! grep -q 'android:allowBackup="false"' "$manifest"; then
                report "android:allowBackup=\"false\" lost in merged manifest (rule 3)" "$manifest"
            fi
        done <<<"$manifests"
    fi
fi

if [[ $fail -eq 0 ]]; then
    echo "check-forbidden: OK"
fi
exit $fail
