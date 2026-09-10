package com.trading.tradeapi.entity;

import com.trading.domain.order.OrderSide;
import com.trading.domain.order.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

public class OrderRecord {
    private Long orderId;
    private Long accountId;
    private Long instrumentId;
    private OrderSide side;
    private BigDecimal quantity;
    private BigDecimal price;
    private OrderStatus status;
    private Instant receivedAt;
    private String idempotencyKey;

    public OrderRecord() {}

    public OrderRecord(Long orderId, Long accountId, Long instrumentId, OrderSide side,
                       BigDecimal quantity, BigDecimal price, OrderStatus status,
                       Instant receivedAt, String idempotencyKey) {
        this.orderId = orderId;
        this.accountId = accountId;
        this.instrumentId = instrumentId;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.status = status;
        this.receivedAt = receivedAt;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public Long getInstrumentId() { return instrumentId; }
    public void setInstrumentId(Long instrumentId) { this.instrumentId = instrumentId; }

    public OrderSide getSide() { return side; }
    public void setSide(OrderSide side) { this.side = side; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
