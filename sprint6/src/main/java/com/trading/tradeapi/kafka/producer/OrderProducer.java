package com.trading.tradeapi.kafka.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.kafka.event.KafkaEventEnvelope;
import com.trading.tradeapi.kafka.event.OrderPlacedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class OrderProducer {
    private static final Logger logger = LoggerFactory.getLogger(OrderProducer.class);
    private static final String ORDERS_TOPIC = "orders";
    private static final String SOURCE = "trade-api";
    private static final Integer SCHEMA_VERSION = 1;
    private static final String EVENT_TYPE = "ORDER_PLACED";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OrderProducer(KafkaTemplate<String, String> kafkaTemplate,
                         ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Publishes an ORDER_PLACED event to the orders topic
     * @param orderId Numeric order ID from database (BIGSERIAL)
     * @param accountId Account ID (used as partition key)
     * @param symbol Trading symbol
     * @param side BUY or SELL
     * @param quantity Order quantity
     * @param price Order price
     * @param idempotencyKey Client idempotency key
     * @param createdOn Order creation timestamp
     */
    public void publishOrderPlaced(Long orderId, Long accountId, String symbol, String side,
                                   Long quantity, java.math.BigDecimal price, String idempotencyKey,
                                   Instant createdOn) {
        try {
            // Create the event payload
            OrderPlacedPayload payload = new OrderPlacedPayload(
                    orderId,
                    accountId,
                    symbol,
                    side,
                    quantity,
                    price,
                    idempotencyKey,
                    formatInstantToRFC3339(createdOn)
            );

            // Create the event envelope
            String eventId = UUID.randomUUID().toString();
            String eventTime = formatInstantToRFC3339(Instant.now());
            KafkaEventEnvelope<OrderPlacedPayload> event = new KafkaEventEnvelope<>(
                    eventId,
                    EVENT_TYPE,
                    eventTime,
                    SOURCE,
                    SCHEMA_VERSION,
                    payload
            );

            // Serialize to JSON
            String jsonMessage = objectMapper.writeValueAsString(event);

            // Send to Kafka with accountId as the message key (partition key)
            String messageKey = String.valueOf(accountId);
            kafkaTemplate.send(ORDERS_TOPIC, messageKey, jsonMessage);
            logger.info("Published ORDER_PLACED event: orderId={}, accountId={}, eventId={}", 
                        orderId, accountId, eventId);
        } catch (Exception e) {
            logger.error("Failed to publish order placed event for orderId: {}", orderId, e);
            throw new RuntimeException("Failed to publish order to Kafka", e);
        }
    }

    /**
     * Formats an Instant to RFC 3339 format (e.g., 2026-09-28T09:14:22Z)
     */
    private String formatInstantToRFC3339(Instant instant) {
        OffsetDateTime odt = instant.atOffset(ZoneOffset.UTC);
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(odt).replace("+00:00", "Z");
    }
}
