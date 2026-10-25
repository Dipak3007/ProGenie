#!/usr/bin/env bash
# Creates or updates the ProGenie tables in your Supabase database, from your laptop, with Flyway in Docker.
# Uses the same migrations as local development (the ProGenie_DB_Scripts folder next to ProGenie).
#
#   bash infra/nocard/render/supabase-migrate.sh
#
# It asks for the Session pooler connection string (Supabase → Connect → Session pooler) unless SUPABASE_URL is set.
# Run it once at the start, and again whenever ProGenie_DB_Scripts has a new migration. Safe to run again.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
db_dir=${PROGENIE_DB_DIR:-$here/../../../../ProGenie_DB_Scripts}
[ -d "$db_dir/migrations" ] || { echo "Migrations not found at $db_dir/migrations (set PROGENIE_DB_DIR)" >&2; exit 1; }
db_dir=$(cd "$db_dir" && pwd)
# shellcheck source=supabase.sh
. "$here/supabase.sh"

# Check Docker first, before asking for the password
if ! out=$(docker info 2>&1 >/dev/null); then
  if grep -qi "permission denied" <<< "$out"; then
    cat >&2 <<'MSG'
Docker is installed, but your user isn't allowed to use it yet. Fix it once:
  sudo usermod -aG docker "$USER"
then log out and log back in (or restart), and run this script again.
(Quick alternative for this one run: sudo bash infra/nocard/render/supabase-migrate.sh)
MSG
  else
    echo "Docker isn't running. Start it (sudo systemctl start docker, or open Docker Desktop) and try again." >&2
  fi
  exit 1
fi

url=${SUPABASE_URL:-}
if [ -z "$url" ]; then
  read -rsp "Supabase Session pooler connection string (with your password; not shown): " url
  echo
fi
parse_supabase_url "$url"

echo "Applying migrations from $db_dir/migrations ..."
# The connection goes in as command-line options: passing it as FLYWAY_URL/USER/PASSWORD environment variables
# didn't reach the Flyway 12 image. (The options are visible to other users of this computer for the minute it runs.)
docker run --rm \
  -v "$db_dir/migrations:/flyway/sql:ro" \
  flyway/flyway:12 \
  "-url=$DB_JDBC_URL" "-user=$DB_USER" "-password=$DB_PASSWORD" \
  -locations=filesystem:/flyway/sql -connectRetries=3 \
  -baselineOnMigrate=true -baselineVersion=0 migrate   # a new Supabase schema may hold its own objects already
echo "Done. The tables are up to date."
