"""
Kafka consumer wrapper for the ETL.
Subscribes to trade-events and/or market-data topics and yields
deserialized JSON messages.
"""
import json
import logging
from typing import Generator

from confluent_kafka import Consumer, KafkaError, KafkaException, Message

from etl import config

log = logging.getLogger(__name__)


def make_consumer(topics: list[str]) -> Consumer:
    """Creates and subscribes a Confluent Kafka Consumer."""
    consumer = Consumer({
        "bootstrap.servers": config.KAFKA_BOOTSTRAP_SERVERS,
        "group.id": config.CONSUMER_GROUP_ID,
        "auto.offset.reset": "earliest",
        "enable.auto.commit": False,
        "isolation.level": "read_committed",
    })
    consumer.subscribe(topics)
    log.info("Subscribed to topics: %s", topics)
    return consumer


def consume_messages(consumer: Consumer,
                     poll_timeout: float = 1.0) -> Generator[dict, None, None]:
    """
    Infinite generator that yields parsed JSON dicts from Kafka.
    Caller is responsible for committing offsets and closing the consumer.
    """
    while True:
        msg: Message | None = consumer.poll(timeout=poll_timeout)
        if msg is None:
            yield {}  # empty heartbeat — allows caller to do periodic work
            continue

        if msg.error():
            if msg.error().code() == KafkaError._PARTITION_EOF:
                log.debug("Reached end of partition %s/%s",
                          msg.topic(), msg.partition())
                continue
            raise KafkaException(msg.error())

        try:
            value = json.loads(msg.value().decode("utf-8"))
            value["_topic"] = msg.topic()
            value["_partition"] = msg.partition()
            value["_offset"] = msg.offset()
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            log.warning("Skipping undecodable message offset %s: %s", msg.offset(), exc)
            consumer.commit(message=msg, asynchronous=False)
            continue

        yield value
        consumer.commit(message=msg, asynchronous=False)
