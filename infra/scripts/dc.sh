#!/usr/bin/env bash
# docker compose for the SIT stack with the right files for this machine:
#   compose.yaml + compose.sit.yaml, plus compose.arm64.yaml on ARM, plus compose.tunnel.yaml when SIT_TUNNEL=true.
# Usage: ./scripts/dc.sh ps | logs -f api | restart api | ...   (any docker compose command)
set -euo pipefail
cd "$(dirname "$0")/.."

files=(-f compose.yaml -f compose.sit.yaml)
case "${SIT_ARCH:-$(uname -m)}" in
  aarch64 | arm64) files+=(-f compose.arm64.yaml) ;;
esac
if [ -f .env.sit ] && grep -qiE '^SIT_TUNNEL=.?true' .env.sit; then
  files+=(-f compose.tunnel.yaml)
fi
exec docker compose "${files[@]}" --env-file .env.sit --profile app "$@"
