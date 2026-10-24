#!/usr/bin/env bash
# Creates infra/nocard/azure/.env (git-ignored) on the VM from infra/nocard/.env.nocard, which you copied
# from your laptop (docs/DEPLOYMENT_NOCARD.md). Run once:
#
#   bash infra/nocard/azure/make-env.sh <api-host> <site-url> <your-email>
#   e.g. bash infra/nocard/azure/make-env.sh progenie-api.centralindia.cloudapp.azure.com \
#          https://progenie.dipak-progenie.workers.dev you@example.com
# On your laptop, laptop/start.sh runs it for you (the API host is then "laptop": the tunnel replaces it).
set -euo pipefail
cd "$(dirname "$0")"
usage="usage: bash make-env.sh <api-host> <site-url> <email-for-lets-encrypt>"
api_host=${1:?$usage}
site_url=${2:?$usage}
email=${3:?$usage}
site_url=${site_url%/}
api_host=${api_host#https://}
api_host=${api_host%/}
[[ $site_url == https://* ]] || { echo "The site URL must start with https://" >&2; exit 1; }
[[ $api_host =~ ^[A-Za-z0-9.-]+$ ]] || { echo "The API host must be a host name like progenie-api.centralindia.cloudapp.azure.com" >&2; exit 1; }
[ -e .env ] && { echo "infra/nocard/azure/.env already exists; not overwriting it." >&2; exit 1; }
secrets=../.env.nocard
[ -f "$secrets" ] || { echo "infra/nocard/.env.nocard is missing: copy it from your laptop first (see the guide)" >&2; exit 1; }

value() { grep -E "^$1=" "$secrets" | head -n1 | cut -d= -f2-; }   # the value of NAME= in .env.nocard
for name in DB_PASSWORD PROGENIE_OTP_PEPPER PROGENIE_PAYMENT_WEBHOOK_SECRET PROGENIE_JWT_PRIVATE_KEY \
            PROGENIE_JWT_PUBLIC_KEY MAILPIT_PASSWORD EDGE_SECRET; do
  [ -n "$(value "$name")" ] || { echo "$name is missing from infra/nocard/.env.nocard" >&2; exit 1; }
done
mailpit_password=$(value MAILPIT_PASSWORD)
mailpit_hash=$(docker run --rm caddy:2-alpine caddy hash-password --plaintext "$mailpit_password")
[[ $mailpit_hash == \$2* ]] || { echo "Could not create the Mailpit password hash" >&2; exit 1; }

umask 077
# Single quotes keep the "$" in the bcrypt hash literal for Docker Compose
cat > .env <<VALUES
# ProGenie back end on Azure: settings for compose.yaml. Created by make-env.sh. Never commit this file.
API_HOST='$api_host'
SITE_URL='$site_url'
ACME_EMAIL='$email'
DB_PASSWORD='$(value DB_PASSWORD)'
PROGENIE_OTP_PEPPER='$(value PROGENIE_OTP_PEPPER)'
PROGENIE_PAYMENT_WEBHOOK_SECRET='$(value PROGENIE_PAYMENT_WEBHOOK_SECRET)'
PROGENIE_JWT_PRIVATE_KEY='$(value PROGENIE_JWT_PRIVATE_KEY)'
PROGENIE_JWT_PUBLIC_KEY='$(value PROGENIE_JWT_PUBLIC_KEY)'
EDGE_SECRET='$(value EDGE_SECRET)'
MAILPIT_HASH='$mailpit_hash'

# Optional (see compose.yaml), e.g.:
# PROGENIE_COMPANY_LEGAL_NAME='ProGenie (test)'
# PROGENIE_PAYMENT_GATEWAY='razorpay'   plus RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET / RAZORPAY_WEBHOOK_SECRET (test keys only)
VALUES
chmod 600 .env
echo "Created infra/nocard/azure/.env. Next: bash infra/nocard/azure/deploy.sh"
