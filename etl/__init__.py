"""
Trade Analytics ETL
====================
Consumes trade-events and market-data Kafka topics, aggregates data,
and writes analytical summaries to PostgreSQL (performance table).

Usage:
    python -m etl.main
"""
