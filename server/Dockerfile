# One image for the Session Service, the gateway and the migrate command.
# Build context is the repository root (the server build includes ../crdt-java):
#   docker build -f server/Dockerfile -t concord-server .
# devtools is dev-only and deliberately not in the image.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY crdt-java/ crdt-java/
COPY server/ server/
WORKDIR /src/server
RUN ./gradlew --no-daemon -q :session:installDist :gateway:installDist

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 concord
COPY --from=build /src/server/session/build/install/session /app/session
COPY --from=build /src/server/gateway/build/install/gateway /app/gateway
USER concord
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50"
# Overridden per service in deploy/docker-compose.yml.
CMD ["/app/session/bin/session"]
