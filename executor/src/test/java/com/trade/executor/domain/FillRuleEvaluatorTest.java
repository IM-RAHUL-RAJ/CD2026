package com.trade.executor.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for FillRuleEvaluator — no Spring context needed.
 * Tests cover all fill/reject paths for BUY and SELL orders.
 */
class FillRuleEvaluatorTest {

    private FillRuleEvaluator evaluator;

    // A standard active quote: price=150, bid=149.50, ask=150.50
    private static final Quote ACTIVE_QUOTE = new Quote(
            "AAPL",
            new BigDecimal("150.00"),
            new BigDecimal("149.50"),
            new BigDecimal("150.50"),
            "USD",
            new BigDecimal("1.25"),
            new BigDecimal("0.84"),
            new BigDecimal("148.75"),
            "REGULAR",
            false,
            "2026-09-17T00:00:00Z"
    );

    private static final Quote STALE_QUOTE = new Quote(
            "AAPL",
            new BigDecimal("150.00"),
            new BigDecimal("149.50"),
            new BigDecimal("150.50"),
            "USD", null, null, null, "CLOSED",
            true, // stale=true
            "2026-09-16T00:00:00Z"
    );

    @BeforeEach
    void setUp() {
        evaluator = new FillRuleEvaluator();
    }

    // ---- BUY tests ----

    @Test
    @DisplayName("BUY order fills when limitPrice >= ask")
    void buyFillsWhenLimitPriceAtOrAboveAsk() {
        FillDecision decision = evaluator.evaluate(
                "BUY", 10L, new BigDecimal("151.00"), ACTIVE_QUOTE,
                "ACTIVE", 0L, BigDecimal.ZERO, new BigDecimal("2000.00"));

        assertThat(decision.filled()).isTrue();
        assertThat(decision.executedPrice()).isEqualByComparingTo("150.50"); // executed at ask
        assertThat(decision.cashDelta()).isEqualByComparingTo("-1505.00"); // -150.50 * 10
        assertThat(decision.newHoldingQty()).isEqualTo(10L);
    }

    @Test
    @DisplayName("BUY order rejected when limitPrice < ask")
    void buyRejectedWhenLimitPriceBelowAsk() {
        FillDecision decision = evaluator.evaluate(
                "BUY", 10L, new BigDecimal("149.00"), ACTIVE_QUOTE,
                "ACTIVE", 0L, BigDecimal.ZERO, new BigDecimal("5000.00"));

        assertThat(decision.filled()).isFalse();
        assertThat(decision.rejectionReason()).isEqualTo("PRICE_NOT_MET");
    }

