#!/usr/bin/env bash
# Prepares OpenStreetMap road data for the OSRM router (car profile, MLD algorithm). Run it once, and again
# whenever you want fresher roads (monthly is plenty). Then: docker compose --profile osrm up -d
#
#   bash infra/osrm/prepare.sh
#
# Defaults: Geofabrik's "India - western zone" extract (Gujarat, Maharashtra, Goa, ...; about 300 MB), cut down to
# Gujarat's box when osmium-tool is installed (sudo apt install osmium-tool), which keeps memory around 1-2 GB.
# Without osmium the whole zone is used (more memory: allow 4-6 GB while preparing).
# Another region:  REGION_URL=https://download.geofabrik.de/asia/india-latest.osm.pbf BBOX=72.3,22.8,72.8,23.25 bash ...
# Needs Docker. Output: infra/osrm/data/region.osrm* (git-ignored).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
data=$here/data
image=ghcr.io/project-osrm/osrm-backend:v5.27.1
url=${REGION_URL:-https://download.geofabrik.de/asia/india/western-zone-latest.osm.pbf}
bbox=${BBOX:-68.0,20.0,74.6,24.8}   # Gujarat: west,south,east,north
mkdir -p "$data"

src=$data/source.osm.pbf
if [ ! -s "$src" ] || [ -n "${REFRESH:-}" ]; then
  echo "Downloading $url ..."
  curl -fL --retry 3 -o "$src.part" "$url"
  mv "$src.part" "$src"
fi

if command -v osmium >/dev/null 2>&1; then
  echo "Cutting out the box $bbox with osmium ..."
  osmium extract --overwrite -b "$bbox" -o "$data/region.osm.pbf" "$src"
else
  echo "osmium-tool not found: using the whole download (install osmium-tool to use less memory)."
  cp "$src" "$data/region.osm.pbf"
fi

run() { docker run --rm -t -v "$data:/data" "$image" "$@"; }
echo "osrm-extract (the longest step: a few minutes) ..."
run osrm-extract -p /opt/car.lua /data/region.osm.pbf
echo "osrm-partition ..."
run osrm-partition /data/region.osrm
echo "osrm-customize ..."
run osrm-customize /data/region.osrm
rm -f "$data/region.osm.pbf"

echo
echo "Done. Start the router:   cd infra && docker compose --profile osrm up -d"
echo "Try it:                   curl 'http://localhost:5000/route/v1/driving/72.505,23.04;72.561,23.035?overview=false'"
echo "Use it in the API:        PROGENIE_DISTANCE_PROVIDER=osrm mvn spring-boot:run"
