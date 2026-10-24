#!/usr/bin/env bash
# Starts the ProGenie SIT backend inside one Hugging Face Docker Space container:
#   PostgreSQL 17 + PostGIS (127.0.0.1:5432), Mailpit (127.0.0.1:8025), the Spring Boot API (127.0.0.1:8080),
#   and Caddy on $PORT (7860), the only port Hugging Face exposes. See docs/DEPLOYMENT_NOCARD.md.
#
# A free Space has no persistent disk, so the data is restored from the private backup dataset at start-up and
# saved back to it while running (backup.py). If any process stops, the container exits and Hugging Face
# restarts it.
#
# Settings come from the Space's Variables and Secrets (Space → Settings). Required:
#   PROGENIE_WEB_BASE_URL, DB_PASSWORD, EDGE_SECRET, MAILPIT_PASSWORD,
#   PROGENIE_OTP_PEPPER, PROGENIE_PAYMENT_WEBHOOK_SECRET, PROGENIE_JWT_PRIVATE_KEY, PROGENIE_JWT_PUBLIC_KEY
# For backups: BACKUP_DATASET and HF_TOKEN. Optional: BACKUP_INTERVAL (seconds), JAVA_OPTS, and any other
# PROGENIE_* setting from api/src/main/resources/application.yml.
set -Eeuo pipefail

APP_DIR=${APP_DIR:-/app}
PG_BIN=${PG_BIN:-/usr/lib/postgresql/17/bin}
PORT=${PORT:-7860}
DATA=${DATA_DIR:-$HOME/data}
RUN=$DATA/run
export PGDATA=$DATA/pg UPLOADS_DIR=$DATA/uploads
export PGHOST=127.0.0.1 PGPORT=5432 PGUSER=progenie PGDATABASE=progenie

log() { printf '[start] %s\n' "$*"; }
die() { log "ERROR: $*"; exit 1; }

# ---------------------------------------------------------------- settings
missing=()
for name in PROGENIE_WEB_BASE_URL DB_PASSWORD EDGE_SECRET MAILPIT_PASSWORD; do
  [ -n "${!name:-}" ] || missing+=("$name")
