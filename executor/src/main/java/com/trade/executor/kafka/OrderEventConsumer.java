package com.trade.executor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.executor.client.FauxnanceClient;
import com.trade.executor.domain.FillDecision;
import com.trade.executor.domain.FillRuleEvaluator;
import com.trade.executor.domain.Quote;
import com.trade.executor.exception.NonRetryableOrderException;
import com.trade.executor.exception.RetryableOrderException;
import com.trade.executor.service.ExecutionService;
import com.trade.executor.service.QuoteCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Consumes ORDER_PLACED events from the "orders" topic (listening concurrently across all partitions).
 *
 * Workflow for each event:
 *   1. Parses and validates JSON payload structure.
 *   2. Queries Postgres to load order record — if status != 'NEW', skips (idempotency guard).
 *   3. Checks instrument tradability status ('ACTIVE').
 *   4. Fetches latest quote from Fauxnance GET /quotes/{symbol} (falling back to QuoteCache if API unavailable).
 *   5. Evaluates FillRuleEvaluator against limit price.
 *   6. Settles DB transaction (orders status, cash_balance with optimistic lock, holding upsert).
 *   7. Publishes ORDER_FILLED / ORDER_REJECTED event to "trade-events" topic keyed by accountId.
 *   8. Acknowledges offset.
 */
