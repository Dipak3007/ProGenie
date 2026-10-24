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

url=${SUPABASE_URL:-}
if [ -z "$url" ]; then
  read -rsp "Supabase Session pooler connection string (with your password; not shown): " url
  echo
fi
parse_supabase_url "$url"

echo "Applying migrations from $db_dir/migrations ..."
FLYWAY_URL=$DB_JDBC_URL FLYWAY_USER=$DB_USER FLYWAY_PASSWORD=$DB_PASSWORD \
sudo docker run --rm \
    -v "$db_dir/migrations:/flyway/sql:ro" \
    flyway/flyway:12 \
    "-url=$DB_JDBC_URL" "-user=$DB_USER" "-password=$DB_PASSWORD" \
    -locations=filesystem:/flyway/sql -connectRetries=3 \
    -baselineOnMigrate=true -baselineVersion=0 migrate
# sudo docker run --rm -e FLYWAY_URL -e FLYWAY_USER -e FLYWAY_PASSWORD \
#   -v "$db_dir/migrations:/flyway/sql:ro" \
#   flyway/flyway:12 -locations=filesystem:/flyway/sql -connectRetries=3 \
#   -baselineOnMigrate=true -baselineVersion=0 migrate   # a new Supabase schema may hold its own objects already
echo "Done. The tables are up to date."