done
[ ${#missing[@]} -eq 0 ] || die "set these in the Space's Settings → Variables and secrets: ${missing[*]}"
[ ${#EDGE_SECRET} -ge 32 ] || die "EDGE_SECRET must be at least 32 characters (it must match the Cloudflare Worker's)"
if [ -z "${BACKUP_DIR:-}" ] && [ -z "${BACKUP_DATASET:-}" ]; then
  log "WARNING: BACKUP_DATASET is not set. Everything (bookings, users, uploads) is lost when the Space restarts or sleeps."
elif [ -n "${BACKUP_DATASET:-}" ] && [ -z "${HF_TOKEN:-}" ]; then
  die "BACKUP_DATASET is set but HF_TOKEN isn't: add a Hugging Face token with write access as the secret HF_TOKEN"
fi
export PGPASSWORD=$DB_PASSWORD

# Hugging Face exposes every Space secret to every process. Keep the tokens away from the API and Caddy:
# GH_TOKEN was only needed for the build, and HF_TOKEN only goes to backup.py.
BACKUP_TOKEN=${HF_TOKEN:-}
unset GH_TOKEN HF_TOKEN
backup() { HF_TOKEN=$BACKUP_TOKEN python3 "$APP_DIR/backup.py" "$@"; }

mkdir -p "$RUN" "$UPLOADS_DIR"
chmod 700 "$DATA"

# ---------------------------------------------------------------- PostgreSQL
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  log "creating the database cluster"
  pwfile=$(mktemp)
  printf '%s\n' "$DB_PASSWORD" > "$pwfile"
  # The API's role is the cluster's superuser, like POSTGRES_USER in the Docker Compose setups: Flyway creates
  # the citext / btree_gist / postgis extensions, and a restore recreates them. Only 127.0.0.1 can connect.
  "$PG_BIN/initdb" -D "$PGDATA" -U progenie --pwfile="$pwfile" --auth=scram-sha-256 -E UTF8 --locale=C.UTF-8 >/dev/null
  rm -f "$pwfile"
  cat >> "$PGDATA/postgresql.conf" <<CONF
listen_addresses = '127.0.0.1'
unix_socket_directories = '$RUN'
shared_buffers = 256MB
max_connections = 50
CONF
fi
"$PG_BIN/pg_ctl" -D "$PGDATA" -l "$RUN/postgres.log" -w -t 60 start >/dev/null || { tail -n 30 "$RUN/postgres.log"; die "PostgreSQL did not start"; }
log "PostgreSQL is up"

if [ "$(psql -X -At -d postgres -c "SELECT 1 FROM pg_database WHERE datname = 'progenie'")" != 1 ]; then
  psql -X -q -d postgres -c "CREATE DATABASE progenie"
  log "restoring the latest backup"
  restored=0
  backup restore "$RUN/restore" || restored=$?
  case $restored in
    0)
      pg_restore --no-owner --no-privileges --exit-on-error -d progenie "$RUN/restore/db.dump" \
        || die "the backup could not be restored; the Space stops here so the backup isn't overwritten"
      if [ -f "$RUN/restore/uploads.tar.gz" ]; then tar -xzf "$RUN/restore/uploads.tar.gz" -C "$UPLOADS_DIR"; fi
      rm -rf "$RUN/restore"
      log "backup restored"
      ;;
    3) log "no backup yet: starting with a fresh database (Flyway loads the Day-0 data)" ;;
    *) die "could not read the backup dataset; check BACKUP_DATASET and HF_TOKEN (the Space stops so the backup isn't overwritten)" ;;
  esac
else
  restored=0   # the database survived (container restarted without losing its disk)
fi

# ---------------------------------------------------------------- Mailpit (testers read one-time codes here)
mailpit --listen 127.0.0.1:8025 --smtp 127.0.0.1:1025 --webroot mailpit --max 5000 \
  --database "$DATA/mailpit.db" > "$RUN/mailpit.log" 2>&1 &
MAILPIT_PID=$!

# ---------------------------------------------------------------- the API
export PROGENIE_ENVIRONMENT=${PROGENIE_ENVIRONMENT:-sit}
export PROGENIE_COOKIE_SECURE=true
export PROGENIE_CORS_ALLOWED_ORIGINS=${PROGENIE_CORS_ALLOWED_ORIGINS:-$PROGENIE_WEB_BASE_URL}
export PROGENIE_MAILPIT_URL=http://127.0.0.1:8025/mailpit
export PROGENIE_DB_MIGRATIONS=filesystem:$APP_DIR/migrations
export PROGENIE_STORAGE_DIR=$UPLOADS_DIR
export PROGENIE_LOG_LEVEL=${PROGENIE_LOG_LEVEL:-INFO}
export DB_HOST=127.0.0.1 DB_PORT=5432 DB_NAME=progenie DB_USER=progenie
export SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8080
# shellcheck disable=SC2086  # JAVA_OPTS is a list of options
env -u EDGE_SECRET -u MAILPIT_PASSWORD java ${JAVA_OPTS:--Xms256m -Xmx2g} -jar "$APP_DIR/app.jar" &
API_PID=$!

# ---------------------------------------------------------------- Caddy
MAILPIT_HASH=$(caddy hash-password --plaintext "$MAILPIT_PASSWORD")
export MAILPIT_HASH PORT
export XDG_DATA_HOME=$DATA/caddy XDG_CONFIG_HOME=$DATA/caddy
caddy run --config "$APP_DIR/Caddyfile" --adapter caddyfile > "$RUN/caddy.log" 2>&1 &
CADDY_PID=$!

# ---------------------------------------------------------------- stop cleanly (Space stopped, rebuilt or asleep)
BACKUP_PID=""
READY=false
shutdown() {
  local code=$1
  set +e
  trap - TERM INT
  log "stopping"
  [ -n "$BACKUP_PID" ] && kill "$BACKUP_PID" 2>/dev/null
  kill -TERM "$API_PID" 2>/dev/null && wait "$API_PID" 2>/dev/null
  # Only after a successful start: if the API never came up, nobody changed anything since the restore,
  # and a crash loop mustn't keep rewriting the backup.
  if [ "$READY" = true ] && "$PG_BIN/pg_isready" -q; then
    log "saving a last backup"
    backup now || log "WARNING: the last backup failed; changes since the previous one are lost"
  fi
  kill "$CADDY_PID" "$MAILPIT_PID" 2>/dev/null
  "$PG_BIN/pg_ctl" -D "$PGDATA" -m fast stop >/dev/null 2>&1 || true
  exit "$code"
}
trap 'shutdown 0' TERM INT

# Wait for the API (up to 5 minutes), then start the backup loop
for _ in $(seq 1 300); do
  if curl -fs -o /dev/null http://127.0.0.1:8080/actuator/health/readiness; then READY=true; break; fi
  kill -0 "$API_PID" 2>/dev/null || { log "the API stopped during start-up (see the log above)"; shutdown 1; }
  sleep 1
done
[ "$READY" = true ] || { log "the API did not become ready within 5 minutes"; shutdown 1; }
log "API is up; site: $PROGENIE_WEB_BASE_URL"
if [ "$restored" = 3 ]; then
  backup now || log "WARNING: the first backup failed; it will be retried"
fi
HF_TOKEN=$BACKUP_TOKEN python3 "$APP_DIR/backup.py" loop &   # not via backup(): $! must be python's PID
BACKUP_PID=$!

# Run until something stops: then exit, and Hugging Face restarts the container
set +e
wait -n "$API_PID" "$CADDY_PID" "$MAILPIT_PID"
code=$?
for name in API_PID CADDY_PID MAILPIT_PID; do
  kill -0 "${!name}" 2>/dev/null || log "${name%_PID} stopped (exit code $code)"
done
kill -0 "$CADDY_PID" 2>/dev/null || tail -n 20 "$RUN/caddy.log" 2>/dev/null
kill -0 "$MAILPIT_PID" 2>/dev/null || tail -n 20 "$RUN/mailpit.log" 2>/dev/null
shutdown 1
