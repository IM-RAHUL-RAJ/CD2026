package com.trade.executor.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.executor.domain.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HTTP client for the Fauxnance market data API.
 *
 * Fauxnance returns quotes in batches of up to 25 symbols per request.
 * Endpoint: GET /api/quotes?symbols=AAPL,TSLA,...&apiKey=<key>
 */
@Component
public class FauxnanceClient {

    private static final Logger log = LoggerFactory.getLogger(FauxnanceClient.class);
    private static final int MAX_SYMBOLS_PER_REQUEST = 25;

    @Value("${fauxnance.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${fauxnance.api-key:mock_api_key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public FauxnanceClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches quotes for the given symbols in batches of MAX_SYMBOLS_PER_REQUEST.
     * Returns an empty list if the API is unreachable.
     */
    public List<Quote> fetchQuotes(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyList();
        }

        List<Quote> results = new ArrayList<>();

        // Partition symbols into batches of ≤25
        for (int i = 0; i < symbols.size(); i += MAX_SYMBOLS_PER_REQUEST) {
            List<String> batch = symbols.subList(i, Math.min(i + MAX_SYMBOLS_PER_REQUEST, symbols.size()));
            try {
                String symbolsParam = String.join(",", batch);
                String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                        .path("/api/quotes")
                        .queryParam("symbols", symbolsParam)
                        .queryParam("apiKey", apiKey)
                        .toUriString();

                String response = restTemplate.getForObject(url, String.class);
                results.addAll(parseQuotes(response));
            } catch (Exception e) {
                log.error("Fauxnance API error for batch starting at {}: {}", i, e.getMessage());
            }
        }

        return results;
    }

    /**
     * Fetches all active instrument symbols from the local DB is the caller's job.
     * Here we just parse the Fauxnance JSON response array.
     */
    private List<Quote> parseQuotes(String json) throws Exception {
        List<Quote> quotes = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return quotes;
        }

        JsonNode root = objectMapper.readTree(json);
        // Fauxnance returns either an array or a wrapper {"quotes": [...]}
        JsonNode array = root.isArray() ? root : root.path("quotes");

        for (JsonNode node : array) {
            try {
                Quote quote = new Quote(
                        node.path("symbol").asText(null),
                        parseBigDecimal(node, "price"),
                        parseBigDecimal(node, "bid"),
                        parseBigDecimal(node, "ask"),
                        node.path("currency").asText(null),
                        parseBigDecimal(node, "change"),
                        parseBigDecimal(node, "changePercent"),
                        parseBigDecimal(node, "previousClose"),
                        node.path("marketState").asText(null),
                        node.path("stale").asBoolean(false),
                        node.path("quoteAsOf").asText(null)
                );
                if (quote.symbol() != null) {
                    quotes.add(quote);
                }
            } catch (Exception e) {
                log.warn("Could not parse quote node: {}", node, e);
            }
        }
        return quotes;
    }

    private BigDecimal parseBigDecimal(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull()) return null;
        try {
            return new BigDecimal(field.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
