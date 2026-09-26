# ==============================================================================
# GENERIC TEMPLATE — Java service built with Maven (Spring Boot, or any plain
# executable jar). This file is intentionally project-agnostic: it doesn't
# reference this service's package names, class names, or artifact id
# anywhere, so you can copy it into a *different* Maven project (a
# classmate's capstone, a new microservice, etc.) and it will just work as
# long as that project has a pom.xml + src/ and produces one runnable jar.
#
# What you'd change per project — all as --build-arg flags, no editing:
#   APP_PORT     the port the app listens on                 (default 8080)
#   HEALTH_PATH  an HTTP health endpoint, or "" to disable it (default /health)
#   JAVA_VERSION must match your pom's <java.version>          (default 21)
#
#   docker build --build-arg APP_PORT=8085 -t my-service .
#   docker run -p 8085:8085 my-service
# ==============================================================================

# ---------- Stage 1: compile with Maven --------------------------------------
# Pin the JDK version the build compiles against and the JDK the app runs on
# to the SAME value (JAVA_VERSION), so "it built" actually means "it'll run".
ARG JAVA_VERSION=21
ARG MAVEN_IMAGE_TAG=3.9.9-eclipse-temurin-${JAVA_VERSION}
FROM maven:${MAVEN_IMAGE_TAG} AS builder
WORKDIR /app

# Copy only the POM before the source. Docker caches each layer by its inputs,
# so as long as pom.xml doesn't change, this dependency-download layer is
# reused on every later build — editing a .java file no longer means
# re-fetching the entire dependency tree.
COPY pom.xml .
RUN mvn -B dependency:go-offline -q

COPY src ./src
# -DskipTests: tests belong in CI (or a plain `mvn test` before you build the
# image), not gating `docker build` — a developer reproducing a bug shouldn't
# be blocked from getting a runnable image by an unrelated failing test.
RUN mvn -B package -DskipTests -q

# ---------- Stage 2: run on a slim JRE — no Maven, no JDK in the final image
ARG JAVA_VERSION=21
# *-jre-alpine keeps the runtime image small (tens of MB, not hundreds).
# Switch this tag to "${JAVA_VERSION}-jre-jammy" (glibc/Ubuntu) instead if
# this service ever shells out to native tooling that needs glibc — see this
# repo's executor/Dockerfile for a worked example of exactly that case
# (it also runs a Python subprocess, and Python's data-science wheels are
# glibc-only).
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine AS runtime
WORKDIR /app

# Never run a JVM as root in a container: if a dependency or the app itself
# is ever compromised, a non-root, no-login user limits what the attacker can
# touch on the host's behalf.
RUN addgroup -S app && adduser -S app -G app

# Wildcard glob instead of a hardcoded jar name/version: this line is
# identical whether your artifactId is "trade-api" or something else
# entirely, and it survives you bumping <version> in the pom. If your build
# produces more than one jar (e.g. a plain jar *and* a "-shaded" one), narrow
# this to the one you actually want, e.g. target/*-shaded.jar.
COPY --from=builder --chown=app:app /app/target/*.jar /app/app.jar

ARG APP_PORT=8085
ENV APP_PORT=${APP_PORT}
EXPOSE ${APP_PORT}

# Extra JVM flags without editing this file, e.g.:
#   docker run -e JAVA_OPTS="-Xmx256m -XX:+UseSerialGC" ...
ENV JAVA_OPTS=""

USER app

# Assumes the app exposes a plain HTTP health endpoint (Spring Boot
# Actuator's /actuator/health, or — as in this project — a hand-rolled
# controller at /health). Build with --build-arg HEALTH_PATH="" to disable
# this block for a project that doesn't have one; a healthcheck that always
# reports unhealthy is worse than having none at all.
ARG HEALTH_PATH=/health
ENV HEALTH_PATH=${HEALTH_PATH}
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD [ -z "$HEALTH_PATH" ] || wget -qO- "http://localhost:${APP_PORT}${HEALTH_PATH}" || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
