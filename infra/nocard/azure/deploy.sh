#!/usr/bin/env bash
# Builds and (re)starts the ProGenie back end on the Azure VM, then waits until the API is healthy.
#   cd ~/progenie/ProGenie_DB_Scripts && git pull     (when there are new migrations)
#   cd ~/progenie/ProGenie && git pull && bash infra/nocard/azure/deploy.sh
set -euo pipefail
cd "$(dirname "$0")"

[ -f .env ] || { echo "infra/nocard/azure/.env is missing: run make-env.sh first" >&2; exit 1; }
db_dir=$(grep -E '^PROGENIE_DB_DIR=' .env | cut -d= -f2- | tr -d "'\"" || true)
db_dir=${db_dir:-../../../../ProGenie_DB_Scripts}
[ -d "$db_dir/migrations" ] || {
  echo "Database scripts not found at $db_dir/migrations: clone ProGenie_DB_Scripts next to ProGenie" >&2
  exit 1
}

echo "Checking the configuration..."
docker compose config --quiet
echo "Building the API image (a few minutes on a small VM)..."
docker compose build api
echo "Starting..."
docker compose up -d --remove-orphans

echo -n "Waiting for the API to be healthy"
api=$(docker compose ps -q api)
for _ in $(seq 1 72); do
  status=$(docker inspect --format '{{.State.Health.Status}}' "$api" 2>/dev/null || echo starting)
  if [ "$status" = healthy ]; then
    echo " ok"
    docker image prune -f >/dev/null
    host=$(grep -E '^API_HOST=' .env | cut -d= -f2- | tr -d "'\"")
    site=$(grep -E '^SITE_URL=' .env | cut -d= -f2- | tr -d "'\"")
    echo "Back end is up at https://$host (it only answers the Cloudflare Worker)."
    echo "Site: $site   Mailpit: $site/mailpit/"
    exit 0
  fi
  echo -n "."
  sleep 5
done
echo " the API did not become healthy in 6 minutes. Last log lines:" >&2
docker compose logs --tail 60 api >&2
exit 1
