"""
ETL configuration loaded from environment variables (with .env fallback).
"""
import os
from dotenv import load_dotenv

load_dotenv()

# Kafka
KAFKA_BOOTSTRAP_SERVERS: str = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
TRADE_EVENTS_TOPIC: str = os.getenv("TRADE_EVENTS_TOPIC", "trade-events")
MARKET_DATA_TOPIC: str = os.getenv("MARKET_DATA_TOPIC", "market-data")
CONSUMER_GROUP_ID: str = os.getenv("CONSUMER_GROUP_ID", "trade-etl")

# PostgreSQL
DB_HOST: str = os.getenv("DB_HOST", "localhost")
DB_PORT: int = int(os.getenv("DB_PORT", "5432"))
DB_NAME: str = os.getenv("DB_NAME", "trading_db")
DB_USER: str = os.getenv("DB_USER", "postgres")
DB_PASSWORD: str = os.getenv("DB_PASSWORD", "postgres")

# Behaviour
POLL_TIMEOUT_SECONDS: float = float(os.getenv("POLL_TIMEOUT_SECONDS", "1.0"))
BATCH_FLUSH_INTERVAL_SECONDS: int = int(os.getenv("BATCH_FLUSH_INTERVAL_SECONDS", "30"))
