#!/usr/bin/env bash
# Usage:
#   ./smoke.sh             # build → claim → backup → stop → install → start → smoke
#   ./smoke.sh --no-build  # require exactly one built ZIP + adjacent identity receipt
#   ./smoke.sh --up-only   # same deployment boundary, no smoke lake allocation
#   ./smoke.sh --down      # explicit Doris teardown, serialized with deployments
#   ./smoke.sh --preflight-only  # read-only Docker checks; no build/deploy/claim
#   ./smoke.sh --plugin-zip /path/to/plugin.zip  # explicit ZIP + .identity.json
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DO_BUILD=1; DOWN=0; UP_ONLY=0; PREFLIGHT_ONLY=0; EXPLICIT_ZIP=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-build) DO_BUILD=0 ;;
        --up-only) UP_ONLY=1 ;;
        --down) DOWN=1 ;;
        --preflight-only) PREFLIGHT_ONLY=1; DO_BUILD=0 ;;
        --plugin-zip)
            [[ $# -ge 2 ]] || { echo "--plugin-zip requires a path" >&2; exit 2; }
            EXPLICIT_ZIP="$2"; DO_BUILD=0; shift ;;
        -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 2 ;;
    esac
    shift
done
if [[ $DOWN -eq 1 && ( $PREFLIGHT_ONLY -eq 1 || -n "$EXPLICIT_ZIP" ) ]]; then
    echo "--down cannot be combined with artifact/preflight selection" >&2; exit 2
fi
if [[ -z "${DORIS_BE_PLATFORM:-}" && "$(uname -m)" == "x86_64" ]]; then
    export DORIS_BE_PLATFORM=linux/amd64
fi
deploy_args=(--compose-file "$HERE/docker-compose.yml" --evidence-root "$ROOT/build/smoke-deployments"
    --startup-timeout "${DORIS_FE_STARTUP_TIMEOUT:-180}")
if [[ $UP_ONLY -eq 1 ]]; then deploy_args+=(--mode up-only); else deploy_args+=(--mode full); fi
if [[ $DOWN -eq 1 ]]; then
    exec python3 "$HERE/plugin_deploy.py" "${deploy_args[@]}" --down
fi
if [[ $DO_BUILD -eq 1 ]]; then (cd "$ROOT" && ./gradlew assemble -q); fi
preflight_args=(--distributions "$ROOT/build/distributions" --compose-file "$HERE/docker-compose.yml"
    --staging-root "$ROOT/build/smoke-artifacts")
if [[ -n "$EXPLICIT_ZIP" ]]; then preflight_args+=(--plugin-zip "$EXPLICIT_ZIP"); fi
preflight_json=$(python3 "$HERE/plugin_preflight.py" "${preflight_args[@]}")
IFS=$'\t' read -r artifact_stage zip_path zip_manifest <<< \
    "$(python3 -c 'import json,sys; p=json.load(sys.stdin); print("\t".join([p["stage"],p["staged_zip"],p["staged_manifest"]]))' <<< "$preflight_json")"
cleanup_artifact() {
    rm -f -- "$zip_path" "$zip_manifest"
    rmdir -- "$artifact_stage"
}
trap cleanup_artifact EXIT
if [[ $PREFLIGHT_ONLY -eq 1 ]]; then printf '%s\n' "$preflight_json"; exit 0; fi
python3 "$HERE/plugin_deploy.py" "${deploy_args[@]}" -- bash "$HERE/smoke_run.sh" "$UP_ONLY" <<< "$preflight_json" &
deployer=$!
interrupt_deployer() {
    trap '' INT TERM
    kill -TERM "$deployer" 2>/dev/null || true
    wait "$deployer" || true
    exit 143
}
trap interrupt_deployer INT TERM
wait "$deployer"
