package com.trading.domain.position;

import java.time.Instant;

public class Position {
    private final long orderId;
    private final Instant timestamp;

    public Position(long orderId) {
        this(orderId, Instant.now());
    }

    public Position(long orderId, Instant timestamp) {
        if (orderId < 1 || timestamp == null) {
            throw new IllegalArgumentException("Invalid position");
        }

        this.orderId = orderId;
        this.timestamp = timestamp;
    }

    public long getOrderId() {
        return orderId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
