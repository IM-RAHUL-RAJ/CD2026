package com.trade.executor.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.executor.domain.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client for the Fauxnance market data API.
 * Supports:
 *   1. fetchBatchQuotes: GET /quotes?symbols=A,B,C (up to 25 symbols = 1 quota unit)
 *   2. fetchSingleQuote: GET /quotes/{symbol} (single symbol quote check during order execution)
 * Auth header: X-Api-Key
 */
@Component
public class FauxnanceClient {

    private static final Logger log = LoggerFactory.getLogger(FauxnanceClient.class);
    private static final int MAX_SYMBOLS_PER_REQUEST = 25;

    @Value("${fauxnance.base-url:https://y4t9nq2bqf.execute-api.eu-west-2.amazonaws.com/v1}")
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
     * Single quote fetch: GET /quotes/{symbol}
     * Used by Trade Executor during execution of an order.
     */
    public Quote fetchSingleQuote(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }

        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/quotes/" + symbol.toUpperCase())
                    .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", apiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            return parseSingleResponse(response.getBody(), symbol);
        } catch (Exception e) {
            log.error("Fauxnance single quote API error for symbol {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Batch quote fetch: GET /quotes?symbols=A,B,C
     * Used by MarketDataPoller to efficiently pull quotes for up to 25 symbols per request.
     */
    public List<Quote> fetchQuotes(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyList();
        }

        List<Quote> results = new ArrayList<>();

        for (int i = 0; i < symbols.size(); i += MAX_SYMBOLS_PER_REQUEST) {
            List<String> batch = symbols.subList(i, Math.min(i + MAX_SYMBOLS_PER_REQUEST, symbols.size()));
            try {
                String symbolsParam = String.join(",", batch);
                String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                        .path("/quotes")
                        .queryParam("symbols", symbolsParam)
                        .toUriString();

                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Api-Key", apiKey);
                HttpEntity<Void> entity = new HttpEntity<>(headers);

                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.GET, entity, String.class);

                results.addAll(parseBatchResponse(response.getBody()));
            } catch (Exception e) {
                log.error("Fauxnance batch API error starting at index {}: {}", i, e.getMessage());
            }
        }

        return results;
    }

    private Quote parseSingleResponse(String json, String requestedSymbol) throws Exception {
        if (json == null || json.isBlank()) return null;

        JsonNode root = objectMapper.readTree(json);
        JsonNode data = root.path("data");
        JsonNode meta = root.path("meta");

        if (data.isMissingNode() || data.isNull()) return null;

        boolean stale = meta.path("stale").asBoolean(false);

        return new Quote(
                data.path("symbol").asText(requestedSymbol),
                parseBigDecimal(data, "price"),
                null, // bid not provided by Fauxnance
                null, // ask not provided by Fauxnance
                data.path("currency").asText(null),
                parseBigDecimal(data, "change"),
                parseBigDecimal(data, "changePercent"),
                parseBigDecimal(data, "previousClose"),
                data.path("marketState").asText(null),
                stale,
                data.path("asOf").asText(null)
        );
    }

    private List<Quote> parseBatchResponse(String json) throws Exception {
        List<Quote> quotes = new ArrayList<>();
        if (json == null || json.isBlank()) return quotes;

        JsonNode root = objectMapper.readTree(json);
        JsonNode quotesArray = root.path("data").path("quotes");

        for (JsonNode item : quotesArray) {
            if (!item.path("error").isMissingNode()) {
                log.warn("Fauxnance batch item error for {}: {}",
                        item.path("symbol").asText(), item.path("error").path("message").asText());
                continue;
            }

            try {
                boolean stale = item.path("stale").asBoolean(false);
                JsonNode q = item.path("quote");

                Quote quote = new Quote(
                        q.path("symbol").asText(null),
                        parseBigDecimal(q, "price"),
                        null,
                        null,
                        q.path("currency").asText(null),
                        parseBigDecimal(q, "change"),
                        parseBigDecimal(q, "changePercent"),
                        parseBigDecimal(q, "previousClose"),
                        q.path("marketState").asText(null),
                        stale,
                        q.path("asOf").asText(null)
                );

                if (quote.symbol() != null && quote.price() != null) {
                    quotes.add(quote);
                }
            } catch (Exception e) {
                log.warn("Failed to parse batch item: {}", item, e);
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
