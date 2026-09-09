package com.trading.domain;

import com.trading.domain.account.Account;
import com.trading.domain.dto.OrderExecutionRequest;
import com.trading.domain.exception.InsufficientFundsException;
import com.trading.domain.exception.InsufficientHoldingsException;
import com.trading.domain.execution.BuyOrderExecutor;
import com.trading.domain.execution.SellOrderExecutor;
import com.trading.domain.holdings.Holdings;
import com.trading.domain.instrument.Instrument;
import com.trading.domain.order.Order;
import com.trading.domain.order.OrderSide;
import com.trading.domain.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderExecutorMockitoTest {

    @Mock
    private Account account;

    @Mock
    private Instrument instrument;

    @Mock
    private Holdings holdings;

    @Test
    void executesBuyWithMocks() {
        Order order = order(OrderSide.BUY, 2, "MOCK-BUY-OK");
        OrderExecutionRequest request = new OrderExecutionRequest(order, account, instrument, holdings);
        when(account.canAfford(new BigDecimal("20.00"))).thenReturn(true);
        when(instrument.hasQuantity(2)).thenReturn(true);

        new BuyOrderExecutor().execute(request);

        assertEquals(OrderStatus.FILLED, order.getOrderStatus());
        verify(account).canAfford(new BigDecimal("20.00"));
        verify(account).debit(new BigDecimal("20.00"));
        verify(instrument).hasQuantity(2);
        verify(instrument).reserveQuantity(2);
        verify(holdings).buy(2, new BigDecimal("10.00"));
    }

    @Test
    void rejectsBuyWhenFundsAreLow() {
        Order order = order(OrderSide.BUY, 1, "MOCK-BUY-NO-FUNDS");
        when(account.canAfford(new BigDecimal("10.00"))).thenReturn(false);

        assertThrows(InsufficientFundsException.class,
                () -> new BuyOrderExecutor().execute(
                        new OrderExecutionRequest(order, account, instrument, holdings)));

        assertEquals(OrderStatus.NEW, order.getOrderStatus());
        verify(account).canAfford(new BigDecimal("10.00"));
        verify(account, never()).debit(any());
        verifyNoInteractions(instrument, holdings);
    }

    @Test
    void executesSellWithMocks() {
        Order order = order(OrderSide.SELL, 2, "MOCK-SELL-OK");
        when(holdings.canSell(2)).thenReturn(true);

        new SellOrderExecutor().execute(new OrderExecutionRequest(order, account, instrument, holdings));

        assertEquals(OrderStatus.FILLED, order.getOrderStatus());
        verify(holdings).canSell(2);
        verify(holdings).sell(2);
        verify(account).credit(new BigDecimal("20.00"));
        verifyNoInteractions(instrument);
    }

    @Test
    void rejectsSellWhenHoldingsAreLow() {
        Order order = order(OrderSide.SELL, 2, "MOCK-SELL-NO-HOLDINGS");
        when(holdings.canSell(2)).thenReturn(false);

        assertThrows(InsufficientHoldingsException.class,
                () -> new SellOrderExecutor().execute(
                        new OrderExecutionRequest(order, account, instrument, holdings)));

        assertEquals(OrderStatus.NEW, order.getOrderStatus());
        verify(holdings).canSell(2);
        verify(holdings, never()).sell(anyLong());
        verify(account, never()).credit(any());
        verifyNoInteractions(instrument);
    }

    private Order order(OrderSide side, long quantity, String idempotencyKey) {
        return new Order(1L, 1L, 1L, quantity, side, new BigDecimal("10.00"), idempotencyKey);
    }
}
