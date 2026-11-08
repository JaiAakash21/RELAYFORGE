# =============================================================================
# RelayForge backend image.
#
# Multi-stage. The build stage carries Maven, the JDK and the whole dependency
# cache; the runtime stage carries a JRE and one jar. That difference is roughly
# 700 MB, and everything left out of the final image is also something that
# cannot be exploited in it.
# =============================================================================

# ---- Stage 1: dependencies ---------------------------------------------------
# Resolved in their own layer, keyed only on the POMs. Docker reuses this layer
# for every build where no dependency changed — which is almost all of them —
# so a one-line code change does not re-download the internet.
FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /build
COPY pom.xml ./
COPY backend/pom.xml backend/
COPY webhook-simulator/pom.xml webhook-simulator/
RUN mvn -B -pl backend -am dependency:go-offline -DskipTests

# ---- Stage 2: build ----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Bring the populated local repository across so this stage resolves offline.
COPY --from=deps /root/.m2 /root/.m2
COPY pom.xml ./
COPY backend backend
COPY webhook-simulator/pom.xml webhook-simulator/
RUN mvn -B -o -pl backend -am package -DskipTests

# Unpack the fat jar into its layers. Spring Boot's layertools separates
# dependencies (which rarely change) from application classes (which change on
# every commit), so a redeploy ships a few hundred KB instead of ~60 MB.
WORKDIR /layers
RUN cp /build/backend/target/relayforge-backend.jar app.jar \
 && java -Djarmode=layertools -jar app.jar extract

# ---- Stage 3: runtime --------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl for the container healthcheck. Installed in one layer and the apt lists
# removed, so the cache does not persist into the image.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# A non-root user. A container process that does not need root should not have
# it: if the application is compromised, the attacker lands as an unprivileged
# user that owns nothing on the filesystem.
RUN groupadd --system --gid 1001 relayforge \
 && useradd --system --uid 1001 --gid relayforge --home /app relayforge

WORKDIR /app
COPY --from=build --chown=relayforge:relayforge /layers/dependencies/ ./
COPY --from=build --chown=relayforge:relayforge /layers/spring-boot-loader/ ./
COPY --from=build --chown=relayforge:relayforge /layers/snapshot-dependencies/ ./
COPY --from=build --chown=relayforge:relayforge /layers/application/ ./

USER relayforge
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then sizes its heap from
# the container's cgroup limit, so changing the compose memory limit is enough
# and the two cannot drift apart.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

# Uses the readiness group, which checks the database and Redis — not merely
# that the process is listening. A container that is up but cannot reach
# PostgreSQL should not be marked healthy.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
