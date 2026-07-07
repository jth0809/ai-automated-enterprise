# syntax=docker/dockerfile:1

# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Dependency layer cache: re-resolved only when pom.xml changes.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
# Tests already ran in CI before the image build.
RUN mvn -B -ntp package -DskipTests

# ---------- Runtime stage ----------
# Ubuntu-based JRE: multi-arch (ARM A1 nodes), unlike the alpine variant.
FROM eclipse-temurin:21-jre
# The base bundles pebble (Ubuntu's service manager); the java entrypoint
# never uses it and its embedded Go deps trip the Trivy HIGH/CRITICAL
# gate — remove the binary instead of ignoring the CVEs.
RUN rm -f /usr/bin/pebble && useradd --system --no-create-home --uid 10001 appuser
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar

# Numeric UID so the K8s runAsNonRoot check can verify it.
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
