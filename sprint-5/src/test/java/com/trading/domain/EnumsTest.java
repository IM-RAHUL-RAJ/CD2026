package com.trading.domain;

import com.trading.domain.account.AccountStatus;
import com.trading.domain.order.OrderSide;
import com.trading.domain.order.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnumsTest {

    @Test
    void accountStatusContainsExpectedValues() {

        assertArrayEquals(
                new AccountStatus[]{
                        AccountStatus.ACTIVE,
                        AccountStatus.SUSPENDED,
                        AccountStatus.CLOSED
                },
                AccountStatus.values()
        );
    }

    @Test
    void orderSideContainsExpectedValues() {

        assertArrayEquals(
                new OrderSide[]{
                        OrderSide.BUY,
                        OrderSide.SELL
                },
                OrderSide.values()
        );
    }

    @Test
    void orderStatusContainsExpectedValues() {

        assertArrayEquals(
                new OrderStatus[]{
                        OrderStatus.NEW,
                        OrderStatus.FILLED,
                        OrderStatus.REJECTED,
                        OrderStatus.CANCELLED
                },
                OrderStatus.values()
        );
    }

    @Test
    void accountStatusCanBeResolvedByName() {

        assertEquals(
                AccountStatus.ACTIVE,
                AccountStatus.valueOf("ACTIVE")
        );

        assertEquals(
                AccountStatus.SUSPENDED,
                AccountStatus.valueOf("SUSPENDED")
        );

        assertEquals(
                AccountStatus.CLOSED,
                AccountStatus.valueOf("CLOSED")
        );
    }

    @Test
    void orderSideCanBeResolvedByName() {

        assertEquals(OrderSide.BUY, OrderSide.valueOf("BUY"));
        assertEquals(OrderSide.SELL, OrderSide.valueOf("SELL"));
    }

    @Test
    void orderStatusCanBeResolvedByName() {

        assertEquals(OrderStatus.NEW, OrderStatus.valueOf("NEW"));
        assertEquals(OrderStatus.FILLED, OrderStatus.valueOf("FILLED"));
        assertEquals(OrderStatus.REJECTED, OrderStatus.valueOf("REJECTED"));
        assertEquals(OrderStatus.CANCELLED, OrderStatus.valueOf("CANCELLED"));
    }
}