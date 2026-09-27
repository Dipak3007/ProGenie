#!/usr/bin/env bash
# Creates infra/.env.sit for the SIT server with fresh random secrets.
# Usage (on the server):  ./scripts/sit-secrets.sh <sit-domain> <email-for-lets-encrypt>
# Never commit .env.sit; it is git-ignored. Run once; it refuses to overwrite an existing file.
set -euo pipefail
cd "$(dirname "$0")/.."

domain=${1:?usage: scripts/sit-secrets.sh <sit-domain> <email>}
email=${2:?usage: scripts/sit-secrets.sh <sit-domain> <email>}
[ -e .env.sit ] && { echo "infra/.env.sit already exists; not overwriting it." >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

umask 077
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# RS256 key pair for access tokens (PKCS#8 private key, X.509 public key), passed as base64 of the PEM files
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp/jwt.pem" 2>/dev/null
openssl pkey -in "$tmp/jwt.pem" -pubout -out "$tmp/jwt.pub"

mailpit_password=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-20)
mailpit_hash=$(docker run --rm caddy:2-alpine caddy hash-password --plaintext "$mailpit_password")

V_SIT_DOMAIN="$domain"
V_ACME_EMAIL="$email"
V_POSTGRES_PASSWORD=$(openssl rand -hex 24)
V_PROGENIE_OTP_PEPPER=$(openssl rand -hex 32)
V_PROGENIE_PAYMENT_WEBHOOK_SECRET=$(openssl rand -hex 24)
V_PROGENIE_JWT_PRIVATE_KEY=$(base64 -w0 < "$tmp/jwt.pem")
V_PROGENIE_JWT_PUBLIC_KEY=$(base64 -w0 < "$tmp/jwt.pub")
V_MAILPIT_PASSWORD_HASH="$mailpit_hash"
V_MSG91_WEBHOOK_TOKEN=$(openssl rand -hex 16)
export V_SIT_DOMAIN V_ACME_EMAIL V_POSTGRES_PASSWORD V_PROGENIE_OTP_PEPPER V_PROGENIE_PAYMENT_WEBHOOK_SECRET \
  V_PROGENIE_JWT_PRIVATE_KEY V_PROGENIE_JWT_PUBLIC_KEY V_MAILPIT_PASSWORD_HASH V_MSG91_WEBHOOK_TOKEN

# Copy the template, filling the generated values. Single quotes keep "$" in the bcrypt hash literal.
awk -v q="'" '
  /^[A-Z0-9_]+=/ {
    key = substr($0, 1, index($0, "=") - 1)
    if (("V_" key) in ENVIRON) { print key "=" q ENVIRON["V_" key] q; next }
  }
  { print }
' sit.env.example > .env.sit
chmod 600 .env.sit

cat <<MSG
Created infra/.env.sit (readable only by you). Keep a copy in your password manager.

  Site:           https://$domain
  Mailpit inbox:  https://$domain/mailpit
  Mailpit login:  tester / $mailpit_password   (shown only now)

Next: ./scripts/sit-deploy.sh
MSG
