package com.trade.executor.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.executor.domain.FillDecision;
import com.trade.executor.domain.FillRuleEvaluator;
import com.trade.executor.domain.Quote;
import com.trade.executor.service.ExecutionService;
import com.trade.executor.service.QuoteCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Consumes ORDER_PLACED events from the "orders" topic and drives fill/reject logic.
 */
@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final FillRuleEvaluator fillRuleEvaluator;
    private final ExecutionService executionService;
    private final TradeEventPublisher tradeEventPublisher;
    private final QuoteCache quoteCache;

    public OrderEventConsumer(ObjectMapper objectMapper,
                              FillRuleEvaluator fillRuleEvaluator,
                              ExecutionService executionService,
                              TradeEventPublisher tradeEventPublisher,
                              QuoteCache quoteCache) {
        this.objectMapper = objectMapper;
        this.fillRuleEvaluator = fillRuleEvaluator;
        this.executionService = executionService;
        this.tradeEventPublisher = tradeEventPublisher;
        this.quoteCache = quoteCache;
    }

    @KafkaListener(topics = "orders", groupId = "trade-executor",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onOrderPlaced(String message, Acknowledgment ack) {
        try {
            JsonNode root = objectMapper.readTree(message);

            // Only handle ORDER_PLACED events
            String eventType = root.path("eventType").asText();
            if (!"ORDER_PLACED".equals(eventType)) {
                ack.acknowledge();
                return;
            }

            JsonNode payload = root.path("payload");
            Long orderId      = payload.path("orderId").asLong();
            Long accountId    = payload.path("accountId").asLong();
            String symbol     = payload.path("symbol").asText();
            String side       = payload.path("side").asText();
            Long quantity     = payload.path("quantity").asLong();
            BigDecimal limitPrice = new BigDecimal(payload.path("limitPrice").asText());

            log.info("Processing ORDER_PLACED: orderId={} accountId={} symbol={} side={} qty={} limit={}",
                     orderId, accountId, symbol, side, quantity, limitPrice);

            // Fetch live quote from in-memory cache
            Quote quote = quoteCache.get(symbol);

            // Fetch account state from DB
            Map<String, Object> account = executionService.fetchAccount(accountId);
            if (account == null) {
                log.error("Account {} not found, sending to DLT", accountId);
                ack.acknowledge();
                return;
            }

            String accountStatus = (String) account.get("status");
            BigDecimal cashBalance = (BigDecimal) account.get("cash_balance");

            // Fetch holding state
            Map<String, Object> holding = executionService.fetchHolding(accountId, symbol);
            Long currentQty = holding != null ? ((Number) holding.get("quantity")).longValue() : 0L;
            BigDecimal currentAvgCost = holding != null ? (BigDecimal) holding.get("avg_cost") : BigDecimal.ZERO;

            // Evaluate fill or reject
            FillDecision decision = fillRuleEvaluator.evaluate(
                    side, quantity, limitPrice, quote,
                    accountStatus, currentQty, currentAvgCost, cashBalance);

            if (decision.filled()) {
                executionService.settle(orderId, accountId, symbol, side, quantity,
                                        decision, account);
                tradeEventPublisher.publishExecuted(orderId, accountId, symbol, side, quantity,
                                                    decision.executedPrice());
                log.info("Order {} FILLED at {}", orderId, decision.executedPrice());
            } else {
                executionService.reject(orderId, decision.rejectionReason());
                tradeEventPublisher.publishRejected(orderId, accountId, symbol, decision.rejectionReason());
                log.info("Order {} REJECTED: {}", orderId, decision.rejectionReason());
            }

            ack.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process order event: {}", message, e);
            // Do not ack — will retry or route to DLT via error handler
            throw new RuntimeException("Order event processing failed", e);
        }
    }
}
