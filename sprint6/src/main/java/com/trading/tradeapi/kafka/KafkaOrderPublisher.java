package com.trading.tradeapi.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class KafkaOrderPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaOrderPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topicName;

    public KafkaOrderPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper,
                               @Value("${trading.kafka.topics.orders:orders}") String topicName) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topicName = topicName;
    }

    public void publishOrderPlacedEvent(Long orderId,
                                        Long accountId,
                                        String symbol,
                                        String side,
                                        Long quantity,
                                        BigDecimal price,
                                        String idempotencyKey,
                                        Instant createdOn) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderId", String.valueOf(orderId));
            payload.put("accountId", accountId);
            payload.put("symbol", symbol);
            payload.put("side", side);
            payload.put("quantity", quantity);
            payload.put("price", price);
            payload.put("idempotencyKey", idempotencyKey);
            payload.put("createdOn", createdOn.toString());

            Map<String, Object> envelope = new HashMap<>();
            envelope.put("eventId", UUID.randomUUID().toString());
            envelope.put("eventType", "ORDER_PLACED");
            envelope.put("eventTime", Instant.now().toString());
            envelope.put("source", "trade-api");
            envelope.put("schemaVersion", 1);
            envelope.put("payload", payload);

            String jsonString = objectMapper.writeValueAsString(envelope);
            String key = String.valueOf(accountId);

            kafkaTemplate.send(topicName, key, jsonString).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish ORDER_PLACED event for orderId={} accountId={}", orderId, accountId, ex);
                } else {
                    log.info("Successfully published ORDER_PLACED event for orderId={} to topic={} partition={}",
                            orderId, topicName, result.getRecordMetadata().partition());
                }
            });
        } catch (Exception e) {
            log.error("Error serializing ORDER_PLACED event for orderId={}", orderId, e);
        }
    }
}
