# ---- build stage: compile with Maven on JDK 25 ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- runtime stage: small JRE image, non-root user ----
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app \
 && mkdir -p /data/uploads && chown app:app /data/uploads   # KYC files (a named volume is mounted here)
WORKDIR /app
COPY --from=build /build/target/progenie-api.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
