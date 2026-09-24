package com.trading.tradeapi.kafka.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
public class OrderProducerUnitTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;
    private OrderProducer orderProducer;

    @BeforeEach
    public void setup() {
        objectMapper = new ObjectMapper();
        orderProducer = new OrderProducer(kafkaTemplate, objectMapper);
    }

    @Test
    @DisplayName("publishOrderPlaced: Successfully serializes and sends message to Kafka")
    public void publishOrderPlacedSuccess() {
        orderProducer.publishOrderPlaced(
                1L,
                101L,
                "AAPL",
                "BUY",
                100L,
                new BigDecimal("233.00"),
                "key-123",
                Instant.now()
        );

        verify(kafkaTemplate).send(eq("orders"), eq("101"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("publishOrderPlaced: Kafka failure rethrows RuntimeException")
    public void publishOrderPlacedFailure() {
        doThrow(new RuntimeException("Kafka error"))
                .when(kafkaTemplate).send(eq("orders"), eq("101"), org.mockito.ArgumentMatchers.anyString());

        assertThrows(RuntimeException.class, () ->
                orderProducer.publishOrderPlaced(
                        1L,
                        101L,
                        "AAPL",
                        "BUY",
                        100L,
                        new BigDecimal("233.00"),
                        "key-123",
                        Instant.now()
                )
        );
    }
}
