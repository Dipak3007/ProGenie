#!/usr/bin/env bash
# Stops the ProGenie back end on your laptop. The data stays (Docker volumes progenie-azure_*);
# start.sh brings it back. The website then shows "The server is offline" until you start it again.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
docker compose -f "$here/../azure/compose.yaml" -f "$here/compose.laptop.yaml" stop
echo "Stopped. Start again with: bash infra/nocard/laptop/start.sh"
