#!/usr/bin/env bash
set -Eeuo pipefail
cd /opt/forge-tracker
umask 077
exec 9>.deploy.lock
flock -w 900 9
# The deployment key is restricted to this entry point. No arbitrary SSH command.
read -r verb revision checksum extra <<< "${SSH_ORIGINAL_COMMAND:-}"
[[ "$verb" == deploy && "$revision" =~ ^[0-9a-f]{40}$ && "$checksum" =~ ^[0-9a-f]{64}$ && -z "${extra:-}" ]] || exit 64
archive=$(mktemp .image.XXXXXX.tar.gz)
trap 'rm -f "$archive"' EXIT
timeout 600 head -c 314572801 > "$archive"
[[ $(stat -c %s "$archive") -le 314572800 ]] || exit 65
echo "$checksum  $archive" | sha256sum --check --status
gzip -dc "$archive" | docker load
image="forge-tracker:$revision"
docker image inspect "$image" >/dev/null
compose() { docker compose --env-file .release.env -f compose.yml "$@"; }
previous=$(cat .release.env 2>/dev/null || true)
if [[ -n "$previous" ]]; then
  compose stop app
  mkdir -p backups
  # Stop SQLite before taking the entire data volume, including WAL and images.
  backup="backups/$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
  if ! docker run --rm --user 0 --entrypoint sh -v forge-tracker-data:/data:ro "$image" -c 'tar -czf - -C /data .' > "$backup"; then
    rm -f "$backup"
    compose start app
    exit 1
  fi
fi
printf 'FORGE_IMAGE=%s\n' "$image" > .release.env
rollback() {
  if [[ -n "$previous" ]]; then
    printf '%s\n' "$previous" > .release.env
    compose up -d --no-build
  fi
}
if ! compose up -d --no-build; then rollback; exit 1; fi
healthy=false
for attempt in $(seq 1 90); do
  state=$(docker inspect forge-tracker --format '{{.State.Health.Status}}' 2>/dev/null || true)
  if [[ "$state" == healthy ]]; then healthy=true; break; fi
  sleep 2
done
if [[ "$healthy" != true ]]; then
  docker logs --tail 60 forge-tracker >&2
  rollback
  exit 1
fi
printf '%s\n' "$revision" > deployed-revision
# Keep the running image and its immediate predecessor, never prune other services.
old_image=${previous#FORGE_IMAGE=}
while read -r candidate; do
  if [[ "$candidate" != "$image" && "$candidate" != "$old_image" ]]; then docker image rm "$candidate" || true; fi
done < <(docker image ls forge-tracker --format '{{.Repository}}:{{.Tag}}')
find backups -name '*.tar.gz' -type f -printf '%T@ %p\n' 2>/dev/null | sort -nr | tail -n +4 | cut -d' ' -f2- | xargs -r rm --
echo "Healthy deployment: $revision"
