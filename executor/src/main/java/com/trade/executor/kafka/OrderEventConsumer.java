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

        log.info("===== CONSUMER RECEIVED MESSAGE =====");
        log.info("Raw message: {}", message);

        try {
        // 1. Parse JSON payload
        log.info("→ Parsing JSON...");
        JsonNode root;
        try {
            root = objectMapper.readTree(message);
            
            // Handle double-encoded JSON (if root is a TextNode, parse it again)
            if (root.isTextual()) {
                log.info("✓ Detected double-encoded JSON, parsing again...");
                root = objectMapper.readTree(root.asText());
            }
            
            log.info("✓ JSON parsed");
            log.info("  Root is ObjectNode: {}", root.isObject());
            log.info("  Root keys: {}", root.fieldNames());
            log.info("  Root JSON: {}", root.toPrettyString());
        } catch (JsonProcessingException e) {
            log.error("✗ JSON PARSE FAILED: {}", e.getMessage());
            throw new NonRetryableOrderException("Malformed JSON in order event: " + e.getMessage(), e);
        }

        log.info("→ Checking eventType...");
        String eventType = root.path("eventType").asText("");
        log.info("  eventType='{}'", eventType);
        if (!"ORDER_PLACED".equals(eventType)) {
            log.debug("Skipping non-ORDER_PLACED eventType: {}", eventType);
            ack.acknowledge();
            return;
        }

        log.info("→ Extracting payload...");
        JsonNode payload = root.path("payload");
        log.info("✓ Payload extracted");
        
        log.info("→ Parsing orderId...");
        String orderIdStr = payload.path("orderId").asText("");
        log.info("  orderIdStr='{}'", orderIdStr);
        Long orderId = 0L;
        try {
            orderId = Long.parseLong(orderIdStr);
            log.info("✓ orderId parsed: {}", orderId);
        } catch (NumberFormatException e) {
            log.error("✗ PARSE FAILED: orderId is not a number: '{}'", orderIdStr);
            throw new NonRetryableOrderException("orderId must be a number, got: " + orderIdStr);
        }
        
        if (orderId == 0) {
            log.error("✗ FAILED: orderId is 0 or missing");
            throw new NonRetryableOrderException("ORDER_PLACED event missing orderId: " + message);
        }
        
        log.info("✓ Parsed orderId={}", orderId);

        log.info("→ Parsing accountId...");
        Long accountId = payload.path("accountId").asLong(0);
        log.info("  accountId={}", accountId);
        if (accountId == 0) {
            log.error("✗ FAILED: accountId is 0 or missing");
            throw new NonRetryableOrderException("ORDER_PLACED event missing accountId for orderId=" + orderId);
        }
        log.info("✓ accountId parsed: {}", accountId);

        // Support both "symbol" and "ticker" keys
        log.info("→ Parsing symbol...");
        String symbol = payload.has("symbol") && !payload.path("symbol").isNull() 
                ? payload.path("symbol").asText() 
                : payload.path("ticker").asText(null);
        log.info("  symbol={}", symbol);

        log.info("→ Parsing side, quantity, price...");
        String side = payload.path("side").asText(null);
        Long quantity = payload.path("quantity").asLong(0);
        log.info("  side={}, quantity={}", side, quantity);

        // Support both "price" and "limitPrice" keys
        String limitStr = payload.has("price") && !payload.path("price").isNull()
                ? payload.path("price").asText()
                : payload.path("limitPrice").asText(null);
        log.info("  price={}", limitStr);

        if (symbol == null || side == null || quantity == 0 || limitStr == null) {
            log.error("✗ FAILED: Incomplete payload - symbol={}, side={}, qty={}, price={}", symbol, side, quantity, limitStr);
            throw new NonRetryableOrderException("ORDER_PLACED payload incomplete for orderId=" + orderId);
        }

        BigDecimal limitPrice;
        try {
            limitPrice = new BigDecimal(limitStr);
            log.info("✓ limitPrice parsed: {}", limitPrice);
        } catch (NumberFormatException e) {
            log.error("✗ FAILED: Invalid price: {}", limitStr);
            throw new NonRetryableOrderException("Invalid price '" + limitStr + "' for orderId=" + orderId);
        }

        log.info("✓ All fields parsed successfully");

        // 2. Load order record from Postgres — idempotency check
        Map<String, Object> orderRecord;
        try {
            log.info("→ Fetching order {} from DB", orderId);
            orderRecord = executionService.fetchOrder(orderId);
            log.info("✓ Successfully fetched order {}", orderId);
        } catch (TransientDataAccessException e) {
            log.error("FAILED: DB unavailable fetching order {}", orderId);
            throw new RetryableOrderException("DB unavailable fetching order " + orderId, e);
        }

        if (orderRecord != null) {
            String currentStatus = (String) orderRecord.get("status");
            log.info("  Order {} current status: {}", orderId, currentStatus);
            if (!"NEW".equalsIgnoreCase(currentStatus)) {
                log.info("Order {} already in state '{}', skipping processing", orderId, currentStatus);
                ack.acknowledge();
                return;
            }
        } else {
            log.error("FAILED: Order {} not found in database!", orderId);
        }

        // 3. Check instrument tradability status
        String instStatus = executionService.fetchInstrumentStatus(symbol);
        if (instStatus == null) {
            log.error("[ERROR] Instrument {} not found in database for order {}", symbol, orderId);
            log.error("[REJECT] Marking order {} as REJECTED due to invalid instrument", orderId);
            // Reject the order in the database first
            executionService.reject(orderId, "INSTRUMENT_NOT_FOUND");
            log.error("[DLT] Order {} will be sent to Dead Letter Topic - instrument not found: {}", orderId, symbol);
            throw new NonRetryableOrderException("Instrument " + symbol + " not found - sending to DLT for order " + orderId);
        }
        if (!"ACTIVE".equalsIgnoreCase(instStatus)) {
            log.warn("[*] Instrument {} is {}, rejecting order {}", symbol, instStatus, orderId);
            executionService.reject(orderId, "INSTRUMENT_NOT_ACTIVE");
            tradeEventPublisher.publishRejected(orderId, accountId, symbol, "INSTRUMENT_NOT_ACTIVE");
            ack.acknowledge();
            return;
        }

        // 4. Fetch quote — try single quote API GET /quotes/{symbol}, fallback to QuoteCache
        log.info("[*] Fetching quote for symbol {} from Fauxnance API...", symbol);
        Quote quote = fauxnanceClient.fetchSingleQuote(symbol);
        if (quote == null) {
            log.warn("[*] No quote from API, falling back to QuoteCache for {}", symbol);
            quote = quoteCache.get(symbol);
            if (quote != null) {
                log.info("[OK] Quote from cache: price={}, bid={}, ask={}, stale={}", 
                        quote.price(), quote.bid(), quote.ask(), quote.stale());
            } else {
                log.error("[ERROR] No quote available from API or cache for symbol {}", symbol);
            }
        } else {
            log.info("[OK] Quote from API: price={}, bid={}, ask={}, stale={}", 
                    quote.price(), quote.bid(), quote.ask(), quote.stale());
        }
        
        // Log quote availability status
        if (quote == null) {
            log.error("[CRITICAL] Quote is NULL for symbol {} - will be evaluated by FillRuleEvaluator", symbol);
        } else if (quote.stale()) {
            log.warn("[WARN] Quote is STALE for symbol {} - MARKET orders will use stale price", symbol);
        } else {
            log.info("[GOOD] Quote is fresh for symbol {}", symbol);
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

        // Extract order_type (default to LIMIT for backward compatibility)
        String orderType = orderRecord != null ? (String) orderRecord.get("order_type") : "LIMIT";
        if (orderType == null || orderType.trim().isEmpty()) {
            orderType = "LIMIT";
        }
        orderType = orderType.trim().toUpperCase();
        log.info("  Order type: {} (trimmed & uppercase)", orderType);

        // 6. Evaluate fill/reject rules (including limit price logic)
        log.info("[*] Evaluating fill decision: side={}, qty={}, limitPrice={}, orderType={}", 
                side, quantity, limitPrice, orderType);
        if (quote != null) {
            log.info("    Quote: price={}, bid={}, ask={}, stale={}", 
                    quote.price(), quote.bid(), quote.ask(), quote.stale());
        } else {
            log.error("[ERROR] Quote is NULL!");
        }
        
        FillDecision decision = fillRuleEvaluator.evaluate(
                side, quantity, limitPrice, quote,
                accountStatus, currentQty, currentAvgCost, cashBalance, orderType);

        // 7. DB settlement & event publication
        try {
            log.info("[*] Fill decision result: status={}, executedPrice={}, reason={}", 
                    decision.status(), decision.executedPrice(), decision.reason());
            log.info("[*] Settling order {}...", orderId);
            if (decision.pending()) {
                log.info("  → PENDING decision: limit order price not met yet");
                log.info("Order {} PENDING - waiting for price condition: {}", orderId, decision.rejectionReason());
                // Don't update DB, just ack - order stays in NEW status for future matching
            } else if (decision.filled()) {
                log.info("  → FILL decision, executing settle()");
                executionService.settle(orderId, accountId, symbol, side, quantity, decision, account);
                log.info("✓ Order {} settled in DB", orderId);
                tradeEventPublisher.publishExecuted(orderId, accountId, symbol, side, quantity, decision.executedPrice());
                log.info("Order {} FILLED at price {}", orderId, decision.executedPrice());
            } else {
                log.info("  → REJECT decision: {}", decision.rejectionReason());
                executionService.reject(orderId, decision.rejectionReason());
                log.info("✓ Order {} rejected in DB", orderId);
                tradeEventPublisher.publishRejected(orderId, accountId, symbol, decision.rejectionReason());
                log.info("Order {} REJECTED reason={}", orderId, decision.rejectionReason());
            }
        } catch (RetryableOrderException e) {
            log.error("FAILED: Retryable error settling order {}: {}", orderId, e.getMessage());
            throw e;
        } catch (TransientDataAccessException e) {
            log.error("FAILED: Transient DB failure settling orderId={}", orderId);
            throw new RetryableOrderException("Transient DB failure settling orderId=" + orderId, e);
        } catch (Exception e) {
            log.error("FAILED: Unexpected error settling order {}: {}", orderId, e.getMessage(), e);
            throw new NonRetryableOrderException("Unexpected error settling orderId=" + orderId, e);
        }

        // 8. Acknowledge offset
        log.info("→ Acknowledging offset for orderId {}", orderId);
        ack.acknowledge();
        log.info("✓✓✓ ORDER {} COMPLETE ✓✓✓\n", orderId);
        
        } catch (Exception e) {
            log.error("!!! CRITICAL: Unhandled exception in OrderEventConsumer: {}", e.getClass().getName(), e);
            throw e;
        }
    }
}
