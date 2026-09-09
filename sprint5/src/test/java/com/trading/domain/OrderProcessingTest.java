package com.trading.domain;

import com.trading.domain.account.Account;
import com.trading.domain.account.AccountStatus;
import com.trading.domain.dto.OrderExecutionRequest;
import com.trading.domain.exception.DuplicateOrderException;
import com.trading.domain.exception.InsufficientFundsException;
import com.trading.domain.exception.InsufficientHoldingsException;
import com.trading.domain.exception.InsufficientInstrumentQuantityException;
import com.trading.domain.exception.InstrumentNotFoundException;
import com.trading.domain.execution.BuyOrderExecutor;
import com.trading.domain.execution.OrderExecutorFactory;
import com.trading.domain.execution.SellOrderExecutor;
import com.trading.domain.holdings.Holdings;
import com.trading.domain.instrument.Instrument;
import com.trading.domain.order.Order;
import com.trading.domain.order.OrderSide;
import com.trading.domain.order.OrderValidationService;
import com.trading.domain.order.OrderValidatorFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OrderProcessingTest {

    @Test
    void selectsOrderStrategies() {
        assertInstanceOf(BuyOrderExecutor.class, OrderExecutorFactory.forSide(OrderSide.BUY));
        assertInstanceOf(SellOrderExecutor.class, OrderExecutorFactory.forSide(OrderSide.SELL));
        assertSame(OrderExecutorFactory.forSide(OrderSide.BUY), OrderExecutorFactory.forSide(OrderSide.BUY));
        assertThrows(NullPointerException.class, () -> OrderExecutorFactory.forSide(null));
        assertThrows(NullPointerException.class, () -> OrderValidatorFactory.forSide(null));
    }

    @Test
    void validatesOrderRules() {
        Instrument instrument = instrument(true, 2);
        Set<String> acceptedKeys = new HashSet<>();
        OrderValidationService service = new OrderValidationService(Map.of(1L, instrument), acceptedKeys);
        Order validBuy = order(OrderSide.BUY, 1, "VALID-KEY");

        service.isOrderValid(validBuy);
        service.acceptIdempotencyKey(validBuy);
        assertTrue(acceptedKeys.contains("VALID-KEY"));
        assertThrows(DuplicateOrderException.class, () -> service.isOrderValid(validBuy));
        assertThrows(InstrumentNotFoundException.class,
                () -> service.isInstrumentValid(2));
        assertThrows(InsufficientInstrumentQuantityException.class,
                () -> new OrderValidationService(Map.of(1L, instrument(true, 0)), new HashSet<>())
                        .isOrderValid(order(OrderSide.BUY, 1, "NO-STOCK")));
        assertThrows(InstrumentNotFoundException.class,
                () -> new OrderValidationService(Map.of(1L, instrument(false, 2)), new HashSet<>())
                        .isOrderValid(order(OrderSide.SELL, 1, "NOT-TRADEABLE")));
        assertThrows(NullPointerException.class, () -> new OrderValidationService(null, new HashSet<>()));
        assertThrows(NullPointerException.class, () -> new OrderValidationService(Map.of(), null));
    }

    @Test
    void validatesBuyAndSellOrders() {
        Order buy = order(OrderSide.BUY, 1, "BUY-VALIDATOR");
        assertDoesNotThrow(() -> OrderValidatorFactory.forSide(OrderSide.BUY)
                .validate(buy, instrument(true, 1)));
        assertThrows(InsufficientInstrumentQuantityException.class,
                () -> OrderValidatorFactory.forSide(OrderSide.BUY)
                        .validate(buy, instrument(true, 0)));

        // SELL validation is intentionally a no-op; holdings are checked by SellOrderExecutor.
        assertDoesNotThrow(() -> OrderValidatorFactory.forSide(OrderSide.SELL)
                .validate(order(OrderSide.SELL, 1, "SELL-VALIDATOR"), instrument(true, 0)));
    }

    @Test
    void executesBuyOrder() {
        Account account = account("100.00");
        Instrument instrument = instrument(true, 3);
        Holdings holdings = new Holdings(1, 1, 0, BigDecimal.ZERO);
        Order order = order(OrderSide.BUY, 2, "BUY-EXECUTOR");
        new BuyOrderExecutor().execute(new OrderExecutionRequest(order, account, instrument, holdings));

        assertAll(
                () -> assertEquals(new BigDecimal("80.00"), account.getAccountBalance()),
                () -> assertEquals(1, instrument.getQuantityAvailable()),
                () -> assertEquals(2, holdings.getQuantity()),
                () -> assertEquals(new BigDecimal("10.00"), holdings.getAveragePrice())
        );

        Account poorAccount = account("5.00");
        Instrument unchangedInstrument = instrument(true, 1);
        Holdings unchangedHoldings = new Holdings(1, 1, 0, BigDecimal.ZERO);
        assertThrows(InsufficientFundsException.class, () -> new BuyOrderExecutor().execute(
                new OrderExecutionRequest(order(OrderSide.BUY, 1, "POOR-BUY"), poorAccount,
                        unchangedInstrument, unchangedHoldings)));
        assertEquals(new BigDecimal("5.00"), poorAccount.getAccountBalance());
        assertEquals(1, unchangedInstrument.getQuantityAvailable());
        assertEquals(0, unchangedHoldings.getQuantity());

        Account fundedAccount = account("100.00");
        Instrument unavailableInstrument = instrument(true, 0);
        Holdings emptyHoldings = new Holdings(1, 1, 0, BigDecimal.ZERO);
        assertThrows(InsufficientInstrumentQuantityException.class, () -> new BuyOrderExecutor().execute(
                new OrderExecutionRequest(order(OrderSide.BUY, 1, "NO-INVENTORY"), fundedAccount,
                        unavailableInstrument, emptyHoldings)));
        assertEquals(new BigDecimal("100.00"), fundedAccount.getAccountBalance());
        assertEquals(0, unavailableInstrument.getQuantityAvailable());
        assertEquals(0, emptyHoldings.getQuantity());
    }

    @Test
    void executesSellOrder() {
        Account account = account("10.00");
        Holdings holdings = new Holdings(1, 1, 3, new BigDecimal("8.00"));
        Order sell = order(OrderSide.SELL, 2, "SELL-EXECUTOR");
        new SellOrderExecutor().execute(new OrderExecutionRequest(sell, account, instrument(true, 5), holdings));
        assertEquals(new BigDecimal("30.00"), account.getAccountBalance());
        assertEquals(1, holdings.getQuantity());

        Account unchangedAccount = account("10.00");
        assertThrows(InsufficientHoldingsException.class, () -> new SellOrderExecutor().execute(
                new OrderExecutionRequest(order(OrderSide.SELL, 2, "NO-HOLDINGS"), unchangedAccount,
                        instrument(true, 5), null)));
        assertEquals(new BigDecimal("10.00"), unchangedAccount.getAccountBalance());
    }

    @Test
    void checksOrderAffordability() {
        BuyOrderExecutor buyExecutor = new BuyOrderExecutor();
        assertDoesNotThrow(() -> buyExecutor.ensureAffordable(new OrderExecutionRequest(
                order(OrderSide.BUY, 1, "FUNDED-BUY"), account("10.00"), instrument(true, 0),
                new Holdings(1, 1, 0, BigDecimal.ZERO))));
        assertThrows(InsufficientFundsException.class, () -> buyExecutor.ensureAffordable(
                new OrderExecutionRequest(order(OrderSide.BUY, 2, "UNFUNDED-BUY"), account("10.00"),
                        instrument(true, 2), new Holdings(1, 1, 0, BigDecimal.ZERO))));

        SellOrderExecutor sellExecutor = new SellOrderExecutor();
        assertDoesNotThrow(() -> sellExecutor.ensureAffordable(new OrderExecutionRequest(
                order(OrderSide.SELL, 1, "HELD-SELL"), account("10.00"), instrument(true, 0),
                new Holdings(1, 1, 1, BigDecimal.TEN))));
        assertThrows(InsufficientHoldingsException.class, () -> sellExecutor.ensureAffordable(
                new OrderExecutionRequest(order(OrderSide.SELL, 2, "UNHELD-SELL"), account("10.00"),
                        instrument(true, 0), new Holdings(1, 1, 1, BigDecimal.TEN))));
    }

    @Test
    void retriesRejectedOrder() {
        Account account = account("5.00");
        Instrument instrument = instrument(true, 2);
        TradeSystem system = new TradeSystem(new HashMap<>(Map.of(1L, account)),
                new HashMap<>(Map.of(1L, instrument)));
        var request = new com.trading.domain.dto.PlaceOrderRequest(1L, "AAPL", OrderSide.BUY,
                1L, new BigDecimal("10.00"), "RETRY-KEY");

        assertThrows(InsufficientFundsException.class, () -> system.placeOrder(1L, request));
        assertTrue(system.getHoldings().isEmpty());
        assertTrue(system.getPositions().isEmpty());
        assertEquals(new BigDecimal("5.00"), account.getAccountBalance());
        assertEquals(2, instrument.getQuantityAvailable());

        account.credit(new BigDecimal("10.00"));
        assertDoesNotThrow(() -> system.placeOrder(1L, request));
        assertEquals(1, system.getPositions().size());
    }

    private Account account(String balance) {
        return new Account(1L, "ACC-1", new BigDecimal(balance), AccountStatus.ACTIVE);
    }

    private Instrument instrument(boolean tradeable, long quantity) {
        return new Instrument(1L, "AAPL", "STOCK", "USD", tradeable,
                new BigDecimal("10.00"), quantity);
    }

    private Order order(OrderSide side, long quantity, String key) {
        return new Order(1L, 1L, 1L, quantity, side, new BigDecimal("10.00"), key);
    }
}
