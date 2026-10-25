#!/usr/bin/env bash
# Writes infra/nocard/render/.env.render (git-ignored): every setting the Render service needs, ready to paste
# into Render → your service → Environment → "Add from .env". See docs/DEPLOYMENT_NOCARD.md.
#
#   bash infra/nocard/render/render-env.sh https://progenie.<your-subdomain>.workers.dev
#
# Reads the secrets from infra/nocard/.env.nocard (scripts/nocard-secrets.sh) and asks for the Supabase
# Session pooler connection string unless SUPABASE_URL is set. Then asks for the Supabase Storage S3 access key
# (DEPLOYMENT_NOCARD.md step 3e) unless S3_ACCESS_KEY / S3_SECRET_KEY are set; press Enter to skip it and keep
# files on the container's disk (they are lost on every restart). Last, asks for a contact e-mail for
# OpenStreetMap's geocoder (address form: PIN code <-> map pin) unless GEOCODING_EMAIL is set.
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

s3_key=${S3_ACCESS_KEY:-}
s3_secret=${S3_SECRET_KEY:-}
if [ -z "$s3_key" ]; then
  read -rp "Supabase Storage S3 access key ID (Enter to skip): " s3_key
fi
if [ -n "$s3_key" ] && [ -z "$s3_secret" ]; then
  read -rsp "Supabase Storage S3 secret access key (not shown): " s3_secret
  echo
fi
if [ -n "$s3_key" ] && { [ -z "$s3_secret" ] || [ -z "$SUPABASE_REF" ] || [ -z "$SUPABASE_REGION" ]; }; then
  echo "Need the secret key, and a Session pooler string like postgres.<ref>@aws-0-<region>.pooler.supabase.com" >&2
  exit 1
fi

geo_email=${GEOCODING_EMAIL:-}
if [ -z "$geo_email" ]; then
  read -rp "Contact e-mail sent to OpenStreetMap's geocoder, as its usage policy asks (Enter to skip): " geo_email
fi

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
if [ -n "$s3_key" ]; then
  cat >> "$out" <<VALUES
PROGENIE_STORAGE_TYPE=s3
S3_ENDPOINT=https://$SUPABASE_REF.supabase.co/storage/v1/s3
S3_REGION=$SUPABASE_REGION
S3_BUCKET=${S3_BUCKET:-progenie}
S3_ACCESS_KEY=$s3_key
S3_SECRET_KEY=$s3_secret
VALUES
  echo "Files will be stored in the Supabase Storage bucket '${S3_BUCKET:-progenie}' (region $SUPABASE_REGION)."
else
  echo "No S3 key given: uploaded files stay on the container's disk and are lost on every restart."
fi
if [ -n "$geo_email" ]; then
  echo "PROGENIE_GEOCODING_EMAIL=$geo_email" >> "$out"
fi
chmod 600 "$out"
echo "Created infra/nocard/render/.env.render (readable only by you; git-ignored)."
echo "Paste its contents into Render → your service → Environment → Add from .env, then delete the file if you like."
