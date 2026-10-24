#!/usr/bin/env bash
# Writes infra/nocard/render/.env.render (git-ignored): every setting the Render service needs, ready to paste
# into Render → your service → Environment → "Add from .env". See docs/DEPLOYMENT_NOCARD.md.
#
#   bash infra/nocard/render/render-env.sh https://progenie.<your-subdomain>.workers.dev
#
# Reads the secrets from infra/nocard/.env.nocard (scripts/nocard-secrets.sh) and asks for the Supabase
# Session pooler connection string unless SUPABASE_URL is set.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
site=${1:?usage: bash render-env.sh https://progenie.<your-subdomain>.workers.dev}
site=${site%/}
[[ $site == https://* ]] || { echo "The site address must start with https://" >&2; exit 1; }
secrets=$here/../.env.nocard
[ -f "$secrets" ] || { echo "infra/nocard/.env.nocard is missing: run bash infra/nocard/scripts/nocard-secrets.sh first" >&2; exit 1; }
# shellcheck source=supabase.sh
. "$here/supabase.sh"

value() { grep -E "^$1=" "$secrets" | head -n1 | cut -d= -f2-; }
for name in PROGENIE_OTP_PEPPER PROGENIE_PAYMENT_WEBHOOK_SECRET PROGENIE_JWT_PRIVATE_KEY PROGENIE_JWT_PUBLIC_KEY \
            MAILPIT_PASSWORD EDGE_SECRET; do
  [ -n "$(value "$name")" ] || { echo "$name is missing from infra/nocard/.env.nocard" >&2; exit 1; }
done

url=${SUPABASE_URL:-}
if [ -z "$url" ]; then
  read -rsp "Supabase Session pooler connection string (with your password; not shown): " url
  echo
fi
parse_supabase_url "$url"

out=$here/.env.render
umask 077
cat > "$out" <<VALUES
SPRING_DATASOURCE_URL=$DB_JDBC_URL
DB_USER=$DB_USER
DB_PASSWORD=$DB_PASSWORD
PROGENIE_WEB_BASE_URL=$site
PROGENIE_OTP_PEPPER=$(value PROGENIE_OTP_PEPPER)
PROGENIE_PAYMENT_WEBHOOK_SECRET=$(value PROGENIE_PAYMENT_WEBHOOK_SECRET)
PROGENIE_JWT_PRIVATE_KEY=$(value PROGENIE_JWT_PRIVATE_KEY)
PROGENIE_JWT_PUBLIC_KEY=$(value PROGENIE_JWT_PUBLIC_KEY)
MAILPIT_PASSWORD=$(value MAILPIT_PASSWORD)
EDGE_SECRET=$(value EDGE_SECRET)
VALUES
chmod 600 "$out"
echo "Created infra/nocard/render/.env.render (readable only by you; git-ignored)."
echo "Paste its contents into Render → your service → Environment → Add from .env, then delete the file if you like."
