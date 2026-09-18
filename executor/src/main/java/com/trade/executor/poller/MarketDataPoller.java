package com.trade.executor.poller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.executor.client.FauxnanceClient;
import com.trade.executor.domain.Quote;
import com.trade.executor.service.QuoteCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Scheduled poller that calls Fauxnance GET /quotes?symbols=A,B,C in batches (up to 25 symbols)
 * to conserve API quota.
 *
 * For each quote returned:
 *   1. Updates in-memory QuoteCache.
 *   2. Publishes ONE Kafka message per symbol to the "market-data" topic,
 *      keyed by symbol (preserving per-symbol Kafka partition ordering).
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

    public MarketDataPoller(FauxnanceClient fauxnanceClient,
                            QuoteCache quoteCache,
                            JdbcTemplate jdbc,
                            KafkaTemplate<String, String> kafkaTemplate,
                            ObjectMapper objectMapper) {
        this.fauxnanceClient = fauxnanceClient;
        this.quoteCache = quoteCache;
        this.jdbc = jdbc;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "#{${fauxnance.poll-interval-seconds:60} * 1000}")
    public void poll() {
        try {
            List<String> symbols = jdbc.queryForList(
                    "SELECT symbol FROM instrument WHERE status = 'ACTIVE'",
                    String.class);

            if (symbols.isEmpty()) {
                log.debug("No active instruments to poll");
                return;
            }

            log.info("Polling Fauxnance for {} symbols via batch API", symbols.size());
            List<Quote> quotes = fauxnanceClient.fetchQuotes(symbols);

            for (Quote quote : quotes) {
                // 1. Update in-memory cache
                quoteCache.put(quote.symbol(), quote);

                // 2. Publish ONE Kafka message per symbol to market-data topic, keyed by symbol
                publishMarketDataEvent(quote);
            }

            log.info("MarketDataPoller updated QuoteCache and published {} market-data messages", quotes.size());

        } catch (Exception e) {
            log.error("Market data poll failed: {}", e.getMessage(), e);
        }
    }

    private void publishMarketDataEvent(Quote quote) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "eventType", "MARKET_DATA_UPDATE",
                    "symbol", quote.symbol(),
                    "price", quote.price(),
                    "currency", quote.currency() != null ? quote.currency() : "USD",
                    "change", quote.change() != null ? quote.change() : 0,
                    "changePercent", quote.changePercent() != null ? quote.changePercent() : 0,
                    "stale", quote.stale(),
                    "timestamp", Instant.now().toString()
            ));

            // Keyed by symbol to preserve per-symbol ordering on Kafka partitions
            kafkaTemplate.send(marketDataTopic, quote.symbol(), json);
        } catch (Exception e) {
            log.error("Failed to publish market-data event for symbol {}: {}", quote.symbol(), e.getMessage());
        }
    }
}
