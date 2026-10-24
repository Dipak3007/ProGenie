# PostgreSQL 17 + PostGIS 3 for ARM servers (e.g. Oracle Cloud's free Ampere VMs).
# The official postgis/postgis image is published for x86 only; this builds the same thing on any architecture:
# the postgres image already has the PostgreSQL apt repository configured, which ships PostGIS for arm64 and amd64.
# Used by compose.arm64.yaml. The schema itself (CREATE EXTENSION postgis, ...) comes from Flyway's V1 migration.
FROM postgres:17-bookworm
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-17-postgis-3 postgresql-17-postgis-3-scripts \
 && rm -rf /var/lib/apt/lists/*
