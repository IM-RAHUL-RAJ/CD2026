package com.trading.domain;

import com.trading.domain.dto.HoldingsUpdateRequest;
import com.trading.domain.dto.PlaceOrderRequest;
import com.trading.domain.exception.InsufficientHoldingsException;
import com.trading.domain.exception.OrderValidationException;
import com.trading.domain.holdings.Holdings;
import com.trading.domain.order.Order;
import com.trading.domain.order.OrderSide;
import com.trading.domain.order.OrderStatus;
import com.trading.domain.position.Position;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class HoldingsAndOrderTest {

    @Test
    void calculatesAveragePriceOnBuy() {
        Holdings holdings = new Holdings(1, 1, 10, new BigDecimal("20.00"));
        assertEquals(1L, holdings.getAccountId());
        assertEquals(1L, holdings.getInstrumentId());
        assertTrue(holdings.canSell(10));
        assertFalse(holdings.canSell(0));
        holdings.buy(5, new BigDecimal("10.00"));

        assertEquals(15, holdings.getQuantity());
        assertEquals(new BigDecimal("16.67"), holdings.getAveragePrice());
        holdings.sell(5);
        assertEquals(10, holdings.getQuantity());
        assertEquals(new BigDecimal("16.67"), holdings.getAveragePrice());
    }

    @Test
    void rejectsInvalidHoldingsChanges() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Holdings(0, 1, 0, BigDecimal.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Holdings(1, 1, -1, BigDecimal.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Holdings(1, 1, 0, new BigDecimal("0.001")))
        );
        Holdings holdings = new Holdings(new HoldingsUpdateRequest(1, 1, 2, new BigDecimal("10.00")));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> holdings.buy(0, BigDecimal.ONE)),
                () -> assertThrows(IllegalArgumentException.class, () -> holdings.buy(1, BigDecimal.ZERO)),
                () -> assertThrows(InsufficientHoldingsException.class, () -> holdings.sell(3))
        );
        assertEquals(2, holdings.getQuantity());
        assertEquals(new BigDecimal("10.00"), holdings.getAveragePrice());
    }

    @Test
    void calculatesOrderAmountAndUpdatesStatus() {
        Order order = order();
        assertAll(
                () -> assertEquals(1L, order.getOrderId()),
                () -> assertEquals(1L, order.getAccountId()),
                () -> assertEquals(1L, order.getInstrumentId()),
                () -> assertEquals(3L, order.getQuantity()),
                () -> assertEquals(OrderSide.BUY, order.getOrderSide()),
                () -> assertEquals(new BigDecimal("10.00"), order.getPrice()),
                () -> assertEquals("ORDER-KEY", order.getIdempotencyKey()),
                () -> assertNotNull(order.getCreatedAt())
        );
        assertEquals(OrderStatus.NEW, order.getOrderStatus());
        assertEquals(new BigDecimal("30.00"), order.getOrderAmount());
        order.markFilled();
        assertEquals(OrderStatus.FILLED, order.getOrderStatus());
        assertThrows(IllegalStateException.class, order::cancel);

        Order rejected = order();
        rejected.markRejected();
        assertEquals(OrderStatus.REJECTED, rejected.getOrderStatus());
        Order cancelled = order();
        cancelled.cancel();
        assertEquals(OrderStatus.CANCELLED, cancelled.getOrderStatus());
    }

    @Test
    void rejectsInvalidOrderValues() {
        assertAll(
                () -> assertThrows(OrderValidationException.class,
                        () -> new Order(0, 1, 1, 1, OrderSide.BUY, BigDecimal.ONE, "12345678")),
                () -> assertThrows(OrderValidationException.class,
                        () -> new Order(1, 1, 1, 0, OrderSide.BUY, BigDecimal.ONE, "12345678")),
                () -> assertThrows(OrderValidationException.class,
                        () -> new Order(1, 1, 1, 1, null, BigDecimal.ONE, "12345678")),
                () -> assertThrows(OrderValidationException.class,
                        () -> new Order(1, 1, 1, 1, OrderSide.BUY, new BigDecimal("1.001"), "12345678")),
                () -> assertThrows(OrderValidationException.class,
                        () -> new Order(1, 1, 1, 1, OrderSide.BUY, BigDecimal.ONE, "short"))
        );
    }

    @Test
    void createsAndValidatesPosition() {
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
        Position position = new Position(1, timestamp);
        assertEquals(1, position.getOrderId());
        assertEquals(timestamp, position.getTimestamp());
        assertThrows(IllegalArgumentException.class, () -> new Position(0, timestamp));
        assertThrows(IllegalArgumentException.class, () -> new Position(1, null));
        assertNotNull(new Position(2).getTimestamp());
    }

    @Test
    void createsOrderFromRequest() {
        PlaceOrderRequest request = new PlaceOrderRequest(4L, "AAPL", OrderSide.SELL,
                2L, new BigDecimal("11.25"), "REQUEST-KEY");

        Order order = new Order(3L, 9L, request);

        assertAll(
                () -> assertEquals(3L, order.getOrderId()),
                () -> assertEquals(4L, order.getAccountId()),
                () -> assertEquals(9L, order.getInstrumentId()),
                () -> assertEquals(2L, order.getQuantity()),
                () -> assertEquals(OrderSide.SELL, order.getOrderSide()),
                () -> assertEquals(new BigDecimal("11.25"), order.getPrice()),
                () -> assertEquals("REQUEST-KEY", order.getIdempotencyKey())
        );
    }

    private Order order() {
        return new Order(1, 1, 1, 3, OrderSide.BUY, new BigDecimal("10.00"), "ORDER-KEY");
    }
}