    @Test
    @DisplayName("BUY order rejected when insufficient funds")
    void buyRejectedWhenInsufficientFunds() {
        FillDecision decision = evaluator.evaluate(
                "BUY", 100L, new BigDecimal("151.00"), ACTIVE_QUOTE,
                "ACTIVE", 0L, BigDecimal.ZERO, new BigDecimal("100.00")); // only $100

        assertThat(decision.filled()).isFalse();
        assertThat(decision.rejectionReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("BUY averages down when adding to existing holding")
    void buyAveragesExistingHolding() {
        // Already hold 10 shares at avg cost $140. Buying 10 more at $150.50 ask.
        FillDecision decision = evaluator.evaluate(
                "BUY", 10L, new BigDecimal("151.00"), ACTIVE_QUOTE,
                "ACTIVE", 10L, new BigDecimal("140.00"), new BigDecimal("5000.00"));

        assertThat(decision.filled()).isTrue();
        assertThat(decision.newHoldingQty()).isEqualTo(20L);
        // avg = (10*140 + 10*150.50) / 20 = 2905 / 20 = 145.25
        assertThat(decision.newAvgCost()).isEqualByComparingTo("145.25");
    }

    // ---- SELL tests ----

    @Test
    @DisplayName("SELL order fills when limitPrice <= bid")
    void sellFillsWhenLimitPriceAtOrBelowBid() {
        FillDecision decision = evaluator.evaluate(
                "SELL", 5L, new BigDecimal("149.00"), ACTIVE_QUOTE,
                "ACTIVE", 10L, new BigDecimal("140.00"), new BigDecimal("0.00"));

        assertThat(decision.filled()).isTrue();
        assertThat(decision.executedPrice()).isEqualByComparingTo("149.50"); // executed at bid
        assertThat(decision.cashDelta()).isEqualByComparingTo("747.50"); // +149.50 * 5
        assertThat(decision.newHoldingQty()).isEqualTo(5L);
    }

    @Test
    @DisplayName("SELL order rejected when limitPrice > bid")
    void sellRejectedWhenLimitPriceAboveBid() {
        FillDecision decision = evaluator.evaluate(
                "SELL", 5L, new BigDecimal("152.00"), ACTIVE_QUOTE,
                "ACTIVE", 10L, new BigDecimal("140.00"), BigDecimal.ZERO);

        assertThat(decision.filled()).isFalse();
        assertThat(decision.rejectionReason()).isEqualTo("PRICE_NOT_MET");
    }

    @Test
    @DisplayName("SELL order rejected when insufficient holdings")
    void sellRejectedWhenInsufficientHoldings() {
        FillDecision decision = evaluator.evaluate(
                "SELL", 20L, new BigDecimal("149.00"), ACTIVE_QUOTE,
                "ACTIVE", 5L, new BigDecimal("140.00"), BigDecimal.ZERO); // only 5 held

        assertThat(decision.filled()).isFalse();
        assertThat(decision.rejectionReason()).isEqualTo("INSUFFICIENT_HOLDINGS");
    }

    @Test
    @DisplayName("SELL entire holding sets newHoldingQty to 0")
    void sellEntireHoldingResultsInZeroQty() {
        FillDecision decision = evaluator.evaluate(
                "SELL", 10L, new BigDecimal("149.00"), ACTIVE_QUOTE,
                "ACTIVE", 10L, new BigDecimal("140.00"), BigDecimal.ZERO);

        assertThat(decision.filled()).isTrue();
        assertThat(decision.newHoldingQty()).isEqualTo(0L);
    }

    // ---- Common rejection tests ----

    @Test
    @DisplayName("Any order rejected when account is not ACTIVE")
    void rejectedWhenAccountNotActive() {
        FillDecision decision = evaluator.evaluate(
                "BUY", 10L, new BigDecimal("151.00"), ACTIVE_QUOTE,
                "SUSPENDED", 0L, BigDecimal.ZERO, new BigDecimal("5000.00"));

        assertThat(decision.filled()).isFalse();
        assertThat(decision.rejectionReason()).isEqualTo("ACCOUNT_NOT_ACTIVE");
    }

    @Test
    @DisplayName("Any order rejected when quote is null")
    void rejectedWhenQuoteIsNull() {
        FillDecision decision = evaluator.evaluate(
                "BUY", 10L, new BigDecimal("151.00"), null,
                "ACTIVE", 0L, BigDecimal.ZERO, new BigDecimal("5000.00"));

        assertThat(decision.filled()).isFalse();
        assertThat(decision.rejectionReason()).isEqualTo("PRICE_NOT_AVAILABLE");
    }

    @Test
    @DisplayName("Any order rejected when quote is stale")
    void rejectedWhenQuoteIsStale() {
        FillDecision decision = evaluator.evaluate(
                "BUY", 10L, new BigDecimal("151.00"), STALE_QUOTE,
                "ACTIVE", 0L, BigDecimal.ZERO, new BigDecimal("5000.00"));

        assertThat(decision.filled()).isFalse();
        assertThat(decision.rejectionReason()).isEqualTo("PRICE_NOT_AVAILABLE");
    }

    @Test
    @DisplayName("Unknown side returns INVALID_SIDE rejection")
    void rejectedWhenSideIsInvalid() {
        FillDecision decision = evaluator.evaluate(
                "HOLD", 10L, new BigDecimal("151.00"), ACTIVE_QUOTE,
                "ACTIVE", 0L, BigDecimal.ZERO, new BigDecimal("5000.00"));

        assertThat(decision.filled()).isFalse();
        assertThat(decision.rejectionReason()).isEqualTo("INVALID_SIDE");
    }
}
