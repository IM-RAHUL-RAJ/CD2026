package com.trade.executor.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class FillRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(FillRuleEvaluator.class);

    /**
     * Pure business logic function: evaluates fill or reject decision against live quote.
     * 
     * For MARKET orders: executes at current price (bid for SELL, ask for BUY), ignoring limitPrice
     * For LIMIT orders: executes only if price condition is met (ask <= limitPrice for BUY, bid >= limitPrice for SELL)
     */
    public FillDecision evaluate(String side,
                                 Long quantity,
                                 BigDecimal limitPrice,
                                 Quote quote,
                                 String accountStatus,
                                 Long currentHoldingQty,
                                 BigDecimal currentHoldingAvgCost,
                                 BigDecimal cashBalance,
                                 String orderType) {

        // Normalize orderType (trim and uppercase for comparison)
        String normalizedOrderType = (orderType != null ? orderType.trim().toUpperCase() : "LIMIT");
        
        log.info("[*] FillRuleEvaluator.evaluate() called: side={}, qty={}, limitPrice={}, orderType={} (normalized={})", 
                side, quantity, limitPrice, orderType, normalizedOrderType);
        if (quote != null) {
            log.info("    Full Quote: symbol={}, price={}, bid={}, ask={}, stale={}", 
                    quote.symbol(), quote.price(), quote.bid(), quote.ask(), quote.stale());
        } else {
            log.warn("    Quote is NULL!");
        }

        if (!"ACTIVE".equalsIgnoreCase(accountStatus)) {
            return FillDecision.rejected("ACCOUNT_NOT_ACTIVE");
        }

        boolean isMarketOrder = "MARKET".equals(normalizedOrderType);

        // For LIMIT orders with unavailable/stale quotes, return PENDING so poller can retry
        // For MARKET orders: use stale quote if available (better than rejecting), only reject if completely null
        if (quote == null) {
            if (isMarketOrder) {
                log.error("[ERROR] MARKET order with NO quote available - cannot execute without any price");
                return FillDecision.rejected("PRICE_NOT_AVAILABLE");
            }
            log.info("[*] LIMIT order with no quote - returning PENDING for poller retry");
            return FillDecision.pending("QUOTE_UNAVAILABLE - waiting for price update");
        }
        
        if (quote.stale()) {
            if (!isMarketOrder) {
                log.info("[*] LIMIT order with stale quote - returning PENDING for fresh price");
                log.info("    Stale quote: symbol={}, price={}, bid={}, ask={}", 
                        quote.symbol(), quote.price(), quote.bid(), quote.ask());
                return FillDecision.pending("PRICE_STALE - waiting for fresh update");
            }
            log.warn("[*] MARKET order with STALE quote - proceeding with execution (better than rejecting)");
            log.warn("    Stale quote: symbol={}, price={}, bid={}, ask={}", 
                    quote.symbol(), quote.price(), quote.bid(), quote.ask());
            // Continue to execution with stale quote for MARKET orders
        }
        
        log.info("[*] Quote available and fresh: symbol={}, price={}, bid={}, ask={}", 
                quote.symbol(), quote.price(), quote.bid(), quote.ask());

        if ("BUY".equalsIgnoreCase(side)) {
            BigDecimal ask = quote.ask() != null ? quote.ask() : quote.price();
            log.info("[*] BUY order: ask={}, price={}, limitPrice={}, isMarketOrder={}", 
                    quote.ask(), quote.price(), limitPrice, isMarketOrder);
            if (ask == null) {
                log.error("[ERROR] BUY: both ask and price are null! quote.ask={}, quote.price={}", 
                        quote.ask(), quote.price());
                return FillDecision.rejected("PRICE_NOT_AVAILABLE");
            }
            if (ask.compareTo(BigDecimal.ZERO) <= 0) {
                log.error("[ERROR] BUY: ask/price is zero or negative! ask={}", ask);
                return FillDecision.rejected("PRICE_NOT_AVAILABLE");
            }
            
            // For limit orders, check if ask price meets the limit
            if (!isMarketOrder) {
                int comparison = ask.compareTo(limitPrice);
                log.info("[*] BUY LIMIT: Comparing ask={} vs limitPrice={}, result={}", 
                        ask, limitPrice, comparison);
                if (comparison > 0) {
                    // Ask price is higher than our limit - hold for now as pending
                    log.info("[*] BUY LIMIT: ask > limitPrice, returning PENDING (PRICE_NOT_MET)");
                    return FillDecision.pending("PRICE_NOT_MET");
                } else {
                    log.info("[*] BUY LIMIT: ask <= limitPrice, proceeding to FILL");
                }
            }

            BigDecimal executedPrice = ask.setScale(2, RoundingMode.HALF_UP);
            BigDecimal totalCost = executedPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);

            if (cashBalance.compareTo(totalCost) < 0) {
                log.warn("[*] BUY: Insufficient funds - need {} but have {}", totalCost, cashBalance);
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
            log.info("[*] SELL order: bid={}, price={}, limitPrice={}, isMarketOrder={}", 
                    quote.bid(), quote.price(), limitPrice, isMarketOrder);
            if (bid == null) {
                log.error("[ERROR] SELL: both bid and price are null! quote.bid={}, quote.price={}", 
                        quote.bid(), quote.price());
                return FillDecision.rejected("PRICE_NOT_AVAILABLE");
            }
            if (bid.compareTo(BigDecimal.ZERO) <= 0) {
                log.error("[ERROR] SELL: bid/price is zero or negative! bid={}", bid);
                return FillDecision.rejected("PRICE_NOT_AVAILABLE");
            }
            
            // For limit orders, check if bid price meets the limit
            if (!isMarketOrder) {
                int comparison = bid.compareTo(limitPrice);
                log.info("[*] SELL LIMIT: Comparing bid={} vs limitPrice={}, result={}", 
                        bid, limitPrice, comparison);
                if (comparison < 0) {
                    // Bid price is lower than our limit - hold for now as pending
                    log.info("[*] SELL LIMIT: bid < limitPrice, returning PENDING (PRICE_NOT_MET)");
                    return FillDecision.pending("PRICE_NOT_MET");
                } else {
                    log.info("[*] SELL LIMIT: bid >= limitPrice, proceeding to FILL");
                }
            }

            long currentQty = currentHoldingQty != null ? currentHoldingQty : 0L;
            if (currentQty < quantity) {
                log.warn("[*] SELL: Insufficient holdings - need {} but have {}", quantity, currentQty);
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
