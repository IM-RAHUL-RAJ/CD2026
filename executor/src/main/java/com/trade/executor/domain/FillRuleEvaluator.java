package com.trade.executor.domain;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class FillRuleEvaluator {

    /**
     * Pure business logic function: evaluates fill or reject decision against live quote.
     */
    public FillDecision evaluate(String side,
                                 Long quantity,
                                 BigDecimal limitPrice,
                                 Quote quote,
                                 String accountStatus,
                                 Long currentHoldingQty,
                                 BigDecimal currentHoldingAvgCost,
                                 BigDecimal cashBalance) {

        if (!"ACTIVE".equalsIgnoreCase(accountStatus)) {
            return FillDecision.rejected("ACCOUNT_NOT_ACTIVE");
        }

        if (quote == null || quote.stale()) {
            return FillDecision.rejected("PRICE_NOT_AVAILABLE");
        }

        if ("BUY".equalsIgnoreCase(side)) {
            BigDecimal ask = quote.ask() != null ? quote.ask() : quote.price();
            if (ask == null || limitPrice.compareTo(ask) < 0) {
                return FillDecision.rejected("PRICE_NOT_MET");
            }

            BigDecimal executedPrice = ask.setScale(2, RoundingMode.HALF_UP);
            BigDecimal totalCost = executedPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);

            if (cashBalance.compareTo(totalCost) < 0) {
                return FillDecision.rejected("INSUFFICIENT_FUNDS");
            }

            BigDecimal cashDelta = totalCost.negate();
            long newHoldingQty = (currentHoldingQty != null ? currentHoldingQty : 0L) + quantity;

            BigDecimal currentTotalCost = currentHoldingAvgCost != null && currentHoldingQty != null
                    ? currentHoldingAvgCost.multiply(BigDecimal.valueOf(currentHoldingQty))
                    : BigDecimal.ZERO;

            BigDecimal newTotalCost = currentTotalCost.add(totalCost);
            BigDecimal newAvgCost = newTotalCost.divide(BigDecimal.valueOf(newHoldingQty), 2, RoundingMode.HALF_UP);

            return FillDecision.filled(executedPrice, cashDelta, newHoldingQty, newAvgCost);

        } else if ("SELL".equalsIgnoreCase(side)) {
            BigDecimal bid = quote.bid() != null ? quote.bid() : quote.price();
            if (bid == null || limitPrice.compareTo(bid) > 0) {
                return FillDecision.rejected("PRICE_NOT_MET");
            }

            long currentQty = currentHoldingQty != null ? currentHoldingQty : 0L;
            if (currentQty < quantity) {
                return FillDecision.rejected("INSUFFICIENT_HOLDINGS");
            }

            BigDecimal executedPrice = bid.setScale(2, RoundingMode.HALF_UP);
            BigDecimal cashDelta = executedPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
            long newHoldingQty = currentQty - quantity;
            BigDecimal newAvgCost = newHoldingQty == 0 ? BigDecimal.ZERO : (currentHoldingAvgCost != null ? currentHoldingAvgCost : BigDecimal.ZERO);

            return FillDecision.filled(executedPrice, cashDelta, newHoldingQty, newAvgCost);
        }

        return FillDecision.rejected("INVALID_SIDE");
    }
}
