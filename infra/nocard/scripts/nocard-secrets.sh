#!/usr/bin/env bash
# Generates every secret the no-card setup needs and saves them to infra/nocard/.env.nocard (git-ignored,
# readable only by you). You then paste them into the Hugging Face Space and Cloudflare. See docs/DEPLOYMENT_NOCARD.md.
#
#   bash infra/nocard/scripts/nocard-secrets.sh
#
# Run once. It refuses to overwrite an existing file, because changing the secrets later logs everyone out
# and the Worker stops matching the Space until both have the new EDGE_SECRET.
set -euo pipefail
cd "$(dirname "$0")/.."
out=.env.nocard
[ -e "$out" ] && { echo "infra/nocard/$out already exists; not overwriting it." >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

umask 077
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# RS256 key pair for access tokens (PKCS#8 private key, X.509 public key), as base64 of the PEM files
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp/jwt.pem" 2>/dev/null
openssl pkey -in "$tmp/jwt.pem" -pubout -out "$tmp/jwt.pub"
jwt_private=$(openssl base64 -A -in "$tmp/jwt.pem")
jwt_public=$(openssl base64 -A -in "$tmp/jwt.pub")
[ -n "$jwt_private" ] && [ -n "$jwt_public" ] || { echo "Could not create the JWT key pair" >&2; exit 1; }

cat > "$out" <<VALUES
# ProGenie no-card SIT secrets. Never commit this file (.gitignore covers .env.*). Keep a copy in a password manager.

# ---- Hugging Face Space → Settings → Variables and secrets → New secret (one per line below)
DB_PASSWORD=$(openssl rand -hex 24)
PROGENIE_OTP_PEPPER=$(openssl rand -hex 32)
PROGENIE_PAYMENT_WEBHOOK_SECRET=$(openssl rand -hex 24)
PROGENIE_JWT_PRIVATE_KEY=$jwt_private
PROGENIE_JWT_PUBLIC_KEY=$jwt_public
MAILPIT_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-20)

# ---- Both places: a Space secret AND a Cloudflare Worker secret (they must match)
EDGE_SECRET=$(openssl rand -hex 32)
VALUES
chmod 600 "$out"

cat <<MSG
Created infra/nocard/$out (readable only by you).

  1. Hugging Face Space → Settings → Variables and secrets: add every value above as a *secret*.
  2. Cloudflare: cd infra/nocard/cloudflare && npx wrangler@4 secret put EDGE_SECRET   (paste the same EDGE_SECRET),
     then npx wrangler@4 secret put HF_SPACE_TOKEN   (the read-only Hugging Face token, step 4c of the guide)
  3. Mailpit login for testers: tester / the MAILPIT_PASSWORD value.
MSG
