# ProGenie back end for a free Render Web Service (no card). See docs/DEPLOYMENT_NOCARD.md.
# Render builds this from the repository root (Docker build context "."), so paths below start at the root.
#
# One small container, because a free instance has 512 MB of RAM and one public port:
#   Caddy on $PORT (the only way in; it checks the Cloudflare Worker's shared secret)
#   ├── the Spring Boot API on 127.0.0.1:8080
#   └── Mailpit on 127.0.0.1:8025 (testers read one-time codes at <site>/mailpit/)
# The database is Supabase (free). Its tables are created by infra/nocard/render/supabase-migrate.sh from your
# laptop, so this image needs nothing from the ProGenie_DB_Scripts repository.

# ---- build the API jar (dependencies are cached until pom.xml changes)
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY api/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY api/src ./src
RUN mvn -B -q package -DskipTests

# ---- runtime: Java 25 + Caddy + Mailpit
FROM eclipse-temurin:25-jre
COPY --from=caddy:2 /usr/bin/caddy /tmp/caddy
COPY --from=axllent/mailpit:v1.32.0 /mailpit /usr/local/bin/mailpit
WORKDIR /app
COPY --from=build /build/target/progenie-api.jar app.jar
COPY infra/nocard/render/start.sh infra/nocard/render/Caddyfile ./
RUN install -m 755 /tmp/caddy /usr/local/bin/caddy && rm /tmp/caddy \
 && chmod 755 start.sh \
 && useradd -m -u 10001 app \
 && mkdir -p /data/uploads /data/mailpit /data/caddy \
 && chown -R app /data
USER app
ENV HOME=/home/app XDG_DATA_HOME=/data/caddy XDG_CONFIG_HOME=/data/caddy TZ=UTC
EXPOSE 10000
ENTRYPOINT ["/app/start.sh"]
