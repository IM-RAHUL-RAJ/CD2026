# ---- Stage 1: Build deps ----
FROM python:3.12-slim AS builder

WORKDIR /app

# Install build tools needed by some C-extension wheels (e.g. confluent-kafka)
RUN apt-get update && apt-get install -y --no-install-recommends \
        gcc \
        librdkafka-dev \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt

# ---- Stage 2: Runtime ----
FROM python:3.12-slim

WORKDIR /app

# Runtime system libs for librdkafka
RUN apt-get update && apt-get install -y --no-install-recommends \
        librdkafka1 \
    && rm -rf /var/lib/apt/lists/*

# Non-root user
RUN useradd -m etl
USER etl

# Copy installed packages from builder
COPY --from=builder /install /usr/local

# Copy source
COPY . .

CMD ["python", "-m", "etl.main"]
