#!/usr/bin/env bash
# Starts (or refreshes) the ProGenie back end on your laptop and points the website at it.
#
#   bash infra/nocard/laptop/start.sh
#
# Run it after every reboot, or whenever the site says the server is offline. It is safe to run again:
#   1. starts PostgreSQL, the API, Mailpit, Caddy and the Cloudflare quick tunnel in Docker (builds the API the
#      first time, or when you changed the code);
#   2. reads the tunnel's current https://<random>.trycloudflare.com address;
#   3. if that address changed, redeploys the Cloudflare Worker with it, so the fixed site address keeps working.
# Needs: Docker, infra/nocard/.env.nocard (scripts/nocard-secrets.sh), and `npx wrangler@4 login` done once.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
azure=$here/../azure
dc() { docker compose -f "$azure/compose.yaml" -f "$here/compose.laptop.yaml" "$@"; }

site=${SITE_URL:-}
if [ ! -f "$azure/.env" ]; then
  [ -n "$site" ] || { echo "First run: give the site address, e.g. SITE_URL=https://progenie.dipak-progenie.workers.dev bash $0" >&2; exit 1; }
  bash "$azure/make-env.sh" laptop "$site" laptop@example.invalid
fi
site=$(grep -E '^SITE_URL=' "$azure/.env" | cut -d= -f2- | tr -d "'\"")

echo "Starting the back end (the first build takes several minutes)..."
dc up -d --build --remove-orphans

echo -n "Waiting for the API"
api=$(dc ps -q api)
for _ in $(seq 1 72); do
  [ "$(docker inspect --format '{{.State.Health.Status}}' "$api" 2>/dev/null)" = healthy ] && break
  echo -n "."
  sleep 5
done
if [ "$(docker inspect --format '{{.State.Health.Status}}' "$api" 2>/dev/null)" != healthy ]; then
  echo " the API did not become healthy. Last log lines:" >&2
  dc logs --tail 60 api >&2
  exit 1
fi
echo " ok"

url=""
for _ in $(seq 1 30); do
  url=$(dc logs tunnel 2>&1 | grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' | tail -1 || true)
  [ -n "$url" ] && break
  sleep 2
done
[ -n "$url" ] || { echo "No tunnel address yet. Check: docker compose logs tunnel (in infra/nocard/azure)" >&2; exit 1; }

last_file=$here/.last-backend-url
if [ "$(cat "$last_file" 2>/dev/null)" != "$url" ]; then
  echo "Tunnel address is $url: pointing the website at it..."
  SKIP_BUILD=1 BACKEND_URL=$url bash "$root/infra/nocard/scripts/deploy-web.sh"
  echo "$url" > "$last_file"
fi

cat <<MSG

ProGenie SIT is up:  $site
Mailpit (one-time codes):  $site/mailpit/   (tester / MAILPIT_PASSWORD from infra/nocard/.env.nocard)
Keep this laptop on and awake while testers use it. Stop with: bash infra/nocard/laptop/stop.sh
MSG
