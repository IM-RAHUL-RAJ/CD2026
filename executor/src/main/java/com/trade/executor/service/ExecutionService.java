package com.trade.executor.service;

import com.trade.executor.domain.FillDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Handles the DB side-effects of a fill or reject decision atomically.
 *
 * Fill settlement:
 *   1. Update order → FILLED with executed_price and executed_on (guarded: only if status=NEW)
 *   2. Update account cash_balance and version (optimistic lock on version)
 *   3. Upsert holding record (INSERT ... ON CONFLICT DO UPDATE)
 *
 * Reject settlement:
 *   1. Update order → REJECTED with rejection_reason
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final JdbcTemplate jdbc;

    public ExecutionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ----- Read helpers -----

    public Map<String, Object> fetchAccount(Long accountId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT account_id, status, cash_balance, version FROM account WHERE account_id = ?",
                accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> fetchHolding(Long accountId, String symbol) {
        // holding.ticker stores the instrument ticker; we look up by ticker via instrument table
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT h.quantity, h.average_price AS avg_cost " +
                "FROM holding h " +
                "JOIN instrument i ON i.ticker = h.ticker " +
                "WHERE h.account_id = ? AND i.symbol = ?",
                accountId, symbol);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ----- Write operations (transactional) -----

    @Transactional
    public void settle(Long orderId, Long accountId, String symbol, String side,
                       Long quantity, FillDecision decision, Map<String, Object> account) {

        // 1. Update order to FILLED (idempotency guard: only if still NEW)
        int updated = jdbc.update(
                "UPDATE orders SET status = 'FILLED', " +
                "executed_price = ?, executed_on = ? " +
                "WHERE order_id = ? AND status = 'NEW'",
                decision.executedPrice(), OffsetDateTime.now(), orderId);

        if (updated == 0) {
            // Already processed (duplicate delivery)
            log.warn("Order {} already settled, skipping duplicate settlement", orderId);
            return;
        }

        // 2. Update account cash_balance with optimistic lock
        BigDecimal newBalance = ((BigDecimal) account.get("cash_balance"))
                .add(decision.cashDelta());
        Long currentVersion = ((Number) account.get("version")).longValue();

        int cashUpdated = jdbc.update(
                "UPDATE account SET cash_balance = ?, version = version + 1 " +
                "WHERE account_id = ? AND version = ?",
                newBalance, accountId, currentVersion);

        if (cashUpdated == 0) {
            throw new IllegalStateException(
                    "Optimistic lock failure updating cash for account " + accountId +
                    " (version mismatch — concurrent modification)");
        }

        // 3. Upsert holding — look up the ticker for this symbol
        String ticker = jdbc.queryForObject(
                "SELECT ticker FROM instrument WHERE symbol = ?", String.class, symbol);

        if ("BUY".equalsIgnoreCase(side)) {
            jdbc.update(
                    "INSERT INTO holding (account_id, ticker, quantity, average_price, as_of_date) " +
                    "VALUES (?, ?, ?, ?, CURRENT_DATE) " +
                    "ON CONFLICT (account_id, ticker) DO UPDATE " +
                    "SET quantity = ?, average_price = ?, as_of_date = CURRENT_DATE",
                    accountId, ticker,
                    decision.newHoldingQty(), decision.newAvgCost(),   // INSERT values
                    decision.newHoldingQty(), decision.newAvgCost()    // UPDATE values
            );
        } else {
            // SELL: decrease quantity; if zero, remove holding row
            if (decision.newHoldingQty() == 0) {
                jdbc.update(
                        "DELETE FROM holding WHERE account_id = ? AND ticker = ?",
                        accountId, ticker);
            } else {
                jdbc.update(
                        "UPDATE holding SET quantity = ?, as_of_date = CURRENT_DATE " +
                        "WHERE account_id = ? AND ticker = ?",
                        decision.newHoldingQty(), accountId, ticker);
            }
        }

        log.info("Settled order {} for account {}: side={} qty={} price={}",
                 orderId, accountId, side, quantity, decision.executedPrice());
    }

    @Transactional
    public void reject(Long orderId, String reason) {
        int updated = jdbc.update(
                "UPDATE orders SET status = 'REJECTED', rejection_reason = ? " +
                "WHERE order_id = ? AND status = 'NEW'",
                reason, orderId);

        if (updated == 0) {
            log.warn("Order {} already settled, skipping duplicate rejection", orderId);
        }
    }
}
