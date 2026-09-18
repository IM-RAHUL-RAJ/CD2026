package com.trading.tradeapi.entity;

import com.trading.tradeapi.enums.OrderSide;
import com.trading.tradeapi.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

public class OrderRecord {
    private Long orderId;
    private Long accountId;
    private String ticker;
    private OrderSide side;
    private BigDecimal quantity;
    private BigDecimal price;
    private OrderStatus status;
    private Instant receivedAt;
    private String idempotencyKey;

    public OrderRecord() {}

    public OrderRecord(Long orderId, Long accountId, String ticker, OrderSide side,
                       BigDecimal quantity, BigDecimal price, OrderStatus status,
                       Instant receivedAt, String idempotencyKey) {
        this.orderId = orderId;
        this.accountId = accountId;
        this.ticker = ticker;
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

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

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
