package com.trade.executor.poller;

import com.trade.executor.client.FauxnanceClient;
import com.trade.executor.domain.Quote;
import com.trade.executor.service.QuoteCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls Fauxnance every N seconds for all active instrument symbols.
 * Fetched quotes are stored in QuoteCache for immediate use by OrderEventConsumer.
 *
 * Poll interval is driven by ${fauxnance.poll-interval-seconds} (default 60s).
 * Using fixedDelayString means the next poll starts AFTER the current one finishes,
 * preventing overlapping calls.
 */
@Component
public class MarketDataPoller {

    private static final Logger log = LoggerFactory.getLogger(MarketDataPoller.class);

    private final FauxnanceClient fauxnanceClient;
    private final QuoteCache quoteCache;
    private final JdbcTemplate jdbc;

    public MarketDataPoller(FauxnanceClient fauxnanceClient,
                            QuoteCache quoteCache,
                            JdbcTemplate jdbc) {
        this.fauxnanceClient = fauxnanceClient;
        this.quoteCache = quoteCache;
        this.jdbc = jdbc;
    }

    /**
     * fixedDelayString reads poll interval from config (milliseconds).
     * e.g. poll-interval-seconds=60 → 60000ms
     */
    @Scheduled(fixedDelayString = "#{${fauxnance.poll-interval-seconds:60} * 1000}")
    public void poll() {
        try {
            // Fetch all ACTIVE instrument symbols from DB
            List<String> symbols = jdbc.queryForList(
                    "SELECT symbol FROM instrument WHERE status = 'ACTIVE'",
                    String.class);

            if (symbols.isEmpty()) {
                log.debug("No active instruments to poll");
                return;
            }

            log.info("Polling Fauxnance for {} symbols", symbols.size());
            List<Quote> quotes = fauxnanceClient.fetchQuotes(symbols);

            for (Quote quote : quotes) {
                quoteCache.put(quote.symbol(), quote);
            }

            log.info("QuoteCache updated: {} symbols cached", quoteCache.size());

        } catch (Exception e) {
            log.error("Market data poll failed", e);
        }
    }
}
