# One image for the Session Service, the gateway (which also serves the web app) and the
# migrate command. Build context is the repository root:
#   docker build -f server/Dockerfile -t concord-server .
# Every base image is multi-arch, so building on the host gives its architecture (amd64 for
# local development, arm64 on the Ampere trial VM). devtools is dev-only and not in the image.

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY crdt-java/ crdt-java/
COPY server/ server/
WORKDIR /src/server
RUN ./gradlew --no-daemon -q :session:installDist :gateway:installDist

# The web app, built against client-core's and the engine's source (web/vite.config.ts).
FROM node:22-alpine AS web
WORKDIR /src
COPY crdt-ts/ crdt-ts/
COPY client-core/ client-core/
COPY web/package.json web/package-lock.json web/
RUN cd web && npm ci --no-audit --no-fund
COPY web/ web/
RUN cd web && npx vite build

# The JDK, not the JRE: jcmd and jstat inside the container (`docker exec <service> jcmd 1 GC.heap_info`)
# are how memory is checked on the trial host (docs/runbook.md). Costs about 250 MB of disk.
FROM eclipse-temurin:21-jdk-noble AS runtime
RUN useradd --system --uid 10001 concord
COPY --from=build /src/server/session/build/install/session /app/session
COPY --from=build /src/server/gateway/build/install/gateway /app/gateway
COPY --from=web /src/web/dist /app/web
USER concord
# Development default; production sets an explicit heap per service (deploy/prod/compose.yaml).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50"
CMD ["/app/session/bin/session"]

# The nightly backup job (deploy/prod/compose.yaml): the same code (dev.concord.session.Backup)
# plus pg_dump, age and rclone. pg_dump must be the server's major (16): a newer one writes
# dumps pg_restore 16 cannot read. Hence noble (24.04) and postgresql-client-16 by name.
FROM runtime AS backup
USER root
RUN apt-get update && apt-get install -y --no-install-recommends postgresql-client-16 age rclone curl && rm -rf /var/lib/apt/lists/*
COPY deploy/prod/backup/ /app/backup/
RUN chmod +x /app/backup/*.sh && mkdir -p /state && chown concord /state
USER concord
