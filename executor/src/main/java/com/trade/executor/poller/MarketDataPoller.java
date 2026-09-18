package com.trade.executor.poller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.executor.client.FauxnanceClient;
import com.trade.executor.domain.FillDecision;
import com.trade.executor.domain.FillRuleEvaluator;
import com.trade.executor.domain.Quote;
import com.trade.executor.service.ExecutionService;
import com.trade.executor.service.QuoteCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Scheduled poller that:
 * 1. Fetches quotes ONLY for tickers with NEW orders (status='NEW')
 * 2. Updates QuoteCache with new prices
 * 3. Checks if any pending orders can now be executed based on new prices
 * 4. Executes orders if price conditions are met
 * 5. Publishes market-data events to Kafka
 */
@Component
public class MarketDataPoller {

    private static final Logger log = LoggerFactory.getLogger(MarketDataPoller.class);

    @Value("${market.data.topic:market-data}")
    private String marketDataTopic;

    private final FauxnanceClient fauxnanceClient;
    private final QuoteCache quoteCache;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutionService executionService;
    private final FillRuleEvaluator fillRuleEvaluator;

    public MarketDataPoller(FauxnanceClient fauxnanceClient,
                            QuoteCache quoteCache,
                            JdbcTemplate jdbc,
                            KafkaTemplate<String, String> kafkaTemplate,
                            ObjectMapper objectMapper,
                            ExecutionService executionService,
                            FillRuleEvaluator fillRuleEvaluator) {
        this.fauxnanceClient = fauxnanceClient;
        this.quoteCache = quoteCache;
        this.jdbc = jdbc;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.executionService = executionService;
        this.fillRuleEvaluator = fillRuleEvaluator;
    }

    @Scheduled(fixedDelayString = "#{${fauxnance.poll-interval-seconds:60} * 1000}")
    public void poll() {
        try {
            // [*] Step 1: Fetch ONLY tickers that have NEW orders (pending orders)
            List<String> tickersWithNewOrders = jdbc.queryForList(
                    "SELECT DISTINCT ticker FROM orders WHERE status = 'NEW' ORDER BY ticker",
                    String.class);

            if (tickersWithNewOrders.isEmpty()) {
                log.debug("No NEW orders to poll for");
                return;
            }

            log.info("[*] Polling Fauxnance for {} tickers with NEW orders", tickersWithNewOrders.size());
            
            // [*] Step 2: Fetch quotes for these tickers
            List<Quote> quotes = fauxnanceClient.fetchQuotes(tickersWithNewOrders);

            if (quotes == null || quotes.isEmpty()) {
                log.warn("[API ERROR] No quotes returned from Fauxnance, skipping execution check");
                return;
            }

            // [*] Step 3: For each quote, update cache and check if any pending orders can execute
            int executedCount = 0;
            for (Quote quote : quotes) {
                quoteCache.put(quote.symbol(), quote);
                
                // Fetch all NEW orders for this ticker
                List<Map<String, Object>> newOrders = jdbc.queryForList(
                        "SELECT order_id, account_id, ticker, side, quantity, price, order_type, status " +
                        "FROM orders WHERE ticker = ? AND status = 'NEW' ORDER BY order_id",
                        quote.symbol());

                for (Map<String, Object> orderRecord : newOrders) {
                    try {
                        executedCount += executePendingOrderIfConditionMet(orderRecord, quote);
                    } catch (Exception e) {
                        log.error("[ERROR] Failed to check execution for order {}: {}", 
                                orderRecord.get("order_id"), e.getMessage());
                    }
                }

                // [*] Step 4: Publish market-data event
                publishMarketDataEvent(quote);
            }

            log.info("[OK] Polling complete: updated cache, attempted to execute {} pending orders", executedCount);

        } catch (Exception e) {
            log.error("[ERROR] Market data poll failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Checks if a NEW order can be executed based on current quote.
     * If price condition is met, executes the order immediately.
     * Returns 1 if order was executed, 0 otherwise.
     */
    private int executePendingOrderIfConditionMet(Map<String, Object> orderRecord, Quote quote) {
        Long orderId = ((Number) orderRecord.get("order_id")).longValue();
        Long accountId = ((Number) orderRecord.get("account_id")).longValue();
        String ticker = (String) orderRecord.get("ticker");
        String side = (String) orderRecord.get("side");
        Long quantity = ((Number) orderRecord.get("quantity")).longValue();
        BigDecimal limitPrice = (BigDecimal) orderRecord.get("price");
        String orderType = (String) orderRecord.get("order_type");
        if (orderType == null || orderType.trim().isEmpty()) {
            orderType = "LIMIT";
        }
        orderType = orderType.trim().toUpperCase();

        // [*] Re-evaluate with current quote
        Map<String, Object> account = executionService.fetchAccount(accountId);
        if (account == null) {
            log.error("[ERROR] Account {} not found for order {}", accountId, orderId);
            return 0;
        }

        Map<String, Object> holding = executionService.fetchHolding(accountId, ticker);
        Long currentQty = holding != null ? ((Number) holding.get("quantity")).longValue() : 0L;
        BigDecimal currentAvgCost = holding != null ? (BigDecimal) holding.get("avg_cost") : BigDecimal.ZERO;

        String accountStatus = (String) account.get("status");
        BigDecimal cashBalance = (BigDecimal) account.get("cash_balance");

        FillDecision decision = fillRuleEvaluator.evaluate(
                side, quantity, limitPrice, quote,
                accountStatus, currentQty, currentAvgCost, cashBalance, orderType);

        // [*] If condition is now met, execute
        if (decision.filled()) {
            log.info("[*] Order {} price condition MET during polling, executing at {} (ticker={})", 
                    orderId, quote.price(), ticker);
            executionService.settle(orderId, accountId, ticker, side, quantity, decision, account);
            return 1;
        } else if ("REJECTED".equals(decision.status())) {
            log.warn("[*] Order {} rejected during polling: {}", orderId, decision.rejectionReason());
            executionService.reject(orderId, decision.rejectionReason());
            return 1;
        } else {
            // Still PENDING - condition not yet met
            log.debug("Order {} still PENDING (price condition not met)", orderId);
            return 0;
        }
    }

    private void publishMarketDataEvent(Quote quote) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "eventType", "MARKET_DATA_UPDATE",
                    "symbol", quote.symbol(),
                    "price", quote.price(),
                    "bid", quote.bid(),
                    "ask", quote.ask(),
                    "currency", quote.currency() != null ? quote.currency() : "USD",
                    "change", quote.change() != null ? quote.change() : 0,
                    "changePercent", quote.changePercent() != null ? quote.changePercent() : 0,
                    "stale", quote.stale(),
                    "timestamp", Instant.now().toString()
            ));

            // Keyed by symbol to preserve per-symbol ordering on Kafka partitions
            kafkaTemplate.send(marketDataTopic, quote.symbol(), json);
        } catch (Exception e) {
            log.error("[ERROR] Failed to publish market-data event for symbol {}: {}", quote.symbol(), e.getMessage());
        }
    }
}
