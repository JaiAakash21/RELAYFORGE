# =============================================================================
# RelayForge webhook simulator image.
#
# A deliberately misbehaving webhook receiver, used by the Reliability
# Playground. It makes no outbound requests of any kind and is reachable only
# from inside the compose network.
# =============================================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY backend/pom.xml backend/
COPY webhook-simulator/pom.xml webhook-simulator/
RUN mvn -B -pl webhook-simulator -am dependency:go-offline -DskipTests
COPY webhook-simulator webhook-simulator
COPY backend/pom.xml backend/
RUN mvn -B -o -pl webhook-simulator -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
RUN groupadd --system --gid 1001 relayforge \
 && useradd --system --uid 1001 --gid relayforge --home /app relayforge
WORKDIR /app
COPY --from=build --chown=relayforge:relayforge \
     /build/webhook-simulator/target/relayforge-webhook-simulator.jar app.jar
USER relayforge
EXPOSE 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
