package com.trading.domain;

import com.trading.domain.account.Account;
import com.trading.domain.account.AccountStatus;
import com.trading.domain.dto.HoldingsUpdateRequest;
import com.trading.domain.dto.OrderExecutionRequest;
import com.trading.domain.dto.PlaceOrderRequest;
import com.trading.domain.holdings.Holdings;
import com.trading.domain.instrument.Instrument;
import com.trading.domain.order.Order;
import com.trading.domain.order.OrderSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DtoRecordsTest {

    @Test
    void storesRequestValues() {
        PlaceOrderRequest placeOrder = new PlaceOrderRequest(1L, "AAPL", OrderSide.BUY,
                2L, new BigDecimal("10.00"), "PLACE-KEY");
        HoldingsUpdateRequest holdingsUpdate = new HoldingsUpdateRequest(1L, 2L, 3L,
                new BigDecimal("4.50"));
        Order order = new Order(1L, 1L, 2L, 2L, OrderSide.BUY,
                new BigDecimal("10.00"), "EXEC-KEY");
        Account account = new Account(1L, "ACC-1", BigDecimal.TEN, AccountStatus.ACTIVE);
        Instrument instrument = new Instrument(2L, "AAPL", "STOCK", "USD", true,
                new BigDecimal("10.00"), 5L);
        Holdings holdings = new Holdings(1L, 2L, 0L, BigDecimal.ZERO);
        OrderExecutionRequest execution = new OrderExecutionRequest(order, account, instrument, holdings);

        assertAll(
                () -> assertEquals(1L, placeOrder.accountId()),
                () -> assertEquals("AAPL", placeOrder.symbol()),
                () -> assertEquals(OrderSide.BUY, placeOrder.side()),
                () -> assertEquals(2L, placeOrder.quantity()),
                () -> assertEquals(new BigDecimal("10.00"), placeOrder.price()),
                () -> assertEquals("PLACE-KEY", placeOrder.idempotencyKey()),
                () -> assertEquals(1L, holdingsUpdate.accountId()),
                () -> assertEquals(2L, holdingsUpdate.instrumentId()),
                () -> assertEquals(3L, holdingsUpdate.quantity()),
                () -> assertEquals(new BigDecimal("4.50"), holdingsUpdate.averagePrice()),
                () -> assertEquals(order, execution.order()),
                () -> assertEquals(account, execution.account()),
                () -> assertEquals(instrument, execution.instrument()),
                () -> assertEquals(holdings, execution.holdings())
        );
    }
}