@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final FillRuleEvaluator fillRuleEvaluator;
    private final ExecutionService executionService;
    private final TradeEventPublisher tradeEventPublisher;
    private final QuoteCache quoteCache;
    private final FauxnanceClient fauxnanceClient;

    public OrderEventConsumer(ObjectMapper objectMapper,
                              FillRuleEvaluator fillRuleEvaluator,
                              ExecutionService executionService,
                              TradeEventPublisher tradeEventPublisher,
                              QuoteCache quoteCache,
                              FauxnanceClient fauxnanceClient) {
        this.objectMapper = objectMapper;
        this.fillRuleEvaluator = fillRuleEvaluator;
        this.executionService = executionService;
        this.tradeEventPublisher = tradeEventPublisher;
        this.quoteCache = quoteCache;
        this.fauxnanceClient = fauxnanceClient;
    }

    @KafkaListener(topics = "orders", groupId = "trade-executor",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onOrderPlaced(String message, Acknowledgment ack) {

        // 1. Parse JSON payload
        JsonNode root;
        try {
            root = objectMapper.readTree(message);
        } catch (JsonProcessingException e) {
            throw new NonRetryableOrderException("Malformed JSON in order event: " + e.getMessage(), e);
        }

        String eventType = root.path("eventType").asText("");
        if (!"ORDER_PLACED".equals(eventType)) {
            log.debug("Skipping non-ORDER_PLACED eventType: {}", eventType);
            ack.acknowledge();
            return;
        }

        JsonNode payload = root.path("payload");
        Long orderId = payload.path("orderId").asLong(0);
        if (orderId == 0) {
            throw new NonRetryableOrderException("ORDER_PLACED event missing orderId: " + message);
        }

        Long accountId = payload.path("accountId").asLong(0);
        if (accountId == 0) {
            throw new NonRetryableOrderException("ORDER_PLACED event missing accountId for orderId=" + orderId);
        }

        // Support both "symbol" and "ticker" keys
        String symbol = payload.has("symbol") && !payload.path("symbol").isNull() 
                ? payload.path("symbol").asText() 
                : payload.path("ticker").asText(null);

        String side = payload.path("side").asText(null);
        Long quantity = payload.path("quantity").asLong(0);

        // Support both "price" and "limitPrice" keys
        String limitStr = payload.has("price") && !payload.path("price").isNull()
                ? payload.path("price").asText()
                : payload.path("limitPrice").asText(null);

        if (symbol == null || side == null || quantity == 0 || limitStr == null) {
            throw new NonRetryableOrderException("ORDER_PLACED payload incomplete for orderId=" + orderId);
        }

        BigDecimal limitPrice;
        try {
            limitPrice = new BigDecimal(limitStr);
        } catch (NumberFormatException e) {
            throw new NonRetryableOrderException("Invalid price '" + limitStr + "' for orderId=" + orderId);
        }

        log.info("Consumer processing ORDER_PLACED: orderId={} accountId={} symbol={} side={} qty={} limit={}",
                 orderId, accountId, symbol, side, quantity, limitPrice);

        // 2. Load order record from Postgres — idempotency check
        Map<String, Object> orderRecord;
        try {
            orderRecord = executionService.fetchOrder(orderId);
        } catch (TransientDataAccessException e) {
            throw new RetryableOrderException("DB unavailable fetching order " + orderId, e);
        }

        if (orderRecord != null) {
            String currentStatus = (String) orderRecord.get("status");
            if (!"NEW".equalsIgnoreCase(currentStatus)) {
                log.info("Order {} already in state '{}', skipping processing", orderId, currentStatus);
                ack.acknowledge();
                return;
            }
        }

        // 3. Check instrument tradability status
        String instStatus = executionService.fetchInstrumentStatus(symbol);
        if (instStatus != null && !"ACTIVE".equalsIgnoreCase(instStatus)) {
            log.warn("Instrument {} is {}, rejecting order {}", symbol, instStatus, orderId);
            executionService.reject(orderId, "INSTRUMENT_NOT_ACTIVE");
            tradeEventPublisher.publishRejected(orderId, accountId, symbol, "INSTRUMENT_NOT_ACTIVE");
            ack.acknowledge();
            return;
        }

        // 4. Fetch quote — try single quote API GET /quotes/{symbol}, fallback to QuoteCache
        Quote quote = fauxnanceClient.fetchSingleQuote(symbol);
        if (quote == null || quote.stale()) {
            quote = quoteCache.get(symbol);
        }

        // 5. Fetch account & holding state
        Map<String, Object> account;
        try {
            account = executionService.fetchAccount(accountId);
        } catch (TransientDataAccessException e) {
            throw new RetryableOrderException("DB unavailable fetching account " + accountId, e);
        }

        if (account == null) {
            throw new NonRetryableOrderException("Account " + accountId + " not found for orderId=" + orderId);
        }

        Map<String, Object> holding;
        try {
            holding = executionService.fetchHolding(accountId, symbol);
        } catch (TransientDataAccessException e) {
            throw new RetryableOrderException("DB unavailable fetching holding for account " + accountId, e);
        }

        String accountStatus = (String) account.get("status");
        BigDecimal cashBalance = (BigDecimal) account.get("cash_balance");
        Long currentQty = holding != null ? ((Number) holding.get("quantity")).longValue() : 0L;
        BigDecimal currentAvgCost = holding != null ? (BigDecimal) holding.get("avg_cost") : BigDecimal.ZERO;

        // 6. Evaluate fill/reject rules (including limit price logic)
        FillDecision decision = fillRuleEvaluator.evaluate(
                side, quantity, limitPrice, quote,
                accountStatus, currentQty, currentAvgCost, cashBalance);

        // 7. DB settlement & event publication
        try {
            if (decision.filled()) {
                executionService.settle(orderId, accountId, symbol, side, quantity, decision, account);
                tradeEventPublisher.publishExecuted(orderId, accountId, symbol, side, quantity, decision.executedPrice());
                log.info("Order {} FILLED at price {}", orderId, decision.executedPrice());
            } else {
                executionService.reject(orderId, decision.rejectionReason());
                tradeEventPublisher.publishRejected(orderId, accountId, symbol, decision.rejectionReason());
                log.info("Order {} REJECTED reason={}", orderId, decision.rejectionReason());
            }
        } catch (RetryableOrderException e) {
            throw e;
        } catch (TransientDataAccessException e) {
            throw new RetryableOrderException("Transient DB failure settling orderId=" + orderId, e);
        }

        // 8. Acknowledge offset
        ack.acknowledge();
    }
}
