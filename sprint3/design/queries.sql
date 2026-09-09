-- QUERY 1: All open orders for one account, newest first

SELECT
    o.order_id,
    o.account_id,
    o.instrument_id,
    i.ticker,
    o.side,
    o.quantity,
    o.price,
    o.status,
    o.received_at
FROM orders o
JOIN instrument i
    ON o.instrument_id = i.instrument_id
WHERE o.account_id = 100001
  AND o.status = 'NEW'
ORDER BY o.received_at DESC;


-- QUERY 2: Last 50 orders for one account, any state, newest first

SELECT
    o.order_id,
    o.account_id,
    o.instrument_id,
    i.ticker,
    o.side,
    o.quantity,
    o.price,
    o.status,
    o.received_at
FROM orders o
JOIN instrument i
    ON o.instrument_id = i.instrument_id
WHERE o.account_id = 100001
ORDER BY o.received_at DESC
LIMIT 50;


-- QUERY 3: Current holdings for one account with quantity and average cost

SELECT
    h.ticker,
    h.quantity,
    h.average_price
FROM holding h
WHERE h.account_id = 100001
ORDER BY h.ticker;


-- QUERY 4: Every order since a given timestamp across all accounts

SELECT
    o.order_id,
    o.account_id,
    a.account_name,
    i.ticker,
    o.side,
    o.quantity,
    o.price,
    o.status,
    o.received_at
FROM orders o
JOIN account a
    ON o.account_id = a.account_id
JOIN instrument i
    ON o.instrument_id = i.instrument_id
WHERE o.received_at >= '2026-08-26 00:00:00+00'
ORDER BY o.received_at ASC;


-- QUERY 5: Resolve account from customer-facing reference

SELECT
    account_id,
    account_name,
    client_id,
    currency,
    cash_balance,
    status
FROM account
WHERE account_name = 'ACC-ARUN-001';


-- QUERY 6: One account's filled orders oldest first,

WITH filled_orders AS (
    SELECT
        o.order_id,
        o.account_id,
        i.ticker,
        o.side,
        o.quantity,
        o.price,
        o.quantity * o.price AS order_value,
        o.received_at
    FROM orders o
    JOIN instrument i
        ON o.instrument_id = i.instrument_id
    WHERE o.account_id = 100001
      AND o.status = 'FILLED'
),
ranked_orders AS (
    SELECT
        *,
        SUM(
            CASE
                WHEN side = 'BUY' THEN order_value
                ELSE -order_value
            END
        ) OVER (
            ORDER BY received_at, order_id
            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
        ) AS running_cash_committed,
        RANK() OVER (
            PARTITION BY ticker
            ORDER BY order_value DESC
        ) AS value_rank_within_instrument
    FROM filled_orders
)
SELECT
    order_id,
    account_id,
    ticker,
    side,
    quantity,
    price,
    order_value,
    received_at,
    running_cash_committed,
    value_rank_within_instrument
FROM ranked_orders
ORDER BY received_at ASC, order_id ASC;