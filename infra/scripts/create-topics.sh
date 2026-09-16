#!/usr/bin/env bash
# ==============================================================================
# Enterprise Trading Platform - Kafka Topic Initialization Script
# Creates the contracted topics and dead-letter topics idempotently.
# ==============================================================================

set -euo pipefail

CONTAINER_NAME="trading-kafka"
BOOTSTRAP_SERVER="localhost:9092"

echo "Connecting to Kafka container '${CONTAINER_NAME}'..."

create_topic() {
    local topic=$1
    local partitions=$2
    local retention_ms=$3
    local rep_factor=${4:-1}

    echo "Creating topic '${topic}' (partitions=${partitions}, retention.ms=${retention_ms}, replication-factor=${rep_factor})..."
    
    if command -v kafka-topics.sh &> /dev/null; then
        kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVER}" --create --if-not-exists \
            --topic "${topic}" \
            --partitions "${partitions}" \
            --replication-factor "${rep_factor}" \
            --config retention.ms="${retention_ms}"
    elif command -v kafka-topics &> /dev/null; then
        kafka-topics --bootstrap-server "${BOOTSTRAP_SERVER}" --create --if-not-exists \
            --topic "${topic}" \
            --partitions "${partitions}" \
            --replication-factor "${rep_factor}" \
            --config retention.ms="${retention_ms}"
    else
        docker exec "${CONTAINER_NAME}" /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVER}" --create --if-not-exists \
            --topic "${topic}" \
            --partitions "${partitions}" \
            --replication-factor "${rep_factor}" \
            --config retention.ms="${retention_ms}"
    fi
}

# 1. orders: Partitions=3, Retention=7 days (604,800,000 ms)
create_topic "orders" 3 604800000

# 2. trade-events: Partitions=3, Retention=30 days (2,592,000,000 ms)
create_topic "trade-events" 3 2592000000

# 3. market-data: Partitions=6, Retention=1 day (86,400,000 ms)
create_topic "market-data" 6 86400000

# Dead-Letter Topics (.DLT)
create_topic "orders.DLT" 3 604800000
create_topic "trade-events.DLT" 3 2592000000
create_topic "market-data.DLT" 6 86400000

echo "=== All contracted topics created successfully ==="
