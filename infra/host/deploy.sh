#!/usr/bin/env bash
# Deploys one image tag; if it is not healthy within the timeout, switches back to the previous tag and exits 1.
set -euo pipefail

SHA="${1:?usage: deploy.sh <git sha>}"
[[ "$SHA" =~ ^[0-9a-f]{7,40}$ ]] || { echo "deploy: not a git sha: $SHA" >&2; exit 2; }

DIR="${LINEBOT_DIR:-/opt/linebot}"
HEALTH_TIMEOUT_SECONDS=90
cd "$DIR"
# shellcheck source=/dev/null
source "$DIR/deploy.env"
: "${ECR_REPO:?ECR_REPO missing in deploy.env}" "${AWS_REGION:?AWS_REGION missing in deploy.env}"
export ECR_REPO

compose() { docker compose -p linebot -f "$DIR/docker-compose.yml" "$@"; }

healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS)) container status
  while ((SECONDS < deadline)); do
    container="$(compose ps -q app || true)"
    status="$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null || true)"
    [[ "$status" == "healthy" ]] && return 0
    sleep 3
  done
  return 1
}

# Start the app on its own and wait ourselves, so compose versions that wait on depends_on cannot hang the deploy.
switch_to() {
  export APP_TAG="$1"
  # compose reads this file for manual commands on the host (logs, stop, start).
  printf 'ECR_REPO=%s\nAPP_TAG=%s\n' "$ECR_REPO" "$APP_TAG" > "$DIR/.env"
  compose up -d --no-deps app && healthy
}

start_tunnel() {
  compose up -d --no-deps --remove-orphans cloudflared && sleep 10 && [[ -n "$(compose ps -q --status running cloudflared)" ]]
}

keep_only() {
  local image
  for image in $(docker image ls "$ECR_REPO" --format '{{.Tag}}'); do
    [[ " $* " == *" $image "* ]] || docker image rm "$ECR_REPO:$image" >/dev/null || true
  done
}

aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "${ECR_REPO%%/*}"

PREVIOUS="$(cat "$DIR/current-tag" 2>/dev/null || true)"
export APP_TAG="$SHA"
compose pull app

if switch_to "$SHA"; then
  echo "$SHA" > "$DIR/current-tag"
  keep_only "$SHA" "$PREVIOUS"
  # A broken tunnel is not the new app's fault, so report it without rolling back.
  if ! start_tunnel; then
    echo "deploy: $SHA is healthy but cloudflared is not running; check TUNNEL_TOKEN in tunnel.env" >&2
    compose logs --tail 30 cloudflared >&2 || true
    exit 1
  fi
  echo "deploy: $SHA is healthy"
  exit 0
fi

echo "deploy: $SHA did not become healthy within ${HEALTH_TIMEOUT_SECONDS}s" >&2
compose logs --tail 50 app >&2 || true
if [[ -n "$PREVIOUS" && "$PREVIOUS" != "$SHA" ]]; then
  if switch_to "$PREVIOUS"; then
    start_tunnel || echo "deploy: cloudflared is not running after the rollback" >&2
    echo "deploy: rolled back to $PREVIOUS" >&2
  else
    echo "deploy: rollback to $PREVIOUS is not healthy either" >&2
  fi
fi
exit 1
