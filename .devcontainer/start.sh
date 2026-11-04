#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

cleanup() {
  kill "${backend_pid:-}" "${frontend_pid:-}" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT TERM

bash ./gradlew bootRun -x frontendInstall -x frontendBuild &
backend_pid=$!
(cd frontend && exec npm run dev -- --host 0.0.0.0 --port 5173 --strictPort) &
frontend_pid=$!
wait -n "$backend_pid" "$frontend_pid"
