#!/usr/bin/env bash
# Tunnel mode: read the tunnel's current https://<random>.trycloudflare.com address, save it as SIT_DOMAIN in
# .env.sit and restart the API so links in messages point to it. Run after the tunnel (re)starts;
# scripts/sit-deploy.sh calls it for you.
set -euo pipefail
cd "$(dirname "$0")/.."

url=""
for _ in $(seq 1 30); do
  url=$(docker logs progenie-tunnel 2>&1 | grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' | tail -1 || true)
  [ -n "$url" ] && break
  sleep 2
done
[ -n "$url" ] || { echo "No tunnel address yet. Check: docker logs progenie-tunnel" >&2; exit 1; }

host=${url#https://}
current=$(grep -E '^SIT_DOMAIN=' .env.sit | cut -d= -f2- | tr -d "'\"")
if [ "$current" != "$host" ]; then
  sed -i -E "s|^SIT_DOMAIN=.*|SIT_DOMAIN='$host'|" .env.sit
  bash ./scripts/dc.sh up -d api >/dev/null
  echo "Tunnel address changed: API restarted with the new address."
fi
echo "SIT is reachable at: $url   (Mailpit: $url/mailpit)"
