package com.trade.executor.service;

import com.trade.executor.domain.FillDecision;
import com.trade.executor.exception.RetryableOrderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    public Map<String, Object> fetchOrder(Long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT order_id, account_id, ticker, side, quantity, price, order_type, status FROM orders WHERE order_id = ?",
                orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String fetchInstrumentStatus(String symbol) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM instrument WHERE symbol = ? OR ticker = ?",
                String.class, symbol, symbol);
        return rows.isEmpty() ? null : rows.get(0);
    }

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
                "WHERE h.account_id = ? AND (i.symbol = ? OR i.ticker = ?)",
                accountId, symbol, symbol);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ----- Write operations (transactional) -----

    @Transactional
    public void settle(Long orderId, Long accountId, String symbol, String side,
                       Long quantity, FillDecision decision, Map<String, Object> account) {

        // 1. Update order to FILLED (idempotency guard: only if still NEW)
        log.info("[*] UPDATE query: executed_price={}, executed_on={}, orderId={}", 
                decision.executedPrice(), OffsetDateTime.now(), orderId);
        log.info("[*] executedPrice type: {}, value: {}, scale: {}", 
                decision.executedPrice().getClass().getName(), 
                decision.executedPrice(), 
                decision.executedPrice().scale());
        
        // Ensure BigDecimal has correct scale for NUMERIC(18,8)
        BigDecimal executedPrice = decision.executedPrice().setScale(8, RoundingMode.HALF_UP);
        log.info("[*] After setScale: value={}, scale={}, toPlainString={}", 
                executedPrice, executedPrice.scale(), executedPrice.toPlainString());
        
        int updated = jdbc.update(
                "UPDATE orders SET status = 'FILLED', " +
                "executed_price = ?, executed_on = ? " +
                "WHERE order_id = ? AND status = 'NEW'",
                executedPrice, OffsetDateTime.now(), orderId);
        
        log.info("[OK] Update result: {} rows affected", updated);
        
        // Verify what was actually stored
        try {
            List<Map<String, Object>> verifyRows = jdbc.queryForList(
                    "SELECT executed_price, status FROM orders WHERE order_id = ?", orderId);
            if (!verifyRows.isEmpty()) {
                Map<String, Object> row = verifyRows.get(0);
                log.info("[VERIFY] Order {} in DB: status={}, executed_price={}, type={}", 
                        orderId, row.get("status"), row.get("executed_price"), 
                        row.get("executed_price") != null ? row.get("executed_price").getClass().getName() : "null");
            }
        } catch (Exception e) {
            log.error("[ERROR] Failed to verify order: {}", e.getMessage());
        }

        if (updated == 0) {
            log.warn("Order {} already settled or not in NEW state, skipping settlement", orderId);
            return;
        }

        // 2. Update account cash_balance with optimistic lock
        BigDecimal newBalance = ((BigDecimal) account.get("cash_balance"))
                .add(decision.cashDelta());
        Long currentVersion = ((Number) account.get("version")).longValue();
        
        log.info("[*] Updating account cash: current={}, delta={}, new={}, version={}", 
                 account.get("cash_balance"), decision.cashDelta(), newBalance, currentVersion);

        int cashUpdated = jdbc.update(
                "UPDATE account SET cash_balance = ?, version = version + 1 " +
                "WHERE account_id = ? AND version = ?",
                newBalance, accountId, currentVersion);

        if (cashUpdated == 0) {
            throw new RetryableOrderException(
                    "Optimistic lock failure updating cash for account " + accountId +
                    " (concurrent modification — will retry with fresh state)");
        }
        log.info("[OK] Account cash updated: {} rows affected", cashUpdated);

        // 3. Upsert holding — look up the ticker for this symbol
        List<String> tickers = jdbc.queryForList(
                "SELECT ticker FROM instrument WHERE symbol = ? OR ticker = ?", String.class, symbol, symbol);
        String ticker = tickers.isEmpty() ? symbol : tickers.get(0);
        log.info("[*] Upserting holding: accountId={}, ticker={}, newQty={}, newAvgCost={}", 
                 accountId, ticker, decision.newHoldingQty(), decision.newAvgCost());

        if ("BUY".equalsIgnoreCase(side)) {
            int holdingRows = jdbc.update(
                    "INSERT INTO holding (account_id, ticker, quantity, average_price, as_of_date) " +
                    "VALUES (?, ?, ?, ?, CURRENT_DATE) " +
                    "ON CONFLICT (account_id, ticker) DO UPDATE " +
                    "SET quantity = ?, average_price = ?, as_of_date = CURRENT_DATE",
                    accountId, ticker,
                    decision.newHoldingQty(), decision.newAvgCost(),   // INSERT values
                    decision.newHoldingQty(), decision.newAvgCost()    // UPDATE values
            );
            log.info("[OK] Holding upserted: {} rows affected", holdingRows);
        } else {
            // SELL: decrease quantity; if zero, remove holding row
            if (decision.newHoldingQty() == 0) {
                int deleteRows = jdbc.update(
                        "DELETE FROM holding WHERE account_id = ? AND ticker = ?",
                        accountId, ticker);
                log.info("[OK] Holding deleted: {} rows removed", deleteRows);
            } else {
                int updateRows = jdbc.update(
                        "UPDATE holding SET quantity = ?, as_of_date = CURRENT_DATE " +
                        "WHERE account_id = ? AND ticker = ?",
                        decision.newHoldingQty(), accountId, ticker);
                log.info("[OK] Holding updated: {} rows affected", updateRows);
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
