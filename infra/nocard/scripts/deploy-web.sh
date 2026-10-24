#!/usr/bin/env bash
# Builds the Angular app and deploys it, with the /api proxy (worker.js), to Cloudflare Workers.
#
#   BACKEND_URL=https://<hf-user>-progenie-api.hf.space bash infra/nocard/scripts/deploy-web.sh
#
# Log in once with `npx wrangler@4 login` (opens the browser), or set CLOUDFLARE_API_TOKEN and
# CLOUDFLARE_ACCOUNT_ID (that's what the GitHub workflow does). The Worker's secrets (EDGE_SECRET, HF_SPACE_TOKEN)
# are set once with `wrangler secret put` and kept across deploys. See docs/DEPLOYMENT_NOCARD.md.
set -euo pipefail
root=$(cd "$(dirname "$0")/../../.." && pwd)

backend=${BACKEND_URL:?set BACKEND_URL to the Space address, e.g. https://dipak-progenie-api.hf.space}
backend=${backend%/}
case $backend in
  https://*.hf.space) ;;
  https://*) echo "Note: BACKEND_URL doesn't end in .hf.space; using it anyway." ;;
  *) echo "BACKEND_URL must start with https://" >&2; exit 1 ;;
esac

cd "$root/web"
[ -d node_modules ] || npm ci --no-audit --no-fund
npm run build
cp "$root/infra/nocard/cloudflare/_headers" dist/web/browser/_headers

cd "$root/infra/nocard/cloudflare"
npx --yes wrangler@4 deploy --var "BACKEND_URL:$backend" "$@"
