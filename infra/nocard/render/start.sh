#!/usr/bin/env bash
# Starts the ProGenie back end inside the Render container: Mailpit, the API and Caddy (on Render's $PORT).
# If any of them stops, the container exits and Render restarts it. See docs/DEPLOYMENT_NOCARD.md.
#
# Settings come from the service's Environment tab on Render (paste the file made by render-env.sh).
set -Eeuo pipefail
APP_DIR=${APP_DIR:-/app}
DATA=${DATA_DIR:-/data}
export PORT=${PORT:-10000}

log() { printf '[start] %s\n' "$*"; }
missing=()
for name in SPRING_DATASOURCE_URL DB_USER DB_PASSWORD PROGENIE_WEB_BASE_URL EDGE_SECRET MAILPIT_PASSWORD; do
  [ -n "${!name:-}" ] || missing+=("$name")
done
if [ ${#missing[@]} -gt 0 ]; then
  log "ERROR: add these on Render (service → Environment): ${missing[*]}"
  exit 1
fi
[ ${#EDGE_SECRET} -ge 32 ] || { log "ERROR: EDGE_SECRET must be at least 32 characters"; exit 1; }
mkdir -p "$DATA/uploads" "$DATA/mailpit"

# ---- Mailpit (one-time codes for testers)
mailpit --listen 127.0.0.1:8025 --smtp 127.0.0.1:1025 --webroot mailpit --max 1000 \
  --database "$DATA/mailpit/mailpit.db" > "$DATA/mailpit/mailpit.log" 2>&1 &
MAILPIT_PID=$!

# ---- the API
export PROGENIE_ENVIRONMENT=${PROGENIE_ENVIRONMENT:-sit}
export PROGENIE_COOKIE_SECURE=true
export PROGENIE_CORS_ALLOWED_ORIGINS=${PROGENIE_CORS_ALLOWED_ORIGINS:-$PROGENIE_WEB_BASE_URL}
export PROGENIE_MAILPIT_URL=http://127.0.0.1:8025/mailpit
export PROGENIE_STORAGE_DIR=$DATA/uploads
export PROGENIE_LOG_LEVEL=${PROGENIE_LOG_LEVEL:-INFO}
export SPRING_FLYWAY_ENABLED=false                    # tables come from supabase-migrate.sh
export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE:-5}
export SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8080
# 512 MB in total: a small heap, the light C1 compiler (also starts faster on a tiny CPU share), serial GC
# shellcheck disable=SC2086  # JAVA_OPTS is a list of options
env -u EDGE_SECRET -u MAILPIT_PASSWORD java ${JAVA_OPTS:--Xmx256m -Xss512k -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC} \
  -jar "$APP_DIR/app.jar" &
API_PID=$!

# ---- Caddy
MAILPIT_HASH=$(caddy hash-password --plaintext "$MAILPIT_PASSWORD")
export MAILPIT_HASH
caddy run --config "$APP_DIR/Caddyfile" --adapter caddyfile &
CADDY_PID=$!

stop() { trap - TERM INT; log "stopping"; kill -TERM "$API_PID" "$CADDY_PID" "$MAILPIT_PID" 2>/dev/null; wait; exit "$1"; }
trap 'stop 0' TERM INT
log "starting the API (it takes a few minutes on a free instance); site: $PROGENIE_WEB_BASE_URL"

set +e
wait -n "$API_PID" "$CADDY_PID" "$MAILPIT_PID"
code=$?
log "a process stopped (exit code $code)"
kill -0 "$MAILPIT_PID" 2>/dev/null || tail -n 20 "$DATA/mailpit/mailpit.log"
stop 1
