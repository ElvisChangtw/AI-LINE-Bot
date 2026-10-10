#!/usr/bin/env bash
# Deploys one image tag; if it is not healthy within the timeout, switches back to the previous tag and exits 1.
set -euo pipefail

SHA="${1:?usage: deploy.sh <git sha>}"
[[ "$SHA" =~ ^[0-9a-f]{7,40}$ ]] || { echo "deploy: not a git sha: $SHA" >&2; exit 2; }

DIR=/opt/linebot
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

switch_to() {
  export APP_TAG="$1"
  compose up -d --remove-orphans
}

aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "${ECR_REPO%%/*}"

PREVIOUS="$(cat "$DIR/current-tag" 2>/dev/null || true)"
export APP_TAG="$SHA"
compose pull app

# compose up itself fails when the app never turns healthy (cloudflared waits on it), so check both together.
if switch_to "$SHA" && healthy; then
  echo "$SHA" > "$DIR/current-tag"
  docker image prune -f >/dev/null
  echo "deploy: $SHA is healthy"
  exit 0
fi

echo "deploy: $SHA did not become healthy within ${HEALTH_TIMEOUT_SECONDS}s" >&2
compose logs --tail 50 app >&2 || true
if [[ -n "$PREVIOUS" && "$PREVIOUS" != "$SHA" ]]; then
  if switch_to "$PREVIOUS" && healthy; then
    echo "deploy: rolled back to $PREVIOUS" >&2
  else
    echo "deploy: rollback to $PREVIOUS is not healthy either" >&2
  fi
fi
exit 1
