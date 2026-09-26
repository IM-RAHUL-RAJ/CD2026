# ==============================================================================
# GENERIC TEMPLATE — Python service (Flask, FastAPI, a plain script, etc.).
# Project-agnostic: no hardcoded module names or framework assumptions
# beyond "pip install -r requirements.txt, then run one entry file."
#
# What you'd change per project — all as --build-arg flags, no editing:
#   PYTHON_VERSION  interpreter version                         (default 3.11)
#   APP_PORT        port the app listens on, and what the
#                   healthcheck probes                          (default 5000)
#   APP_ENTRY       command to launch the app                   (default "python app.py")
#   HEALTH_PATH     HTTP path to probe on localhost:APP_PORT,
#                   or "" to disable the healthcheck            (default /)
#
#   docker build --build-arg APP_PORT=5000 --build-arg APP_ENTRY="python app.py" \
#                -t my-service .
#   docker run -p 5000:5000 my-service
#
# Base image note: this uses python:*-slim (Debian/glibc), not
# python:*-alpine (musl). Data/analytics packages such as pandas, numpy, and
# duckdb only ship prebuilt "manylinux" (glibc) wheels — on Alpine, pip
# would fall back to compiling them from source, which is slow and, for
# some packages, requires a C/C++ toolchain that isn't installed here. If
# your project has no such dependencies, alpine + `apk add py3-pip` is a
# smaller alternative.
# ==============================================================================

ARG PYTHON_VERSION=3.11
FROM python:${PYTHON_VERSION}-slim
WORKDIR /app

# requirements.txt before source: this install layer is cached and reused
# across builds as long as dependencies haven't changed.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# Non-root user for security.
RUN groupadd -r appuser && useradd -r -g appuser appuser \
    && chown -R appuser:appuser /app
USER appuser

ARG APP_PORT=5000
ENV APP_PORT=${APP_PORT}
EXPOSE ${APP_PORT}

# python:*-slim ships neither wget nor curl (unlike Alpine's BusyBox), so
# the healthcheck uses Python's own stdlib to make the request. Single-
# quoted -c argument, string-concatenated rather than an f-string, so the
# ${APP_PORT}/${HEALTH_PATH} substitution from the shell doesn't collide
# with Python's own quoting.
ARG HEALTH_PATH=/
ENV HEALTH_PATH=${HEALTH_PATH}
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD [ -z "$HEALTH_PATH" ] || python -c \
        'import os, urllib.request; urllib.request.urlopen("http://localhost:" + os.environ["APP_PORT"] + os.environ["HEALTH_PATH"], timeout=3)' \
        || exit 1

ARG APP_ENTRY="python app.py"
ENV APP_ENTRY=${APP_ENTRY}
ENTRYPOINT ["sh", "-c", "$APP_ENTRY"]
