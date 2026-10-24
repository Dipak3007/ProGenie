#!/usr/bin/env bash
# Builds and (re)starts the SIT stack, then waits until the API reports healthy.
# Works on an x86 or ARM server, and in tunnel mode on your own computer (scripts/dc.sh picks the files).
# Usage:  ~/progenie/ProGenie/infra/scripts/sit-deploy.sh
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env.sit ] || { echo "infra/.env.sit is missing: run scripts/sit-secrets.sh first" >&2; exit 1; }
db_dir=$(grep -E '^PROGENIE_DB_DIR=' .env.sit | cut -d= -f2- | tr -d "'\"")
[ -d "${db_dir:-../../ProGenie_DB_Scripts}/migrations" ] || {
  echo "Database scripts not found at ${db_dir:-../../ProGenie_DB_Scripts}/migrations (clone ProGenie_DB_Scripts next to ProGenie)" >&2
  exit 1
}
tunnel=false
grep -qiE '^SIT_TUNNEL=.?true' .env.sit && tunnel=true
dc() { bash ./scripts/dc.sh "$@"; }

echo "Checking the configuration..."
dc config --quiet

# One image at a time: building everything at once can run a small server out of memory.
if dc config --services | grep -qx postgres && dc config | grep -q 'progenie/postgis'; then
  echo "Building the PostGIS image (ARM)..."; dc build postgres
fi
echo "Building the API image..."; dc build api
echo "Building the web image..."; dc build web

echo "Starting..."
dc up -d --remove-orphans
if [ "$tunnel" = true ]; then
  bash ./scripts/sit-tunnel-url.sh
fi

echo -n "Waiting for the API to be healthy"
for _ in $(seq 1 60); do
  status=$(docker inspect --format '{{.State.Health.Status}}' progenie-api 2>/dev/null || echo starting)
  if [ "$status" = healthy ]; then
    echo " ok"
    docker image prune -f >/dev/null
    domain=$(grep -E '^SIT_DOMAIN=' .env.sit | cut -d= -f2- | tr -d "'\"")
    echo "SIT is up: https://$domain   (Mailpit: https://$domain/mailpit)"
    exit 0
  fi
  echo -n "."
  sleep 5
done

echo " the API did not become healthy in 5 minutes. Last log lines:" >&2
docker logs --tail 60 progenie-api >&2
exit 1
