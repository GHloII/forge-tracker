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
image="forge-tracker:$revision"
image_present=false
if docker image inspect "$image" >/dev/null 2>&1; then image_present=true; fi
gzip -dc "$archive" | docker load
docker image inspect "$image" >/dev/null
compose() { docker compose --env-file .release.env -f compose.yml "$@"; }
health_status() { docker inspect forge-tracker --format '{{.State.Health.Status}}'; }
wait_for_health() {
  local attempt
  for ((attempt = 0; attempt < 90; attempt++)); do
    if [[ "$(health_status 2>/dev/null || true)" == healthy ]]; then return 0; fi
    sleep 2
  done
  return 1
}

previous=$(cat .release.env 2>/dev/null || true)
active_revision=${previous#FORGE_IMAGE=forge-tracker:}
last_healthy=$(cat deployed-revision 2>/dev/null || true)
predecessor=$(cat previous-revision 2>/dev/null || true)
# After a manual rollback, the last deployed revision can be the other healthy image.
if [[ "$last_healthy" =~ ^[0-9a-f]{40}$ && "$last_healthy" != "$active_revision" ]]; then
  predecessor=$last_healthy
fi

cleanup() {
  local running_revision=$1 candidate
  if [[ "$predecessor" =~ ^[0-9a-f]{40}$ && "$predecessor" != "$running_revision" ]] || [[ -z "$previous" ]]; then
    while IFS= read -r candidate; do
      if [[ "$candidate" =~ ^forge-tracker:[0-9a-f]{40}$ &&
            "$candidate" != "forge-tracker:$running_revision" && "$candidate" != "forge-tracker:$predecessor" ]]; then
        docker image rm "$candidate" || true
      fi
    done < <(docker image ls forge-tracker --format '{{.Repository}}:{{.Tag}}')
  elif [[ "$revision" != "$running_revision" && "$image_present" == false ]]; then
    # Until we record a predecessor, retain existing images that may be needed for rollback.
    docker image rm "$image" || true
  fi
  if [[ -d backups ]] && ! find backups -maxdepth 1 -type f -name '*.tar.gz' -printf '%T@ %p\0' |
    sort -znr | tail -zn +4 | cut -zd ' ' -f2- | xargs -0 -r rm --; then
    echo 'Could not remove old database backups' >&2
  fi
}

active_was_healthy=false
if [[ -n "$previous" ]]; then
  if [[ "$active_revision" == "$last_healthy" || "$(health_status 2>/dev/null || true)" == healthy ]]; then
    active_was_healthy=true
  fi
  compose stop app
  mkdir -p backups
  # Stop SQLite before taking the entire data volume, including WAL and images.
  backup="backups/$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
  if ! docker run --rm --user 0 --entrypoint sh -v forge-tracker-data:/data:ro "$image" -c 'tar -czf - -C /data .' > "$backup"; then
    rm -f "$backup"
    if compose start app && wait_for_health; then cleanup "$active_revision"; fi
    exit 1
  fi
fi
printf 'FORGE_IMAGE=%s\n' "$image" > .release.env
rollback() {
  if [[ -n "$previous" ]]; then
    printf '%s\n' "$previous" > .release.env
    if compose up -d --no-build && wait_for_health; then cleanup "$active_revision"; fi
  fi
}
if ! compose up -d --no-build || ! wait_for_health; then
  docker logs --tail 60 forge-tracker >&2 || true
  rollback
  exit 1
fi
if [[ "$active_was_healthy" == true && "$active_revision" =~ ^[0-9a-f]{40}$ && "$active_revision" != "$revision" ]]; then
  predecessor=$active_revision
fi
if [[ "$predecessor" =~ ^[0-9a-f]{40}$ && "$predecessor" != "$revision" ]]; then
  printf '%s\n' "$predecessor" > .previous-revision.tmp
  mv -f .previous-revision.tmp previous-revision
fi
printf '%s\n' "$revision" > deployed-revision
cleanup "$revision"
echo "Healthy deployment: $revision"
