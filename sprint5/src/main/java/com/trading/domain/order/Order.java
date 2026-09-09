package com.trading.domain.order;

import com.trading.domain.dto.PlaceOrderRequest;
import com.trading.domain.exception.OrderValidationException;

import java.math.BigDecimal;
import java.time.Instant;

public class Order {
    private final long orderId;
    private final long accountId;
    private final long instrumentId;
    private final long quantity;
    private final OrderSide orderSide;
    private final BigDecimal price;
    private final String idempotencyKey;
    private final Instant createdAt;
    private OrderStatus orderStatus;

    public Order(long orderId, long instrumentId, PlaceOrderRequest request) {
        this(orderId, request.accountId(), instrumentId, request.quantity(),
                request.side(), request.price(), request.idempotencyKey());
    }

    public Order(long orderId, long accountId, long instrumentId,
                 long quantity, OrderSide orderSide,
                 BigDecimal price, String idempotencyKey) {
        if (orderId < 1 || accountId < 1 || instrumentId < 1) {
            throw new OrderValidationException("IDs must be positive");
        }
        if (quantity <= 0) {
            throw new OrderValidationException("Quantity must be greater than zero");
        }
        if (orderSide == null) {
            throw new OrderValidationException("Order side is required");
        }
        if (price == null || price.scale() > 2
                || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new OrderValidationException(
                    "Price must be positive with at most 2 decimal places");
        }
        if (idempotencyKey == null
                || idempotencyKey.length() < 8
                || idempotencyKey.length() > 100) {
            throw new OrderValidationException(
                    "Idempotency key must be 8 to 100 characters");
        }

        this.orderId = orderId;
        this.accountId = accountId;
        this.instrumentId = instrumentId;
        this.quantity = quantity;
        this.orderSide = orderSide;
        this.price = price.setScale(2);
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Instant.now();
        this.orderStatus = OrderStatus.NEW;
    }

    public long getOrderId() {
        return orderId;
    }

    public long getAccountId() {
        return accountId;
    }

    public long getInstrumentId() {
        return instrumentId;
    }

    public long getQuantity() {
        return quantity;
    }

    public OrderSide getOrderSide() {
        return orderSide;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

    public BigDecimal getOrderAmount() {
        return price.multiply(BigDecimal.valueOf(quantity)).setScale(2);
    }

    public void markFilled() {
        transitionTo(OrderStatus.FILLED);
    }

    public void markRejected() {
        transitionTo(OrderStatus.REJECTED);
    }

    public void cancel() {
        transitionTo(OrderStatus.CANCELLED);
    }

    private void transitionTo(OrderStatus target) {
        if (orderStatus != OrderStatus.NEW) {
            throw new IllegalStateException(
                    "Terminal order status cannot change");
        }

        orderStatus = target;
    }
}
