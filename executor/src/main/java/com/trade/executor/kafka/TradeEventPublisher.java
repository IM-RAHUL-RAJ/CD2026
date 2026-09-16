package com.trade.executor.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes TRADE_EXECUTED and TRADE_REJECTED events to the "trade-events" topic.
 * Key is accountId (String) for partition affinity — all events for the same account
 * land on the same partition, preserving per-account ordering.
 */
@Component
public class TradeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TradeEventPublisher.class);

    @Value("${trade.events.topic:trade-events}")
    private String tradeEventsTopic;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public TradeEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishExecuted(Long orderId, Long accountId, String symbol,
                                String side, Long quantity, BigDecimal executedPrice) {
        publish("TRADE_EXECUTED", accountId, Map.of(
                "orderId", orderId,
                "accountId", accountId,
                "symbol", symbol,
                "side", side,
                "quantity", quantity,
                "executedPrice", executedPrice,
                "status", "FILLED"
        ));
    }

    public void publishRejected(Long orderId, Long accountId, String symbol, String reason) {
        publish("TRADE_REJECTED", accountId, Map.of(
                "orderId", orderId,
                "accountId", accountId,
                "symbol", symbol,
                "reason", reason,
                "status", "REJECTED"
        ));
    }

    private void publish(String eventType, Long accountId, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "eventId", UUID.randomUUID().toString(),
                    "eventType", eventType,
                    "schemaVersion", 1,
                    "source", "trade-executor",
                    "occurredAt", Instant.now().toString(),
                    "payload", payload
            ));
            kafkaTemplate.send(tradeEventsTopic, accountId.toString(), json);
        } catch (Exception e) {
            log.error("Failed to publish {} event for account {}", eventType, accountId, e);
        }
    }
}
