#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

cleanup() {
  trap - EXIT INT TERM
  for pid in "${backend_pid:-}" "${frontend_pid:-}"; do
    if [[ -n "$pid" ]]; then
      kill -- "-$pid" 2>/dev/null || true
    fi
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT TERM

setsid bash ./gradlew bootRun -x frontendInstall -x frontendBuild &
backend_pid=$!
setsid bash -c 'cd frontend && exec npm run dev -- --host 0.0.0.0 --port 5173 --strictPort' &
frontend_pid=$!
wait -n "$backend_pid" "$frontend_pid"
