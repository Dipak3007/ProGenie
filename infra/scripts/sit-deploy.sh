#!/usr/bin/env bash
# Builds and (re)starts the SIT stack, then waits until the API reports healthy.
# Usage (on the server, from anywhere):  ~/progenie/ProGenieV2/infra/scripts/sit-deploy.sh
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env.sit ] || { echo "infra/.env.sit is missing: run scripts/sit-secrets.sh first" >&2; exit 1; }
db_dir=$(grep -E '^PROGENIE_DB_DIR=' .env.sit | cut -d= -f2- | tr -d "'\"")
[ -d "${db_dir:-../../ProGenieV2-Database}/migrations" ] || {
  echo "Database scripts not found at ${db_dir:-../../ProGenieV2-Database}/migrations (clone ProGenieV2-Database next to ProGenieV2)" >&2
  exit 1
}

dc() { docker compose -f compose.yaml -f compose.sit.yaml --env-file .env.sit --profile app "$@"; }

echo "Checking the configuration..."
dc config --quiet

# One image at a time: building both at once can run a 4 GB server out of memory.
echo "Building the API image..."; dc build api
echo "Building the web image..."; dc build web

echo "Starting..."
dc up -d --remove-orphans

echo -n "Waiting for the API to be healthy"
for _ in $(seq 1 60); do
  status=$(docker inspect --format '{{.State.Health.Status}}' progenie-api 2>/dev/null || echo starting)
  if [ "$status" = healthy ]; then
    echo " ok"
    docker image prune -f >/dev/null
    domain=$(grep -E '^SIT_DOMAIN=' .env.sit | cut -d= -f2- | tr -d "'\"")
    echo "SIT is up: https://$domain"
    exit 0
  fi
  echo -n "."
  sleep 5
done

echo " the API did not become healthy in 5 minutes. Last log lines:" >&2
docker logs --tail 60 progenie-api >&2
exit 1
