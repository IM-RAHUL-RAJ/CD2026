# ==============================================================================
# GENERIC TEMPLATE — Java service (Maven) that also spawns a companion Python
# script as a subprocess at runtime. Common shape for a "hybrid" component:
# a JVM app that does its heavy analytics/ETL work by shelling out to Python
# (pandas/duckdb/etc.) instead of reimplementing it in Java.
#
# This file is project-agnostic: no hardcoded package names, class names, or
# artifact ids. Point it at a different Maven project that also ships one or
# more standalone .py scripts next to its pom.xml, and it works unchanged.
#
# What you'd change per project — all as --build-arg flags, no editing:
#   APP_PORT         port the app listens on                    (default 8083)
#   JAVA_VERSION     must match your pom's <java.version>        (default 21)
#   PY_REQUIREMENTS  requirements file to pip install            (default requirements.txt)
#   PY_SCRIPTS       space-separated .py files to copy alongside the jar
#                    (default "analytics_pipeline.py etl_trigger_service.py")
#
#   docker build --build-arg APP_PORT=8083 -t my-service .
#   docker run -p 8083:8083 my-service
#
# If your Java code does NOT spawn a Python subprocess, use the plain
# sprint8/Dockerfile template instead — it's the same idea minus the Python
# runtime, and produces a smaller image.
# ==============================================================================

# ---------- Stage 1: compile with Maven --------------------------------------
ARG JAVA_VERSION=21
ARG MAVEN_IMAGE_TAG=3.9.9-eclipse-temurin-${JAVA_VERSION}
FROM maven:${MAVEN_IMAGE_TAG} AS builder
WORKDIR /app

# Copy only the POM before the source so the dependency-download layer is
# cached across builds and only invalidated when pom.xml actually changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline -q

COPY src ./src
# -DskipTests: tests belong in CI, not in `docker build` — a dev reproducing
# a bug shouldn't be blocked from getting a runnable image by an unrelated
# failing test.
RUN mvn -B package -DskipTests -q

# ---------- Stage 2: runtime — JRE + Python side by side ---------------------
ARG JAVA_VERSION=21
# IMPORTANT: this is *-jre-jammy (Debian/glibc), not *-jre-alpine (musl).
# Alpine would be smaller, but the Python data/analytics stack this stage
# installs below (pandas, numpy, duckdb, psycopg2-binary, and similar) only
# publish prebuilt "manylinux" wheels, which are glibc-only. On Alpine, pip
# would fall back to compiling each of those from source — slow, and for
# duckdb specifically requires a full C++ toolchain that isn't installed
# here. If your companion Python script has no such dependencies, you can
# switch this to an alpine base and add the matching apk python3 package.
FROM eclipse-temurin:${JAVA_VERSION}-jre-jammy AS runtime
WORKDIR /app

# System Python 3, for running the companion script(s) as a subprocess.
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip \
    && rm -rf /var/lib/apt/lists/*

# Python deps layer — cached independently of the Java build above and of
# the script copy below, so editing a .py file doesn't force a pip re-run.
ARG PY_REQUIREMENTS=requirements.txt
COPY ${PY_REQUIREMENTS} requirements.txt
# --break-system-packages: Debian 12+ marks the system Python as
# externally-managed (PEP 668); this is a single-purpose container image,
# not a shared system, so installing straight into it is the correct call.
RUN pip3 install --no-cache-dir --break-system-packages -r requirements.txt

# Companion script(s) the jar will spawn via ProcessBuilder/subprocess at
# runtime. Two separate COPY instructions because Docker's ARG substitution
# does not word-split a space-separated value into multiple COPY sources.
ARG PY_SCRIPT_1=analytics_pipeline.py
ARG PY_SCRIPT_2=etl_trigger_service.py
COPY ${PY_SCRIPT_1} ${PY_SCRIPT_2} ./

# Non-root user for security. Owns /app so the subprocess can write logs,
# lock files, or (if pointed here) a local analytics DB file.
RUN groupadd -r app && useradd -r -g app -d /app app \
    && chown -R app:app /app
USER app

# Wildcard glob instead of a hardcoded jar name/version — works regardless
# of this project's artifactId or <version>. Narrow to target/*-shaded.jar
# etc. if your build produces more than one jar.
COPY --from=builder --chown=app:app /app/target/*.jar /app/app.jar

ARG APP_PORT=8083
ENV APP_PORT=${APP_PORT}
EXPOSE ${APP_PORT}

# Extra JVM flags without editing this file, e.g.:
#   docker run -e JAVA_OPTS="-Xmx256m -XX:+UseSerialGC" ...
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
