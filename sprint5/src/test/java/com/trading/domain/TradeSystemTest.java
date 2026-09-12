package com.trading.domain;

import com.trading.domain.account.Account;
import com.trading.domain.account.AccountStatus;
import com.trading.domain.dto.PlaceOrderRequest;
import com.trading.domain.exception.*;
import com.trading.domain.holdings.Holdings;
import com.trading.domain.instrument.Instrument;
import com.trading.domain.order.OrderSide;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TradeSystemTest {

    private Account createAccount(
            BigDecimal balance,
            AccountStatus status
    ) {
        return new Account(
                1L,
                "ACC001",
                balance,
                status
        );
    }

    private Instrument createInstrument(
            boolean tradeable,
            long quantity
    ) {
        return new Instrument(
                1L,
                "AAPL",
                "STOCK",
                "USD",
                tradeable,
                new BigDecimal("100.00"),
                quantity
        );
    }

    private TradeSystem createTradeSystem(
            Account account,
            Instrument instrument
    ) {
        Map<Long, Account> accounts = new HashMap<>();

        accounts.put(
                account.getAccountId(),
                account
        );

        Map<Long, Instrument> instruments = new HashMap<>();

        instruments.put(
                instrument.getInstrumentId(),
                instrument
        );

        return new TradeSystem(
                accounts,
                instruments
        );
    }

    private PlaceOrderRequest buyRequest(
            long quantity,
            String idempotencyKey
    ) {
        return new PlaceOrderRequest(
                1L,
                "AAPL",
                OrderSide.BUY,
                quantity,
                new BigDecimal("100.00"),
                idempotencyKey
        );
    }

    private PlaceOrderRequest sellRequest(
            long quantity,
            String idempotencyKey
    ) {
        return new PlaceOrderRequest(
                1L,
                "AAPL",
                OrderSide.SELL,
                quantity,
                new BigDecimal("100.00"),
                idempotencyKey
        );
    }

    @Test
    @DisplayName("Buy succeeds and updates cash and position")
    void buySucceedsAndUpdatesCashAndPosition() {

        Account account =
                createAccount(
                        new BigDecimal("1000.00"),
                        AccountStatus.ACTIVE
                );

        Instrument instrument =
                createInstrument(
                        true,
                        100
                );

        TradeSystem tradeSystem =
                createTradeSystem(
                        account,
                        instrument
                );

        tradeSystem.placeOrder(
                1L,
                buyRequest(
                        5,
                        "BUY-ORDER-1"
                )
        );

        assertEquals(
                new BigDecimal("500.00"),
                account.getAccountBalance()
        );

        Holdings holdings =
                tradeSystem
                        .getHoldings()
                        .get("1:1");

        assertNotNull(holdings);

        assertEquals(
                5,
                holdings.getQuantity()
        );

        assertTrue(
                tradeSystem.getPositions().containsKey(1L)
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> tradeSystem.getHoldings().clear()
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> tradeSystem.getPositions().clear()
        );
    }

    @Test
    @DisplayName("Buy rejected on insufficient funds")
    void buyRejectedOnInsufficientFunds() {

        Account account =
                createAccount(
                        new BigDecimal("50.00"),
                        AccountStatus.ACTIVE
                );

        Instrument instrument =
                createInstrument(
                        true,
                        100
                );

        TradeSystem tradeSystem =
                createTradeSystem(
                        account,
                        instrument
                );

        assertThrows(
                InsufficientFundsException.class,
                () ->
                        tradeSystem.placeOrder(
                                1L,
                                buyRequest(
                                        1,
                                        "FUNDS-FAIL"
                                )
                        )
        );
    }

    @Test
    @DisplayName("Sell rejected on insufficient holdings")
    void sellRejectedOnInsufficientHoldings() {

        Account account =
                createAccount(
                        new BigDecimal("1000.00"),
                        AccountStatus.ACTIVE
                );

        Instrument instrument =
                createInstrument(
                        true,
                        100
                );

        TradeSystem tradeSystem =
                createTradeSystem(
                        account,
                        instrument
                );

        assertThrows(
                InsufficientHoldingsException.class,
                () ->
                        tradeSystem.placeOrder(
                                1L,
                                sellRequest(
                                        10,
                                        "SELL-FAIL"
                                )
                        )
        );
    }

    @Test
    @DisplayName("Order rejected on an account that does not exist")
    void orderRejectedWhenAccountDoesNotExist() {

        Map<Long, Account> accounts =
                new HashMap<>();

        Map<Long, Instrument> instruments =
                new HashMap<>();

        instruments.put(
                1L,
                createInstrument(
                        true,
                        100
                )
        );

        TradeSystem tradeSystem =
                new TradeSystem(
                        accounts,
                        instruments
                );

        PlaceOrderRequest request =
                new PlaceOrderRequest(
                        99L,
                        "AAPL",
                        OrderSide.BUY,
                        1L,
                        new BigDecimal("100.00"),
                        "ACCOUNT-FAIL"
                );

        assertThrows(
                AccountNotFoundException.class,
                () ->
                        tradeSystem.placeOrder(
                                1L,
                                request
                        )
        );
    }

    @Test
    @DisplayName("Order rejected on an inactive account")
    void orderRejectedWhenAccountInactive() {

        Account account =
                createAccount(
                        new BigDecimal("1000.00"),
                        AccountStatus.SUSPENDED
                );

        Instrument instrument =
                createInstrument(
                        true,
                        100
                );

        TradeSystem tradeSystem =
                createTradeSystem(
                        account,
                        instrument
                );

        assertThrows(
                AccountNotActiveException.class,
                () ->
                        tradeSystem.placeOrder(
                                1L,
                                buyRequest(
                                        1,
                                        "INACTIVE-ACCOUNT"
                                )
                        )
        );
    }

    @Test
    @DisplayName("Order rejected on unknown or non-tradable instrument")
    void orderRejectedOnInvalidInstrument() {

        Account account =
                createAccount(
                        new BigDecimal("1000.00"),
                        AccountStatus.ACTIVE
                );

        Instrument instrument =
                createInstrument(
                        false,
                        100
                );

        TradeSystem tradeSystem =
                createTradeSystem(
                        account,
                        instrument
                );

        assertThrows(
                InstrumentNotFoundException.class,
                () ->
                        tradeSystem.placeOrder(
                                1L,
                                buyRequest(
                                        1,
                                        "INVALID-INSTRUMENT"
                                )
                        )
        );
    }

    @Test
    @DisplayName("Duplicate idempotency key rejected")
    void duplicateIdempotencyKeyRejected() {

        Account account =
                createAccount(
                        new BigDecimal("1000.00"),
                        AccountStatus.ACTIVE
                );

        Instrument instrument =
                createInstrument(
                        true,
                        100
                );

        TradeSystem tradeSystem =
                createTradeSystem(
                        account,
                        instrument
                );

        PlaceOrderRequest request =
                buyRequest(
                        1,
                        "DUPLICATE-KEY"
                );

        tradeSystem.placeOrder(
                1L,
                request
        );

        assertThrows(
                DuplicateOrderException.class,
                () ->
                        tradeSystem.placeOrder(
                                2L,
                                request
                        )
        );
    }

    @Test
    @DisplayName("A request breaking two rules returns the code of the first rule")
    void firstRuleFailureReturned() {

        Account account =
                createAccount(
                        new BigDecimal("0.00"),
                        AccountStatus.ACTIVE
                );

        Instrument instrument =
                createInstrument(
                        true,
                        0
                );

        TradeSystem tradeSystem =
                createTradeSystem(
                        account,
                        instrument
                );

        InsufficientInstrumentQuantityException exception =
                assertThrows(
                        InsufficientInstrumentQuantityException.class,
                        () ->
                                tradeSystem.placeOrder(
                                        1L,
                                        buyRequest(
                                                5,
                                                "MULTI-RULE-FAIL"
                                        )
                                )
                );

        assertEquals(
                "ORD-409",
                exception.getCode()
        );
    }
}
