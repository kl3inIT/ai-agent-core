# syntax=docker/dockerfile:1.7
# =========================================================================
# Builds a runtime image from a pre-built bootJar.
#
# Expects `dth-crm.jar` next to this file (same directory):
#   ./Dockerfile
#   ./dth-crm.jar
#
# Build locally:
#   ./gradlew -Pvaadin.productionMode=true bootJar
#   cp build/libs/dth-crm.jar .
#   docker build -t dthcrm-app:local .
#
# Or via docker-compose.server.yml (compose builds automatically).
# =========================================================================
FROM eclipse-temurin:21.0.3_9-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl \
        libfreetype6 \
        fonts-dejavu \
        fontconfig \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 1001 dthcrm \
    && useradd  --system --uid 1001 --gid dthcrm --home-dir /app --shell /sbin/nologin dthcrm \
    && mkdir -p /app/heapdumps /app/.jmix /app/file-storage \
    && chown -R dthcrm:dthcrm /app

WORKDIR /app
COPY --chown=dthcrm:dthcrm dth-crm.jar /app/app.jar

ENV DTH_SERVER_PORT=8080

USER dthcrm
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 \
    CMD curl -fsS http://localhost:8080/ > /dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
