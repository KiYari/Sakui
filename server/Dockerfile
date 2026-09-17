# syntax=docker/dockerfile:1
#
# Build context is the REPO ROOT (not ./server) — the Gradle build needs the root
# settings.gradle.kts, the wrapper, and the :server subproject together.
#   docker build -f server/Dockerfile .

# ---------- build ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Build descriptors first, so dependency resolution becomes its own cached layer
# and is not invalidated every time a .kt file changes.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY server/build.gradle.kts ./server/
# Windows checkouts don't carry the exec bit.
RUN chmod +x gradlew && ./gradlew --no-daemon :server:dependencies

COPY server/src ./server/src
RUN ./gradlew --no-daemon :server:installDist

# ---------- runtime ----------
FROM eclipse-temurin:21-jre AS runtime

# curl exists solely for the container healthcheck in docker-compose.yml.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 --create-home eeck

WORKDIR /app
COPY --from=build /src/server/build/install/server ./

# Ktor's embeddedServer already binds 0.0.0.0, which is what a container needs.
ENV EECK_PORT=3001
EXPOSE 3001

USER eeck
ENTRYPOINT ["/app/bin/server"]
