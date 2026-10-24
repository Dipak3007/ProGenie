#!/usr/bin/env bash
# Builds the Angular app and deploys it, with the /api proxy (worker.js), to Cloudflare Workers.
#
#   BACKEND_URL=https://<api-host> bash infra/nocard/scripts/deploy-web.sh
#   (<api-host> is the Azure VM's DNS name, e.g. progenie-api.centralindia.cloudapp.azure.com)
#
# Log in once with `npx wrangler@4 login` (opens the browser), or set CLOUDFLARE_API_TOKEN and
# CLOUDFLARE_ACCOUNT_ID (that's what the GitHub workflow does). The Worker's secret EDGE_SECRET is set once
# with `wrangler secret put EDGE_SECRET` and kept across deploys. See docs/DEPLOYMENT_NOCARD.md.
set -euo pipefail
root=$(cd "$(dirname "$0")/../../.." && pwd)

backend=${BACKEND_URL:?set BACKEND_URL to the back end, e.g. https://progenie-api.centralindia.cloudapp.azure.com}
backend=${backend%/}
[[ $backend == https://* ]] || { echo "BACKEND_URL must start with https://" >&2; exit 1; }

cd "$root/web"
# SKIP_BUILD=1 (used by laptop/start.sh) only changes BACKEND_URL and reuses the last build
if [ "${SKIP_BUILD:-}" != 1 ] || [ ! -f dist/web/browser/index.html ]; then
  [ -d node_modules ] || npm ci --no-audit --no-fund
  npm run build
fi
cp "$root/infra/nocard/cloudflare/_headers" dist/web/browser/_headers

cd "$root/infra/nocard/cloudflare"
npx --yes wrangler@4 deploy --var "BACKEND_URL:$backend" "$@"
