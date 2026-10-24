# syntax=docker/dockerfile:1
#
# ProGenie SIT backend for a free Hugging Face Docker Space (no card needed). See docs/DEPLOYMENT_NOCARD.md.
# One container: PostgreSQL 17 + PostGIS, the Spring Boot API, Mailpit and Caddy (the only open port, 7860).
#
# Your code is NOT stored in this Space. At build time it is fetched from your private GitHub repositories with
# the Space secret GH_TOKEN (a read-only token), at the commit named in DEPLOY_REF. infra/nocard/scripts/
# push-hf-space.sh writes DEPLOY_REF and pushes these files; every push makes Hugging Face rebuild.

# ---- 1. fetch the code (the token is only used inside this step and never ends up in an image layer)
FROM alpine:3.22 AS source
RUN apk add --no-cache git
WORKDIR /src
COPY DEPLOY_REF .
RUN --mount=type=secret,id=GH_TOKEN,mode=0444,required=true sh -eu -c '\
  . ./DEPLOY_REF; \
  token=$(cat /run/secrets/GH_TOKEN); \
  fetch() { \
    mkdir -p "/src/$1" && cd "/src/$1" && git init -q && \
    git fetch -q --depth 1 "https://x-access-token:${token}@github.com/$2.git" "$3" && \
    git checkout -q FETCH_HEAD && rm -rf .git || { \
      echo "Could not fetch $2 at $3. Check that the repository exists and that GH_TOKEN can read it." >&2; exit 1; }; \
  }; \
  fetch app "$APP_REPO" "$APP_REF"; \
  fetch db "$DB_REPO" "$DB_REF"'

# ---- 2. build the API jar (dependencies are cached until pom.xml changes)
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY --from=source /src/app/api/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY --from=source /src/app/api/src ./src
RUN mvn -B -q package -DskipTests

# ---- 3. runtime: PostGIS image + Java + Caddy + Mailpit + the backup tool
FROM postgis/postgis:17-3.5
ENV JAVA_HOME=/opt/java/openjdk
COPY --from=eclipse-temurin:25-jre /opt/java/openjdk /opt/java/openjdk
COPY --from=caddy:2 /usr/bin/caddy /usr/local/bin/caddy
COPY --from=axllent/mailpit:v1.32.0 /mailpit /usr/local/bin/mailpit
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl python3 python3-venv \
 && rm -rf /var/lib/apt/lists/* \
 && python3 -m venv /opt/backup \
 && /opt/backup/bin/pip install --no-cache-dir huggingface_hub==2.3.0 \
 && useradd -m -u 1000 user

WORKDIR /app
COPY --from=build /build/target/progenie-api.jar app.jar
COPY --from=source /src/db/migrations ./migrations
COPY --chmod=755 start.sh ./
COPY backup.py Caddyfile DEPLOY_REF ./

# Hugging Face runs the container as user 1000; PostgreSQL refuses to run as root anyway.
USER user
ENV HOME=/home/user \
    PATH=/opt/backup/bin:/opt/java/openjdk/bin:/usr/lib/postgresql/17/bin:$PATH \
    PORT=7860 \
    TZ=UTC
EXPOSE 7860
ENTRYPOINT ["/app/start.sh"]
CMD []
